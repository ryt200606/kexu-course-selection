package edu.ustb.coursev2;

import com.google.ortools.sat.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Same immutable candidates and constraints feed both the exact model and the greedy baseline. */
final class AllocationSolver {
  static final int MAX_RANK = 8;

  record Slot(
      int weekday, int startPeriod, int endPeriod, int startWeek, int endWeek, String room) {
    boolean overlaps(Slot b) {
      return weekday == b.weekday
          && startPeriod <= b.endPeriod
          && b.startPeriod <= endPeriod
          && startWeek <= b.endWeek
          && b.startWeek <= endWeek;
    }
  }

  record Choice(
      String studentId,
      String sectionId,
      String offeringId,
      int rank,
      int credits,
      List<Slot> meetings,
      List<String> reasons) {
    String key() {
      return studentId + "/" + sectionId;
    }

    boolean clashes(Choice b) {
      return offeringId.equals(b.offeringId)
          || meetings.stream().anyMatch(a -> b.meetings.stream().anyMatch(a::overlaps));
    }
  }

  record Input(
      String seed,
      Map<String, Integer> seats,
      Map<String, Integer> budgets,
      List<Choice> choices) {}

  record Assignment(String studentId, String sectionId, int rank) {}

  record Score(int covered, List<Integer> rankHits, int assigned) {}

  record Stage(String name, String status, Long objective, Double bestBound, long elapsedMs) {}

  record Result(
      String solverStatus,
      List<Assignment> assignments,
      Score metrics,
      List<Assignment> greedyAssignments,
      Score greedy,
      List<String> provenObjectives,
      List<Stage> stages) {}

