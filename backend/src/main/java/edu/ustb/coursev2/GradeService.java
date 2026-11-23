package edu.ustb.coursev2;

import static edu.ustb.coursev2.Db.*;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GradeService {
  private final Db db;
  private final Journal journal;

  public GradeService(Db db, Journal journal) {
    this.db = db;
    this.journal = journal;
  }

  private Map<String, Object> access(String section, boolean lock) {
    Actor.require("TEACHER", "ADMIN");
    if (lock) db.one("SELECT id FROM cs2_section WHERE id=? FOR UPDATE", section);
    Map<String, Object> s =
        db.one(
            "SELECT s.id,s.teacher_id,s.grade_state,s.grade_revision,s.version,o.term_id FROM"
                + " cs2_section s JOIN cs2_offering o ON o.id=s.offering_id WHERE s.id=?",
            section);
    Actor a = Actor.current();
    if (a.role().equals("TEACHER") && !a.teacherId().equals(s.get("teacherId")))
      throw new Api.Failure(403, "FORBIDDEN", "只能管理本人教学班成绩");
    return s;
  }

  public Map<String, Object> sheet(String section) {
    Map<String, Object> s = access(section, false);
    List<Map<String, Object>> rows =
        db.rows(
            "SELECT e.id AS enrollment_id,e.student_id,st.name AS"
                + " student_name,e.grade,e.published_grade,e.grade_version AS version FROM"
                + " cs2_enrollment e JOIN cs2_student st ON st.id=e.student_id WHERE e.section_id=?"
                + " AND e.state='ENROLLED' ORDER BY e.student_id",
            section);
    return Api.object(
        "sectionId",
        section,
        "gradeState",
        s.get("gradeState"),
        "gradeRevision",
        s.get("gradeRevision"),
        "version",
        s.get("version"),
        "items",
        rows);
  }

  @Transactional(timeout = 10)
  public Map<String, Object> save(String section, Map<String, Object> body) {
    Map<String, Object> s = access(section, true);
    if (str(s, "gradeState").equals("PUBLISHED"))
      throw Api.conflict("GRADES_PUBLISHED", "已发布成绩须由教务显式重开后更正");
    Object raw = body.get("items");
    if (!(raw instanceof List<?> items) || items.isEmpty() || items.size() > 1000)
      throw Api.invalid("成绩批次须为1—1000条");
    Set<String> ids = new HashSet<>();
    for (Object item : items) {
      if (!(item instanceof Map<?, ?> m)) throw Api.invalid("成绩条目格式不正确");
      Object id = m.get("enrollmentId"), v = m.get("version");
      if (!(id instanceof String) || !(v instanceof Number) || !ids.add(id.toString()))
        throw Api.invalid("成绩条目ID/版本或重复项不正确");
      BigDecimal grade = null;
      if (m.get("grade") != null) {
        try {
          grade = new BigDecimal(m.get("grade").toString());
        } catch (NumberFormatException e) {
          throw Api.invalid("成绩格式不正确");
        }
        if (grade.scale() > 2
            || grade.compareTo(BigDecimal.ZERO) < 0
            || grade.compareTo(new BigDecimal("100")) > 0) throw Api.invalid("成绩须为0—100且最多两位小数");
      }
      int changed =
          db.update(
              "UPDATE cs2_enrollment SET"
                  + " grade=?,grade_version=grade_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?"
                  + " AND section_id=? AND state='ENROLLED' AND grade_version=?",
              grade,
              id,
              section,
              Api.version(Map.of("version", v)));
      if (changed == 0) throw Api.conflict("VERSION_CONFLICT", "成绩已更新或学生不属于本班，整批未保存");
    }
    db.update("UPDATE cs2_section SET version=version+1 WHERE id=?", section);
    journal.audit("GRADES_DRAFT", section, "批量保存" + items.size() + "条成绩草稿", str(s, "termId"));
    return sheet(section);
  }

  @Transactional(timeout = 10)
  public Map<String, Object> publish(String section, Map<String, Object> body) {
    Map<String, Object> s = access(section, true);
    if (lng(s, "version") != Api.version(body))
      throw Api.conflict("VERSION_CONFLICT", "教学班已变化，请刷新后发布");
    if (str(s, "gradeState").equals("PUBLISHED"))
      throw Api.conflict("GRADES_PUBLISHED", "当前成绩已经发布");
    if (db.count(
            "SELECT COUNT(*) FROM cs2_enrollment WHERE section_id=? AND state='ENROLLED'", section)
        == 0) throw Api.conflict("GRADE_INCOMPLETE", "教学班没有在读学生");
    if (db.count(
            "SELECT COUNT(*) FROM cs2_enrollment WHERE section_id=? AND state='ENROLLED' AND grade"
                + " IS NULL",
            section)
        > 0) throw Api.conflict("GRADE_INCOMPLETE", "仍有学生成绩未录入，不能发布");
    db.update(
        "UPDATE cs2_enrollment SET published_grade=grade,updated_at=CURRENT_TIMESTAMP WHERE"
            + " section_id=? AND state='ENROLLED'",
        section);
    db.update(
        "UPDATE cs2_section SET"
            + " grade_state='PUBLISHED',grade_revision=grade_revision+1,last_published_at=CURRENT_TIMESTAMP,version=version+1"
            + " WHERE id=?",
        section);
    for (Map<String, Object> row :
        db.rows(
            "SELECT student_id FROM cs2_enrollment WHERE section_id=? AND state='ENROLLED' ORDER BY"
                + " student_id",
            section))
      journal.notifyStudent(str(row, "studentId"), "成绩已发布", section + "成绩已正式发布，可在成绩单查看");
    journal.audit(
        "GRADES_PUBLISH", section, "发布成绩修订" + (num(s, "gradeRevision") + 1), str(s, "termId"));
    return sheet(section);
  }

  @Transactional(timeout = 10)
  public Map<String, Object> reopen(String section, Map<String, Object> body) {
    Actor.require("ADMIN");
    Map<String, Object> s = access(section, true);
    String reason = Api.text(body, "reason", 500);
    if (lng(s, "version") != Api.version(body))
      throw Api.conflict("VERSION_CONFLICT", "教学班已变化，请刷新");
    if (!str(s, "gradeState").equals("PUBLISHED"))
      throw Api.conflict("GRADE_NOT_PUBLISHED", "当前不是已发布状态");
    db.update("UPDATE cs2_section SET grade_state='DRAFT',version=version+1 WHERE id=?", section);
    journal.audit("GRADES_REOPEN", section, "更正原因：" + reason + "；学生继续看到上一版正式成绩", str(s, "termId"));
    return sheet(section);
  }
}
