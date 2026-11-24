package edu.ustb.coursev2;

import com.google.ortools.sat.*;
import java.util.*;

final class ScheduleSolver {
  record Room(String id, int capacity) {}

  record Unavailable(String teacherId, String room, AllocationSolver.Slot slot) {}

  record Demand(
      String sectionId,
      String teacherId,
      int capacity,
      int lockedIndex,
      List<AllocationSolver.Slot> slots) {}

  record Booking(String sectionId, String teacherId, AllocationSolver.Slot slot) {}

  record Input(
      List<Room> rooms,
      List<Demand> sections,
      List<Unavailable> unavailable,
      List<Booking> existing) {}

  record Choice(
      String sectionId, String teacherId, int index, int unusedSeats, AllocationSolver.Slot slot) {}

  record Result(
      String solverStatus,
      List<Choice> assignments,
      int requested,
      int scheduled,
      int greedyScheduled,
      int greedyTeacherGaps,
      List<Map<String, Object>> excluded,
      List<AllocationSolver.Stage> stages) {}

  static boolean conflict(String ta, AllocationSolver.Slot a, String tb, AllocationSolver.Slot b) {
    return (Objects.equals(ta, tb) || Objects.equals(a.room(), b.room())) && a.overlaps(b);
  }

  static Result solve(Input input) {
    SolverRuntime.load();
    Map<String, Integer> rooms = new TreeMap<>();
    input.rooms.forEach(r -> rooms.put(r.id, r.capacity));
    List<Choice> candidates = new ArrayList<>(), greedy = new ArrayList<>();
    List<Map<String, Object>> excluded = new ArrayList<>();
    for (Demand d : input.sections) {
      Choice first = null;
      for (int i = 0; i < d.slots.size(); i++) {
        var s = d.slots.get(i);
        String reason = null;
        if (d.lockedIndex >= 0 && i != d.lockedIndex) reason = "LOCKED_SLOT";
        else if (!rooms.containsKey(s.room()) || rooms.get(s.room()) < d.capacity)
          reason = "ROOM_CAPACITY";
        else if (input.unavailable.stream()
            .anyMatch(
                u ->
                    (d.teacherId.equals(u.teacherId) || s.room().equals(u.room))
                        && s.overlaps(u.slot))) reason = "UNAVAILABLE";
        else if (input.existing.stream()
            .anyMatch(old -> conflict(d.teacherId, s, old.teacherId, old.slot)))
          reason = "PUBLISHED_CONFLICT";
        if (reason != null) {
          excluded.add(Map.of("sectionId", d.sectionId, "slotIndex", i, "reason", reason));
          continue;
        }
        Choice c = new Choice(d.sectionId, d.teacherId, i, rooms.get(s.room()) - d.capacity, s);
        candidates.add(c);
        if (first == null
            && greedy.stream().noneMatch(g -> conflict(c.teacherId, s, g.teacherId, g.slot)))
          first = c;
      }
      if (first != null) greedy.add(first);
    }
    CpModel model = new CpModel();
    BoolVar[] vars = new BoolVar[candidates.size()];
    var count = LinearExpr.newBuilder();
    Map<String, List<BoolVar>> section = new TreeMap<>();
    var cost = LinearExpr.newBuilder();
    long slackBound = 1L + 10000L * input.sections.size();
    for (int i = 0; i < candidates.size(); i++) {
      Choice c = candidates.get(i);
      vars[i] = model.newBoolVar(c.sectionId + "/" + c.index);
      count.add(vars[i]);
      cost.addTerm(vars[i], slackBound * c.index + c.unusedSeats);
      section.computeIfAbsent(c.sectionId, k -> new ArrayList<>()).add(vars[i]);
      for (int j = 0; j < i; j++) {
        Choice old = candidates.get(j);
        if (!c.sectionId.equals(old.sectionId)
            && conflict(c.teacherId, c.slot, old.teacherId, old.slot))
          model.addAtMostOne(new Literal[] {vars[i], vars[j]});
      }
    }
    section.values().forEach(v -> model.addAtMostOne(v.toArray(BoolVar[]::new)));
    var teacherGapCost = gaps(model, input, candidates, vars);
    model.maximize(count);
    var engine = AllocationSolver.solver(8);
    long start = System.nanoTime();
    var status = engine.solve(model);
    var stages = new ArrayList<AllocationSolver.Stage>();
    boolean feasible = status == CpSolverStatus.OPTIMAL || status == CpSolverStatus.FEASIBLE;
    stages.add(
        new AllocationSolver.Stage(
            "scheduled",
            status.name(),
            feasible ? Math.round(engine.objectiveValue()) : null,
            engine.bestObjectiveBound(),
            (System.nanoTime() - start) / 1_000_000));
    if (status == CpSolverStatus.UNKNOWN)
      return new Result(
          "FEASIBLE",
          greedy,
          input.sections.size(),
          greedy.size(),
          greedy.size(),
          teacherGaps(input, greedy),
          excluded,
          stages);
    if (status != CpSolverStatus.OPTIMAL && status != CpSolverStatus.FEASIBLE)
      throw new IllegalStateException("Schedule model " + status);
    var selected = read(candidates, vars, engine);
    if (status == CpSolverStatus.OPTIMAL) {
      model.addEquality(count, selected.size());
      model.minimize(teacherGapCost);
      var gapSolver = AllocationSolver.solver(5);
      start = System.nanoTime();
      status = gapSolver.solve(model);
      feasible = status == CpSolverStatus.OPTIMAL || status == CpSolverStatus.FEASIBLE;
      stages.add(
          new AllocationSolver.Stage(
              "teacherGapPeriods",
              status.name(),
              feasible ? Math.round(gapSolver.objectiveValue()) : null,
              gapSolver.bestObjectiveBound(),
              (System.nanoTime() - start) / 1_000_000));
      if (feasible) selected = read(candidates, vars, gapSolver);
      if (status == CpSolverStatus.OPTIMAL)
        model.addEquality(teacherGapCost, gapSolver.value(teacherGapCost));
    }
    if (status == CpSolverStatus.OPTIMAL) {
      model.minimize(cost);
      var tie = AllocationSolver.solver(5);
      start = System.nanoTime();
      status = tie.solve(model);
      feasible = status == CpSolverStatus.OPTIMAL || status == CpSolverStatus.FEASIBLE;
      stages.add(
          new AllocationSolver.Stage(
              "slotPreferenceThenUnusedSeats",
              status.name(),
              feasible ? Math.round(tie.objectiveValue()) : null,
              tie.bestObjectiveBound(),
              (System.nanoTime() - start) / 1_000_000));
      if (status == CpSolverStatus.FEASIBLE || status == CpSolverStatus.OPTIMAL)
        selected = read(candidates, vars, tie);
    }
    return new Result(
        status == CpSolverStatus.OPTIMAL ? "OPTIMAL" : "FEASIBLE",
        selected,
        input.sections.size(),
        selected.size(),
        greedy.size(),
        teacherGaps(input, greedy),
        excluded,
        stages);
  }