  static String sha(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  static CpSolver solver(double seconds) {
    CpSolver engine = new CpSolver();
    engine.getParameters().setNumSearchWorkers(1).setRandomSeed(0).setMaxTimeInSeconds(seconds);
    return engine;
  }

  static List<Assignment> assigned(List<Choice> cs) {
    return cs.stream()
        .sorted(Comparator.comparing(Choice::studentId).thenComparingInt(Choice::rank))
        .map(c -> new Assignment(c.studentId, c.sectionId, c.rank))
        .toList();
  }

  static Score score(List<Assignment> rows) {
    int[] hits = new int[MAX_RANK];
    rows.forEach(c -> hits[c.rank - 1]++);
    return new Score(
        (int) rows.stream().map(Assignment::studentId).distinct().count(),
        Arrays.stream(hits).boxed().toList(),
        rows.size());
  }

  private static <T> Map<String, List<T>> group(List<T> items, Function<T, String> key) {
    return items.stream().collect(Collectors.groupingBy(key, TreeMap::new, Collectors.toList()));
  }

  private static List<Choice> greedy(Input input, List<Choice> cs) {
    Map<String, Integer> available = new HashMap<>(input.seats),
        budgets = new HashMap<>(input.budgets);
    Map<String, List<Choice>> perStudent = group(cs, Choice::studentId), accepted = new TreeMap<>();
    List<String> order = new ArrayList<>(perStudent.keySet());
    order.sort(Comparator.comparing(id -> sha(input.seed + "/student/" + id)));
    // Give each student one turn per pass, considering the highest still admissible rank.
    for (int pass = 0; pass < MAX_RANK; pass++) {
      boolean progressed = false;
      for (String student : order) {
        var prior = accepted.computeIfAbsent(student, k -> new ArrayList<>());
        for (Choice c : perStudent.get(student)) {
          if (available.get(c.sectionId) < 1
              || budgets.get(student) < c.credits
              || prior.stream().anyMatch(c::clashes)) continue;
          prior.add(c);
          available.merge(c.sectionId, -1, Integer::sum);
          budgets.merge(student, -c.credits, Integer::sum);
          progressed = true;
          break;
        }
      }
      if (!progressed) break;
    }
    return accepted.values().stream().flatMap(Collection::stream).toList();
  }

  static Result solve(Input input) {
    SolverRuntime.load();
    List<Choice> cs =
        input.choices.stream()
            .filter(c -> c.reasons.isEmpty())
            .sorted(Comparator.comparing(Choice::studentId).thenComparingInt(Choice::rank))
            .toList();
    List<Assignment> baseline = assigned(greedy(input, cs));
    CpModel model = new CpModel();
    Map<Choice, BoolVar> vars = new LinkedHashMap<>();
    cs.forEach(c -> vars.put(c, model.newBoolVar(c.key())));
    for (var g : group(cs, Choice::sectionId).entrySet())
      model.addLessOrEqual(
          LinearExpr.sum(g.getValue().stream().map(vars::get).toArray(BoolVar[]::new)),
          input.seats.get(g.getKey()));
    var objectives = new LinkedHashMap<String, LinearExprBuilder>();
    objectives.put("covered", LinearExpr.newBuilder());
    for (int rank = 1; rank <= MAX_RANK; rank++)
      objectives.put("rank" + rank, LinearExpr.newBuilder());
    objectives.put("seedTie", LinearExpr.newBuilder());
    var lottery =
        cs.stream()
            .sorted(Comparator.comparing(c -> sha(input.seed + "/edge/" + c.key())))
            .toList();
    for (int i = 0; i < lottery.size(); i++) {
      Choice c = lottery.get(i);
      objectives.get("rank" + c.rank).add(vars.get(c));
      objectives.get("seedTie").addTerm(vars.get(c), lottery.size() - i);
    }
    for (var entry : group(cs, Choice::studentId).entrySet()) {
      List<Choice> choices = entry.getValue();
      BoolVar covered = model.newBoolVar("covered/" + entry.getKey());
      objectives.get("covered").add(covered);
      LinearExprBuilder credits = LinearExpr.newBuilder(), total = LinearExpr.newBuilder();
      for (int i = 0; i < choices.size(); i++) {
        Choice a = choices.get(i);
        BoolVar x = vars.get(a);
        credits.addTerm(x, a.credits);
        total.add(x);
        model.addLessOrEqual(x, covered);
        for (int j = 0; j < i; j++)
          if (a.clashes(choices.get(j)))
            model.addAtMostOne(new Literal[] {x, vars.get(choices.get(j))});
      }
      model.addGreaterOrEqual(total, covered);
      model.addLessOrEqual(credits, input.budgets.get(entry.getKey()));
    }
    List<Assignment> best = baseline;
    List<String> proven = new ArrayList<>();
    List<Stage> stages = new ArrayList<>();
    String status = "OPTIMAL";
    long deadline = System.nanoTime() + 20_000_000_000L;
    for (var objective : objectives.entrySet()) {
      double remaining = (deadline - System.nanoTime()) / 1e9;
      if (remaining <= 0) {
        status = "FEASIBLE";
        stages.add(new Stage(objective.getKey(), "TIME_BUDGET", null, null, 0));
        break;
      }
      LinearExpr expr = objective.getValue().build();
      model.maximize(expr);
      CpSolver engine = solver(remaining);
      long start = System.nanoTime();
      var outcome = engine.solve(model);
      boolean hasSolution = outcome == CpSolverStatus.FEASIBLE || outcome == CpSolverStatus.OPTIMAL;
      stages.add(
          new Stage(
              objective.getKey(),
              outcome.name(),
              hasSolution ? engine.value(expr) : null,
              engine.bestObjectiveBound(),
              (System.nanoTime() - start) / 1_000_000));
      if (outcome == CpSolverStatus.MODEL_INVALID || outcome == CpSolverStatus.INFEASIBLE)
        throw new IllegalStateException("Invalid allocation model: " + outcome);
      if (outcome == CpSolverStatus.UNKNOWN) {
        status = "FEASIBLE";
        break;
      }
      var selected = assigned(cs.stream().filter(c -> engine.booleanValue(vars.get(c))).toList());
      if (compare(score(selected), score(best)) >= 0) best = selected;
      if (outcome != CpSolverStatus.OPTIMAL) {
        status = "FEASIBLE";
        break;
      }
      model.addEquality(expr, engine.value(expr));
      proven.add(objective.getKey());
    }
    return new Result(
        status,
        best,
        score(best),
        baseline,
        score(baseline),
        List.copyOf(proven),
        List.copyOf(stages));
  }

  static int compare(Score a, Score b) {
    if (a.covered() != b.covered()) return Integer.compare(a.covered(), b.covered());
    for (int i = 0; i < MAX_RANK; i++)
      if (!a.rankHits().get(i).equals(b.rankHits().get(i)))
        return Integer.compare(a.rankHits().get(i), b.rankHits().get(i));
    return 0;
  }
}
