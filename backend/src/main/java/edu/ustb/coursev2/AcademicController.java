package edu.ustb.coursev2;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v3")
public class AcademicController {
  private final AcademicCurriculum curriculum;
  private final AcademicPlans plans;
  private final AcademicReviews reviews;
  private final Db db;

  AcademicController(
      AcademicCurriculum curriculum, AcademicPlans plans, AcademicReviews reviews, Db db) {
    this.curriculum = curriculum;
    this.plans = plans;
    this.reviews = reviews;
    this.db = db;
  }

  @GetMapping("/admin/curricula")
  public Api.Page curricula(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return curriculum.catalog(page, size);
  }

  @PostMapping("/admin/curricula")
  public Map<String, Object> newCurriculum(@RequestBody Map<String, Object> body) {
    return curriculum.create(body);
  }

  @GetMapping("/admin/curricula/{id}/progress")
  public Api.Page cohortProgress(
      @PathVariable String id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return curriculum.cohortProgress(id, page, size);
  }

  @PostMapping("/admin/curricula/{id}/assign")
  public Map<String, Object> assign(
      @PathVariable String id, @RequestBody Map<String, Object> body) {
    return curriculum.assign(id, body);
  }

  @GetMapping("/student/curriculum")
  public Map<String, Object> curriculum() {
    return curriculum.progress();
  }

  @GetMapping("/student/plans")
  public Map<String, Object> plans(@RequestParam String termId) {
    return plans.saved(termId);
  }

  @PutMapping("/student/plans")
  public Map<String, Object> save(
      @RequestParam String termId, @RequestBody Map<String, Object> body) {
    return plans.save(termId, body);
  }

  @PostMapping("/student/plans/compare")
  public Map<String, Object> compare(@RequestBody Map<String, Object> body) {
    return plans.compare(body);
  }

  @GetMapping("/grade-reviews")
  public Api.Page reviews(
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String termId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return reviews.list(state, termId, page, size);
  }

  @GetMapping("/grade-reviews/{id}")
  public Map<String, Object> review(@PathVariable String id) {
    return reviews.get(id);
  }

  @PostMapping("/student/grade-reviews")
  public Map<String, Object> request(@RequestBody Map<String, Object> body) {
    return reviews.request(body);
  }

  @PostMapping("/teacher/grade-reviews/{id}/recommend")
  public Map<String, Object> recommend(
      @PathVariable String id, @RequestBody Map<String, Object> body) {
    return reviews.recommend(id, body);
  }

  @PostMapping("/admin/grade-reviews/{id}/decide")
  public Map<String, Object> decide(
      @PathVariable String id, @RequestBody Map<String, Object> body) {
    return reviews.decide(id, body);
  }

  @PostMapping("/teacher/grade-reviews/{id}/publish")
  public Map<String, Object> publish(
      @PathVariable String id, @RequestBody Map<String, Object> body) {
    return reviews.publish(id, body);
  }

  @GetMapping("/admin/analytics/drill")
  public Api.Page drill(
      @RequestParam String termId,
      @RequestParam String metric,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    Actor.require("ADMIN");
    db.one("SELECT id FROM cs2_term WHERE id=?", termId);
    // Independent aggregates prevent a student's several meetings or former queue records from
    // multiplying totals.
    String measures =
        switch (metric) {
          case "CAPACITY" -> "s.enrolled_count AS numerator,s.capacity AS denominator";
          case "WAITLIST" ->
              """
(SELECT COUNT(*) FROM cs2_waitlist w JOIN cs2_round r ON r.id=w.round_id
 WHERE w.section_id=s.id AND w.state='WAITING' AND r.state='OPEN'
   AND r.starts_at<=CURRENT_TIMESTAMP AND CURRENT_TIMESTAMP<r.ends_at) AS numerator,s.capacity AS denominator
""";
          case "GRADES" ->
              """
(SELECT COUNT(*) FROM cs2_enrollment e WHERE e.section_id=s.id AND e.state='ENROLLED' AND e.published_grade<60) AS numerator,
(SELECT COUNT(*) FROM cs2_enrollment e WHERE e.section_id=s.id AND e.state='ENROLLED' AND e.published_grade IS NOT NULL) AS denominator
""";
          default -> throw Api.invalid("未知指标");
        };
    return db.page(
        "SELECT s.id AS section_id,c.title AS course_title,t.name AS teacher_name,"
            + measures
            + " FROM cs2_section s JOIN cs2_offering o ON o.id=s.offering_id JOIN cs2_course c ON"
            + " c.id=o.course_id JOIN cs2_teacher t ON t.id=s.teacher_id WHERE o.term_id=? ORDER BY"
            + " s.id",
        List.of(termId),
        page,
        size);
  }
}