  static List<Choice> read(List<Choice> options, BoolVar[] vars, CpSolver solver) {
    List<Choice> selected = new ArrayList<>();
    for (int i = 0; i < vars.length; i++)
      if (solver.booleanValue(vars[i])) selected.add(options.get(i));
    return selected;
  }

  /**
   * Weight each distinct week interval by its width, so odd scheduling windows are counted exactly.
   */
  private static LinearExprBuilder gaps(
      CpModel model, Input input, List<Choice> candidates, BoolVar[] vars) {
    var total = LinearExpr.newBuilder();
    var teachers = new TreeSet<String>();
    input.sections.forEach(d -> teachers.add(d.teacherId));
    for (String teacher : teachers)
      for (int day = 1; day <= 7; day++) {
        var choices = new ArrayList<Integer>();
        var fixed = new ArrayList<AllocationSolver.Slot>();
        var boundaries = new TreeSet<Integer>();
        for (int i = 0; i < candidates.size(); i++) {
          var c = candidates.get(i);
          if (c.teacherId.equals(teacher) && c.slot.weekday() == day) {
            choices.add(i);
            boundaries.add(c.slot.startWeek());
            boundaries.add(c.slot.endWeek() + 1);
          }
        }
        for (var f : input.existing)
          if (teacher.equals(f.teacherId) && f.slot.weekday() == day) {
            fixed.add(f.slot);
            boundaries.add(f.slot.startWeek());
            boundaries.add(f.slot.endWeek() + 1);
          }
        var ordered = new ArrayList<>(boundaries);
        for (int w = 0; w + 1 < ordered.size(); w++) {
          int week = ordered.get(w), width = ordered.get(w + 1) - week;
          for (int p = 2; p <= 13; p++) {
            final int period = p;
            LinearArgument left =
                any(model, candidates, vars, choices, fixed, week, s -> s.endPeriod() < period);
            if (left == null) continue;
            LinearArgument right =
                any(model, candidates, vars, choices, fixed, week, s -> s.startPeriod() > period);
            if (right == null) continue;
            LinearArgument filled =
                any(
                    model,
                    candidates,
                    vars,
                    choices,
                    fixed,
                    week,
                    s -> s.startPeriod() <= period && period <= s.endPeriod());
            BoolVar gap =
                model.newBoolVar("gap/" + teacher + "/" + day + "/" + week + "/" + period);
            var lb = LinearExpr.newBuilder().add(gap).addTerm(left, -1).addTerm(right, -1);
            if (filled != null) lb.add(filled);
            model.addGreaterOrEqual(lb, -1);
            total.addTerm(gap, width);
          }
        }
      }
    return total;
  }

