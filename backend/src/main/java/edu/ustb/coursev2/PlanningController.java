package edu.ustb.coursev2;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v3")
class PlanningController {
  private final PlanningService plans;

  PlanningController(PlanningService plans) {
    this.plans = plans;
  }

  @GetMapping("/student/preferences/{roundId}")
  Map<String, Object> choices(@PathVariable String roundId) {
    return plans.preferences(roundId);
  }

  @PutMapping("/student/preferences/{roundId}")
  Map<String, Object> save(@PathVariable String roundId, @RequestBody Map<String, Object> body) {
    return plans.savePreferences(roundId, body);
  }

  @PostMapping("/admin/planning/rounds/{roundId}/commit")
  Map<String, Object> commit(@PathVariable String roundId) {
    return plans.commit(roundId);
  }

  @PostMapping("/admin/planning/allocations/preview")
  Map<String, Object> allocation(@RequestBody Map<String, Object> body) {
    return plans.allocationPreview(Api.id(body, "roundId"));
  }

  @PostMapping("/admin/planning/schedules/preview")
  Map<String, Object> schedule(@RequestBody Map<String, Object> body) {
    return plans.schedulePreview(body);
  }

  @GetMapping("/admin/planning/runs")
  Api.Page runs(
      @RequestParam(required = false) String termId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return plans.runs(termId, page, size);
  }

  @GetMapping("/admin/planning/runs/{id}")
  Map<String, Object> run(@PathVariable String id) {
    return plans.run(id);
  }

  @PostMapping("/admin/planning/runs/{id}/publish")
  Map<String, Object> publish(
      @PathVariable String id,
      @RequestBody Map<String, Object> body,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return plans.publish(id, Api.text(body, "snapshotHash", 64), key);
  }
}
