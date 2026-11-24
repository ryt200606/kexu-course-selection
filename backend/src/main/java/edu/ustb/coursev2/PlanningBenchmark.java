package edu.ustb.coursev2;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Isolated synthetic CPU benchmark; no Spring context, credentials, database or network. */
public final class PlanningBenchmark {
  private static final long SEED = 20261124L;
  private static final ObjectMapper JSON =
      new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

  private static String digest(Object input) throws Exception {
    return AllocationSolver.sha(JSON.writeValueAsString(input));
  }

  static AllocationSolver.Input allocation(int size) {
    Random r = new Random(SEED + size);
    int sections = Math.max(12, size / 3);
    Map<String, Integer> seats = new TreeMap<>(), limits = new TreeMap<>();
    List<AllocationSolver.Choice> cs = new ArrayList<>();
    for (int i = 0; i < sections; i++) seats.put("S" + i, 4);
    for (int i = 0; i < size; i++) {
      String student = "P" + i;
      limits.put(student, 3 + (i % 3) * 3);
      var list = new LinkedHashSet<Integer>();
      list.add(r.nextInt(Math.max(2, sections / 8)));
      while (list.size() < 5) list.add(r.nextInt(sections));
      int rank = 0;
      for (int n : list)
        cs.add(
            new AllocationSolver.Choice(
                student,
                "S" + n,
                "C" + (n / 2),
                ++rank,
                3,
                List.of(
                    new AllocationSolver.Slot(
                        1 + n % 5, 1 + 2 * (n % 3), 2 + 2 * (n % 3), 1, 16, "R" + n)),
                List.of()));
    }
    return new AllocationSolver.Input("benchmark-commit-" + SEED, seats, limits, cs);
  }

  static ScheduleSolver.Input schedule(
      int sections, int roomCount, int teachers, int days, int periods, int options) {
    Random r = new Random(SEED + sections);
    List<ScheduleSolver.Room> rooms = new ArrayList<>();
    List<ScheduleSolver.Demand> demands = new ArrayList<>();
    for (int i = 0; i < roomCount; i++)
      rooms.add(new ScheduleSolver.Room("R" + i, 35 + i % 3 * 10));
    for (int i = 0; i < sections; i++) {
      String teacher = "T" + (i % teachers);
      var slots = new LinkedHashSet<AllocationSolver.Slot>();
      while (slots.size() < options) {
        int period = 1 + 2 * r.nextInt(periods);
        slots.add(
            new AllocationSolver.Slot(
                1 + r.nextInt(days), period, period + 1, 1, 16, "R" + r.nextInt(roomCount)));
      }
      demands.add(new ScheduleSolver.Demand("D" + i, teacher, 30, -1, List.copyOf(slots)));
    }
    return new ScheduleSolver.Input(rooms, demands, List.of(), List.of());
  }

  private static Map<String, Object> conflicts(ScheduleSolver.Input input) {
    long pairs = 0, overlap = 0;
    var rows = input.sections();
    for (int i = 0; i < rows.size(); i++)
      for (int j = 0; j < i; j++)
        for (var a : rows.get(i).slots())
          for (var b : rows.get(j).slots()) {
            pairs++;
            if (ScheduleSolver.conflict(rows.get(i).teacherId(), a, rows.get(j).teacherId(), b))
              overlap++;
          }
    return Map.of(
        "candidatePairs",
        pairs,
        "incompatiblePairs",
        overlap,
        "density",
        (double) overlap / Math.max(1, pairs));
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1 || Files.exists(Path.of(args[0])))
      throw new IllegalArgumentException("Provide a new benchmark JSON destination path");
    var receipt = new TreeMap<String, Object>();
    receipt.put("format", "course-planning-benchmark-v1");
    receipt.put("ortools", "9.14.6206");
    receipt.put("seed", SEED);
    receipt.put("threads", 1);
    receipt.put("startedAt", Instant.now().toString());
    receipt.put("java", System.getProperty("java.runtime.version"));
    receipt.put(
        "os",
        System.getProperty("os.name")
            + " "
            + System.getProperty("os.version")
            + " "
            + System.getProperty("os.arch"));
    receipt.put("processorsVisible", Runtime.getRuntime().availableProcessors());
    var alloc = new ArrayList<Object>();
    var sched = new ArrayList<Object>();
    long t = System.nanoTime();
    SolverRuntime.load();
    receipt.put("nativeLoadMs", (System.nanoTime() - t) / 1_000_000);
    for (int n : new int[] {24, 96, 320}) {
      var input = allocation(n);
      t = System.nanoTime();
      var result = AllocationSolver.solve(input);
      var row =
          Api.object(
              "students",
              n,
              "sections",
              input.seats().size(),
              "choices",
              input.choices().size(),
              "sha256",
              digest(input),
              "elapsedMs",
              (System.nanoTime() - t) / 1_000_000,
              "solverStatus",
              result.solverStatus(),
              "metrics",
              result.metrics(),
              "greedy",
              result.greedy(),
              "stages",
              result.stages());
      alloc.add(row);
      System.out.println(
          "Allocation "
              + n
              + ": "
              + result.solverStatus()
              + " "
              + result.metrics().covered()
              + "/"
              + n);
    }
    for (int[] size :
        List.of(
            new int[] {12, 8, 8, 5, 4, 3},
            new int[] {30, 5, 6, 3, 4, 5},
            new int[] {55, 3, 4, 2, 3, 7})) {
      var input = schedule(size[0], size[1], size[2], size[3], size[4], size[5]);
      t = System.nanoTime();
      var result = ScheduleSolver.solve(input);
      var row =
          Api.object(
              "sections",
              size[0],
              "optionsPerSection",
              size[5],
              "rooms",
              size[1],
              "sha256",
              digest(input),
              "elapsedMs",
              (System.nanoTime() - t) / 1_000_000,
              "solverStatus",
              result.solverStatus(),
              "scheduled",
              result.scheduled(),
              "teacherGapPeriods",
              ScheduleSolver.teacherGaps(input, result.assignments()),
              "greedyScheduled",
              result.greedyScheduled(),
              "greedyTeacherGapPeriods",
              result.greedyTeacherGaps(),
              "conflicts",
              conflicts(input),
              "stages",
              result.stages());
      sched.add(row);
      System.out.println(
          "Schedule "
              + size[0]
              + ": "
              + result.solverStatus()
              + " "
              + result.scheduled()
              + "/"
              + size[0]);
    }
    receipt.put("allocation", alloc);
    receipt.put("scheduling", sched);
    receipt.put("finishedAt", Instant.now().toString());
    Files.writeString(
        Path.of(args[0]),
        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(receipt) + System.lineSeparator(),
        StandardOpenOption.CREATE_NEW);
  }

  private PlanningBenchmark() {}
}