  private static LinearArgument any(
      CpModel model,
      List<Choice> cs,
      BoolVar[] vars,
      List<Integer> choices,
      List<AllocationSolver.Slot> fixed,
      int week,
      java.util.function.Predicate<AllocationSolver.Slot> keep) {
    for (var s : fixed)
      if (s.startWeek() <= week && week <= s.endWeek() && keep.test(s))
        return LinearExpr.constant(1);
    var selected = new ArrayList<LinearArgument>();
    for (int i : choices) {
      var s = cs.get(i).slot;
      if (s.startWeek() <= week && week <= s.endWeek() && keep.test(s)) selected.add(vars[i]);
    }
    if (selected.isEmpty()) return null;
    if (selected.size() == 1) return selected.get(0);
    var v = model.newBoolVar("occupied");
    model.addMaxEquality(v, selected.toArray(LinearArgument[]::new));
    return v;
  }

  static int teacherGaps(Input input, List<Choice> assignments) {
    Map<String, Integer> occupied = new HashMap<>();
    Set<String> teachers = new HashSet<>();
    input.sections.forEach(d -> teachers.add(d.teacherId));
    var bookings = new ArrayList<>(input.existing);
    assignments.forEach(c -> bookings.add(new Booking(c.sectionId, c.teacherId, c.slot)));
    for (var b : bookings)
      if (teachers.contains(b.teacherId)) {
        var s = b.slot;
        int mask = ((1 << (s.endPeriod() - s.startPeriod() + 1)) - 1) << (s.startPeriod() - 1);
        for (int w = s.startWeek(); w <= s.endWeek(); w++)
          occupied.merge(b.teacherId + "/" + s.weekday() + "/" + w, mask, (a, bb) -> a | bb);
      }
    return occupied.values().stream()
        .mapToInt(
            v ->
                32
                    - Integer.numberOfLeadingZeros(v)
                    - Integer.numberOfTrailingZeros(v)
                    - Integer.bitCount(v))
        .sum();
  }
}
