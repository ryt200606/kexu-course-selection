package edu.ustb.coursev2;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AcademicReviews {
  private static final Set<String> STATES =
      Set.of("REQUESTED", "RECOMMENDED", "APPROVED", "REJECTED", "PUBLISHED", "STALE");
  private static final String REVIEW =
      """
SELECT r.id,r.enrollment_id,e.section_id,e.student_id,st.name AS student_name,c.title AS course_title,
       o.term_id,s.teacher_id,r.original_grade,r.proposed_grade,r.original_version,
       r.student_reason,r.teacher_reason,r.admin_reason,r.state,r.version,r.created_at,r.updated_at
FROM cs3_grade_review r JOIN cs2_enrollment e ON e.id=r.enrollment_id
JOIN cs2_section s ON s.id=e.section_id JOIN cs2_offering o ON o.id=e.offering_id
JOIN cs2_course c ON c.id=o.course_id JOIN cs2_student st ON st.id=e.student_id
""";
  private final Db db;
  private final Journal journal;

  AcademicReviews(Db db, Journal journal) {
    this.db = db;
    this.journal = journal;
  }

  private String scope(List<Object> args) {
    Actor.require("STUDENT", "TEACHER", "ADMIN");
    Actor a = Actor.current();
    if (a.role().equals("ADMIN")) return " WHERE 1=1";
    if (a.role().equals("STUDENT")) {
      args.add(a.studentId());
      return " WHERE e.student_id=?";
    }
    args.add(a.teacherId());
    return " WHERE s.teacher_id=?";
  }

  private static Map<String, Object> decorate(Map<String, Object> row) {
    var proposed = (BigDecimal) row.get("proposedGrade");
    var previous = (BigDecimal) row.get("originalGrade");
    var pass = BigDecimal.valueOf(60);
    row.put(
        "crossesPassThreshold",
        proposed != null && (previous.compareTo(pass) >= 0) != (proposed.compareTo(pass) >= 0));
    return row;
  }

  public Api.Page list(String state, String term, int page, int size) {
    var args = new ArrayList<Object>();
    String sql = REVIEW + scope(args);
    if (state != null && !state.isBlank()) {
      if (!STATES.contains(state)) throw Api.invalid("未知复核状态");
      sql += " AND r.state=?";
      args.add(state);
    }
    if (term != null && !term.isBlank()) {
      sql += " AND o.term_id=?";
      args.add(term);
    }
    var result = db.page(sql + " ORDER BY r.created_at DESC,r.id", args, page, size);
    result.items().forEach(AcademicReviews::decorate);
    return result;
  }

  public Map<String, Object> get(String id) {
    var args = new ArrayList<Object>();
    String sql = REVIEW + scope(args) + " AND r.id=?";
    args.add(id);
    var row = decorate(db.one(sql, args.toArray()));
    row.put(
        "history",
        db.rows(
            "SELECT action,actor_name,message,created_at FROM cs2_audit WHERE entity_id=? "
                + "AND action LIKE 'REVIEW_%' ORDER BY CASE action WHEN 'REVIEW_REQUEST' THEN 1 "
                + "WHEN 'REVIEW_RECOMMEND' THEN 2 WHEN 'REVIEW_APPROVE' THEN 3 "
                + "WHEN 'REVIEW_REJECT' THEN 3 ELSE 4 END, created_at,id",
            id));
    return row;
  }

  private BigDecimal score(Object value) {
    try {
      if (value != null) {
        BigDecimal n = new BigDecimal(value.toString());
        if (n.signum() >= 0 && n.compareTo(BigDecimal.valueOf(100)) <= 0 && n.scale() <= 2)
          return n;
      }
    } catch (NumberFormatException ignored) {
    }
    throw Api.invalid("建议成绩须为 0–100 且最多两位小数");
  }

  private Map<String, Object> lockEnrollment(String id) {
    // All grade writers acquire the section before the enrollment. Resolve the section once then
    // recheck under lock.
    var found = db.one("SELECT section_id FROM cs2_enrollment WHERE id=?", id);
    String section = Db.str(found, "sectionId");
    db.one("SELECT id FROM cs2_section WHERE id=? FOR UPDATE", section);
    db.one("SELECT id FROM cs2_enrollment WHERE id=? FOR UPDATE", id);
    var e =
        db.one(
            """
SELECT e.student_id,e.section_id,e.state,e.grade_version,e.published_grade,s.grade_state,s.teacher_id,o.term_id
FROM cs2_enrollment e JOIN cs2_section s ON s.id=e.section_id JOIN cs2_offering o ON o.id=e.offering_id
WHERE e.id=?
""",
            id);
    if (!section.equals(e.get("sectionId"))) throw Api.conflict("VERSION_CONFLICT", "选课记录已变更，请刷新");
    return e;
  }

  @Transactional
  public Map<String, Object> request(Map<String, Object> body) {
    Actor.require("STUDENT");
    String enrollment = Api.text(body, "enrollmentId", 36), reason = Api.text(body, "reason", 500);
    // Scope before acquiring a write lock on a class.
    db.one(
        "SELECT id FROM cs2_enrollment WHERE id=? AND student_id=?",
        enrollment,
        Actor.current().studentId());
    var e = lockEnrollment(enrollment);
    if (!"ENROLLED".equals(e.get("state")) || e.get("publishedGrade") == null)
      throw Api.conflict("GRADE_NOT_PUBLISHED", "只能复核本人已发布的成绩");
    var active =
        db.rows(
            "SELECT id,original_version,original_grade FROM cs3_grade_review WHERE enrollment_id=? "
                + "AND state IN ('REQUESTED','RECOMMENDED','APPROVED')",
            enrollment);
    for (var r : active) {
      if (matches(r, e)) throw Api.conflict("REVIEW_EXISTS", "该成绩已有正在处理的复核");
      db.update(
          "UPDATE cs3_grade_review SET state='STALE',version=version+1,updated_at=CURRENT_TIMESTAMP"
              + " WHERE id=?",
          r.get("id"));
      journal.audit("REVIEW_STALE", Db.str(r, "id"), "成绩来源已更新，旧申请归档", Db.str(e, "termId"));
    }
    String id = Db.uid();
    db.update(
        """
INSERT INTO cs3_grade_review(id,enrollment_id,original_grade,original_version,student_reason,state,created_at,updated_at)
VALUES(?,?,?,?,?,'REQUESTED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
""",
        id,
        enrollment,
        e.get("publishedGrade"),
        e.get("gradeVersion"),
        reason);
    journal.audit("REVIEW_REQUEST", id, "学生申请成绩复核：" + reason, Db.str(e, "termId"));
    return get(id);
  }

  private boolean matches(Map<String, Object> review, Map<String, Object> e) {
    return "ENROLLED".equals(e.get("state"))
        && e.get("publishedGrade") != null
        && Db.lng(review, "originalVersion") == Db.lng(e, "gradeVersion")
        && ((BigDecimal) review.get("originalGrade"))
                .compareTo((BigDecimal) e.get("publishedGrade"))
            == 0;
  }

  private record Locked(Map<String, Object> review, Map<String, Object> enrollment) {}

  private Locked lock(String id, Map<String, Object> body, String expected) {
    var first = get(id);
    var e = lockEnrollment(Db.str(first, "enrollmentId"));
    var r =
        db.one(
            "SELECT id,enrollment_id,state,version,original_version,original_grade,proposed_grade"
                + " FROM cs3_grade_review WHERE id=? FOR UPDATE",
            id);
    if (!expected.equals(r.get("state")) || Db.lng(r, "version") != Api.version(body))
      throw Api.conflict("VERSION_CONFLICT", "复核已由其他人处理，请刷新");
    if (Actor.current().role().equals("TEACHER")
        && !Actor.current().teacherId().equals(e.get("teacherId")))
      throw new Api.Failure(403, "FORBIDDEN", "仅限本班授课教师");
    return new Locked(r, e);
  }

  private void record(String id, String action, String note, Map<String, Object> e) {
    journal.audit(action, id, note, Db.str(e, "termId"));
    journal.notifyStudent(Db.str(e, "studentId"), "成绩复核进度", Db.str(e, "sectionId") + " · " + note);
  }

  @Transactional
  public Map<String, Object> recommend(String id, Map<String, Object> body) {
    Actor.require("TEACHER");
    String reason = Api.text(body, "reason", 500);
    BigDecimal proposed = score(body.get("proposedGrade"));
    var l = lock(id, body, "REQUESTED");
    if (!matches(l.review(), l.enrollment()))
      throw Api.conflict("GRADE_CHANGED", "成绩来源已更新，请学生按新成绩发起申请");
    db.update(
        "UPDATE cs3_grade_review SET"
            + " state='RECOMMENDED',proposed_grade=?,teacher_reason=?,version=version+1,updated_at=CURRENT_TIMESTAMP"
            + " WHERE id=?",
        proposed,
        reason,
        id);
    record(id, "REVIEW_RECOMMEND", "教师建议 " + proposed + " 分：" + reason, l.enrollment());
    return get(id);
  }

  @Transactional
  public Map<String, Object> decide(String id, Map<String, Object> body) {
    Actor.require("ADMIN");
    String reason = Api.text(body, "reason", 500);
    if (!(body.get("approve") instanceof Boolean approve)) throw Api.invalid("approve 必须为布尔值");
    var l = lock(id, body, "RECOMMENDED");
    if (approve && !matches(l.review(), l.enrollment()))
      throw Api.conflict("GRADE_CHANGED", "成绩来源已更新，不能核准旧申请");
    db.update(
        "UPDATE cs3_grade_review SET"
            + " state=?,admin_reason=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",
        approve ? "APPROVED" : "REJECTED",
        reason,
        id);
    record(
        id,
        approve ? "REVIEW_APPROVE" : "REVIEW_REJECT",
        approve ? "教务核准，等待任课教师重新发布：" + reason : "教务驳回：" + reason,
        l.enrollment());
    return get(id);
  }

  @Transactional
  public Map<String, Object> publish(String id, Map<String, Object> body) {
    Actor.require("TEACHER");
    var l = lock(id, body, "APPROVED");
    var r = l.review();
    var e = l.enrollment();
    if (!matches(r, e) || !"PUBLISHED".equals(e.get("gradeState")))
      throw Api.conflict("GRADE_CHANGED", "成绩已变更或有在途草稿，不能发布本次复核结果");
    BigDecimal before = (BigDecimal) r.get("originalGrade"),
        after = (BigDecimal) r.get("proposedGrade");
    db.update(
        "UPDATE cs2_enrollment SET"
            + " grade=?,published_grade=?,grade_version=grade_version+1,updated_at=CURRENT_TIMESTAMP"
            + " WHERE id=?",
        after,
        after,
        r.get("enrollmentId"));
    db.update(
        "UPDATE cs2_section SET"
            + " grade_revision=grade_revision+1,last_published_at=CURRENT_TIMESTAMP,version=version+1"
            + " WHERE id=?",
        e.get("sectionId"));
    db.update(
        "UPDATE cs3_grade_review SET"
            + " state='PUBLISHED',version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",
        id);
    record(id, "REVIEW_PUBLISH", "教师重新发布正式成绩：" + before + " → " + after, e);
    return get(id);
  }
}
