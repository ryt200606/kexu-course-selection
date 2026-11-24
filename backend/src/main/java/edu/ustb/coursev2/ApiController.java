package edu.ustb.coursev2;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2")
public class ApiController {
  private final Db db;
  private final QueryService query;
  private final EnrollmentService enroll;
  private final GradeService grades;
  private final AdminService admin;
  private final DatabaseConfig.Identity identity;

  public ApiController(
      Db db,
      QueryService query,
      EnrollmentService enroll,
      GradeService grades,
      AdminService admin,
      DatabaseConfig.Identity identity) {
    this.db = db;
    this.query = query;
    this.enroll = enroll;
    this.grades = grades;
    this.admin = admin;
    this.identity = identity;
  }

  @GetMapping("/health")
  public Map<String, Object> health() {
    Map<String, Object> schema =
        db.one("SELECT version,source_version,schema_sha256 FROM cs2_schema_version ORDER BY version DESC LIMIT 1");
    return Api.object(
        "status",
        "UP",
        "database",
        identity.database(),
        "serverVersion",
        identity.serverVersion(),
        "schemaVersion",
        schema.get("version"),
        "sourceVersion",
        schema.get("sourceVersion"),
        "schemaSha256",
        schema.get("schemaSha256"),
        "surrogate",
        identity.surrogate());
  }

  @GetMapping("/terms")
  public Api.Page terms(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return query.terms(page, size);
  }

  @GetMapping("/rounds")
  public Api.Page rounds(
      @RequestParam(required = false) String termId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return query.rounds(termId, page, size);
  }

  @GetMapping("/catalog")
  public Api.Page catalog(
      @RequestParam(required = false) String termId,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String department,
      @RequestParam(defaultValue = "false") boolean availableOnly,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return query.catalog(termId, q, department, availableOnly, page, size, null);
  }

  @GetMapping("/sections/{id}")
  public Map<String, Object> section(@PathVariable String id) {
    return query.section(id);
  }

  @GetMapping("/dashboard")
  public Map<String, Object> dashboard(@RequestParam(required = false) String termId) {
    return query.dashboard(termId);
  }

  @GetMapping("/notifications")
  public Api.Page notifications(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return query.notifications(page, size);
  }

  @PutMapping("/notifications/{id}/read")
  public Map<String, Object> read(@PathVariable String id) {
    if (db.update(
            "UPDATE cs2_notification SET read_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=?",
            id,
            Actor.current().id())
        == 0) throw Api.missing();
    return Map.of("status", "OK");
  }

  @GetMapping("/student/summary")
  public Map<String, Object> summary(@RequestParam(required = false) String termId) {
    return query.studentSummary(Actor.current().studentId(), termId);
  }

  @GetMapping("/student/enrollments")
  public Api.Page ownEnrollments(
      @RequestParam(required = false) String termId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return query.enrollments(termId, Actor.current().studentId(), null, null, page, size, true);
  }

  @GetMapping("/student/timetable")
  public Map<String, Object> timetable(@RequestParam(required = false) String termId) {
    return query.timetable(Actor.current().studentId(), termId);
  }

  @GetMapping("/student/waitlist")
  public Api.Page waitlist(
      @RequestParam(required = false) String termId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return query.waitlist(Actor.current().studentId(), termId, page, size);
  }

  @GetMapping("/student/sections/{id}/eligibility")
  public Map<String, Object> eligibility(@PathVariable String id) {
    return enroll.eligibility(Actor.current().studentId(), id);
  }

  @PostMapping("/student/enrollments")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> enroll(
      @RequestBody Map<String, Object> body,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return enroll.enroll(Api.id(body, "sectionId"), key);
  }

  @DeleteMapping("/student/enrollments/{id}")
  public Map<String, Object> withdraw(
      @PathVariable String id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return enroll.withdraw(id, key);
  }

  @PostMapping("/student/swaps")
  public Map<String, Object> swap(
      @RequestBody Map<String, Object> body,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return enroll.swap(Api.id(body, "sourceSectionId"), Api.id(body, "targetSectionId"), key);
  }

  @PostMapping("/student/waitlist")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> join(
      @RequestBody Map<String, Object> body,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return enroll.joinWaitlist(Api.id(body, "sectionId"), key);
  }

  @DeleteMapping("/student/waitlist/{id}")
  public Map<String, Object> cancel(
      @PathVariable String id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return enroll.cancelWaitlist(id, key);
  }

  @GetMapping("/teacher/sections")
  public Api.Page teacherSections(
      @RequestParam(required = false) String termId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    Actor a = Actor.current();
    return query.catalog(
        termId, null, null, false, page, size, a.role().equals("TEACHER") ? a.teacherId() : null);
  }

  @GetMapping("/teacher/sections/{id}/grades")
  public Map<String, Object> gradeSheet(@PathVariable String id) {
    return grades.sheet(id);
  }

  @PutMapping("/teacher/sections/{id}/grades")
  public Map<String, Object> saveGrades(
      @PathVariable String id, @RequestBody Map<String, Object> body) {
    return grades.save(id, body);
  }

  @PostMapping("/teacher/sections/{id}/publish-grades")
  public Map<String, Object> publishGrades(
      @PathVariable String id, @RequestBody Map<String, Object> body) {
    return grades.publish(id, body);
  }

  @PostMapping("/admin/sections/{id}/reopen-grades")
  public Map<String, Object> reopen(
      @PathVariable String id, @RequestBody Map<String, Object> body) {
    return grades.reopen(id, body);
  }

  @GetMapping("/admin/enrollments")
  public Api.Page adminEnrollments(
      @RequestParam(required = false) String termId,
      @RequestParam(required = false) String studentId,
      @RequestParam(required = false) String sectionId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return query.enrollments(termId, studentId, sectionId, null, page, size, false);
  }

  @GetMapping("/admin/audit")
  public Api.Page audit(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String termId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return admin.audit(q, termId, page, size);
  }

  @GetMapping("/admin/consistency")
  public Map<String, Object> consistency(@RequestParam(required = false) String termId) {
    return query.consistency(termId);
  }

  @PostMapping("/admin/promotions/run")
  public Map<String, Object> promotion(@RequestBody Map<String, Object> body) {
    return enroll.promote(Api.id(body, "sectionId"));
  }

  @GetMapping("/admin/{resource}")
  public Api.Page adminList(
      @PathVariable String resource,
      @RequestParam(required = false) String termId,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String state,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return admin.list(resource, termId, q, state, page, size);
  }

  @GetMapping("/admin/{resource}/{id}")
  public Map<String, Object> adminDetail(@PathVariable String resource, @PathVariable String id) {
    return admin.detail(resource, id);
  }

  @PostMapping("/admin/{resource}")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> create(
      @PathVariable String resource, @RequestBody Map<String, Object> body) {
    return admin.save(resource, null, body);
  }

  @PutMapping("/admin/{resource}/{id}")
  public Map<String, Object> update(
      @PathVariable String resource,
      @PathVariable String id,
      @RequestBody Map<String, Object> body) {
    return admin.save(resource, id, body);
  }

  @DeleteMapping("/admin/{resource}/{id}")
  public Map<String, Object> delete(@PathVariable String resource, @PathVariable String id) {
    return admin.delete(resource, id);
  }
}
