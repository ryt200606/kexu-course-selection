package edu.ustb.coursev2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fixed-resource doubling experiment using the exact production model, deadlines and seeded input generator. */
public final class AllocationCeiling {
  public static void main(String[] args) throws Exception {
    if (args.length != 1 || Files.exists(Path.of(args[0]))) {
      throw new IllegalArgumentException("Provide a new JSON output file");
    }
    Path out = Path.of(args[0]);
    var json = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    var receipt = new LinkedHashMap<String, Object>();
    var stages = new ArrayList<Map<String,Object>>();
    receipt.put("startedUtc", Instant.now().toString());
    receipt.put("seed", 20261124);
    receipt.put("solverBudgetSeconds", 20);
    receipt.put("workerThreads", 1);
    receipt.put("stages", stages);
    SolverRuntime.load();
    for (int size : new int[]{320, 640, 1280, 2560, 5120}) {
      var input = PlanningBenchmark.allocation(size);
      long start = System.nanoTime();
      var result = AllocationSolver.solve(input);
      var row = new LinkedHashMap<String,Object>();
      row.put("students", size);
      row.put("choices", input.choices().size());
      row.put("sections", input.seats().size());
      row.put("inputSha256", AllocationSolver.sha(json.writeValueAsString(input)));
      row.put("solverStatus", result.solverStatus());
      row.put("metrics", result.metrics());
      row.put("greedy", result.greedy());
      row.put("elapsedMs", (System.nanoTime()-start)/1_000_000);
      row.put("lexicographicStages", result.stages());
      stages.add(row);
      json.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), receipt);
      if (!"OPTIMAL".equals(result.solverStatus())) break;
    }
    receipt.put("finishedUtc", Instant.now().toString());
    json.writerWithDefaultPrettyPrinter().writeValue(out.toFile(),receipt);
  }
}

