package edu.ustb.coursev2;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AcademicCurriculum {
  private final Db db;
  private final Journal journal;

  public AcademicCurriculum(Db db, Journal journal) {
    this.db = db;
    this.journal = journal;
  }

  static List<String> ids(Object raw, int min, int max, String field) {
    if (!(raw instanceof List<?> values) || values.size() < min || values.size() > max)
      throw Api.invalid(field + " 数量须为 " + min + "–" + max);
    Set<String> ids = new LinkedHashSet<>();
    for (Object value : values)
      if (!(value instanceof String s) || !s.matches("[A-Za-z0-9_-]{1,32}") || !ids.add(s))
        throw Api.invalid(field + " 含有重复或无效编号");
    return List.copyOf(ids);
  }

  public Api.Page catalog(int page, int size) {
    Actor.require("ADMIN");
    return db.page(
        "SELECT id,cohort,name,revision AS version,(SELECT COUNT(*) FROM cs3_curriculum_binding b"
            + " WHERE b.curriculum_id=p.id) AS student_count FROM cs3_curriculum p ORDER BY"
            + " cohort,revision DESC",
        List.of(),
        page,
        size);
  }

  public Api.Page cohortProgress(String id, int page, int size) {
    Actor.require("ADMIN");
    db.one("SELECT id FROM cs3_curriculum WHERE id=?", id);
    var result =
        db.page(
            """
SELECT s.id AS student_id,s.name AS student_name,s.department,
       SUM(x.required_credits) AS required_credits,
       SUM(CASE WHEN x.passed_credits>x.required_credits THEN x.required_credits ELSE x.passed_credits END) AS earned_credits
FROM (
  SELECT b.student_id,m.module_no,m.required_credits,COALESCE(SUM(t.credits),0) AS passed_credits
  FROM cs3_curriculum_binding b JOIN cs3_module m ON m.curriculum_id=b.curriculum_id
  JOIN cs3_module_course mc ON mc.curriculum_id=m.curriculum_id AND mc.module_no=m.module_no
  LEFT JOIN (SELECT student_id,course_id,MAX(credits) AS credits FROM cs2_v_transcript
             WHERE published_grade>=60 GROUP BY student_id,course_id) t
      ON t.student_id=b.student_id AND t.course_id=mc.course_id
  WHERE b.curriculum_id=? GROUP BY b.student_id,m.module_no,m.required_credits
) x JOIN cs2_student s ON s.id=x.student_id
GROUP BY s.id,s.name,s.department ORDER BY s.id
""",
            List.of(id),
            page,
            size);
    for (var row : result.items())
      row.put("remainingCredits", Db.num(row, "requiredCredits") - Db.num(row, "earnedCredits"));
    return result;
  }

  @Transactional
  public Map<String, Object> create(Map<String, Object> body) {
    Actor.require("ADMIN");
    String cohort = Api.text(body, "cohort", 80), name = Api.text(body, "name", 100);
    if (!(body.get("modules") instanceof List<?> modules)
        || modules.isEmpty()
        || modules.size() > 24) throw Api.invalid("培养方案须含 1–24 个模块");
    // Revision numbers are assigned under the same lock even when the cohort has no earlier
    // version.
    db.one("SELECT id FROM cs3_curriculum_gate WHERE id=1 FOR UPDATE");
    long version =
        1 + db.count("SELECT COALESCE(MAX(revision),0) FROM cs3_curriculum WHERE cohort=?", cohort);
    String id = Db.uid();
    db.update(
        "INSERT INTO cs3_curriculum(id,cohort,name,revision) VALUES(?,?,?,?)",
        id,
        cohort,
        name,
        version);
    Set<String> names = new HashSet<>(), seen = new HashSet<>();
    int number = 0;
    for (Object raw : modules) {
      if (!(raw instanceof Map<?, ?> fields)) throw Api.invalid("模块格式错误");
      Map<String, Object> module = new HashMap<>();
      fields.forEach((k, v) -> module.put(String.valueOf(k), v));
      String label = Api.text(module, "name", 100);
      int required = Api.integer(module, "requiredCredits", 1, 300);
      List<String> courses = ids(module.get("courseIds"), 1, 100, "课程");
      if (!names.add(label)) throw Api.invalid("模块名称须唯一");
      db.update(
          "INSERT INTO cs3_module(curriculum_id,module_no,name,required_credits) VALUES(?,?,?,?)",
          id,
          ++number,
          label,
          required);
      for (String course : courses) {
        if (!seen.add(course)) throw Api.invalid("同一门课程不能计入多个模块");
        db.one("SELECT id FROM cs2_course WHERE id=?", course);
        db.update(
            "INSERT INTO cs3_module_course(curriculum_id,module_no,course_id) VALUES(?,?,?)",
            id,
            number,
            course);
      }
    }
    journal.audit("CURRICULUM_PUBLISH", id, cohort + " 培养方案第 " + version + " 版", null);
    return Api.object("id", id, "name", name, "cohort", cohort, "version", version);
  }

  @Transactional
  public Map<String, Object> assign(String id, Map<String, Object> body) {
    Actor.require("ADMIN");
    db.one("SELECT id FROM cs3_curriculum WHERE id=?", id);
    List<String> students = ids(body.get("studentIds"), 1, 100, "学生");
    for (String student : new TreeSet<>(students)) {
      db.one("SELECT id FROM cs2_student WHERE id=? FOR UPDATE", student);
      if (db.count("SELECT COUNT(*) FROM cs3_curriculum_binding WHERE student_id=?", student) > 0)
        throw Api.conflict("CURRICULUM_PINNED", "学生已绑定培养方案版本，整批未更改");
      db.update(
          "INSERT INTO cs3_curriculum_binding(student_id,curriculum_id) VALUES(?,?)", student, id);
    }
    journal.audit("CURRICULUM_ASSIGN", id, "绑定培养方案学生 " + students.size() + " 人", null);
    return Api.object("id", id, "assigned", students.size());
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> progress() {
    Actor.require("STUDENT");
    String student = Actor.current().studentId();
    var plans =
        db.rows(
            "SELECT p.id,p.name,p.cohort,p.revision AS version FROM cs3_curriculum p "
                + "JOIN cs3_curriculum_binding b ON p.id=b.curriculum_id WHERE b.student_id=?",
            student);
    if (plans.isEmpty())
      return Api.object(
          "curriculum",
          null,
          "modules",
          List.of(),
          "earnedCredits",
          0,
          "requiredCredits",
          0,
          "remainingCredits",
          0);
    Object id = plans.get(0).get("id");
    // One passed course appears once even after retakes. The highest credit value of its passed
    // offerings counts.
    var courses =
        db.rows(
            """
SELECT mc.module_no,mc.course_id,c.title,COALESCE(p.credits,0) AS credits
FROM cs3_module_course mc JOIN cs2_course c ON c.id=mc.course_id
LEFT JOIN (SELECT course_id,MAX(credits) AS credits FROM cs2_v_transcript
           WHERE student_id=? AND published_grade>=60 GROUP BY course_id) p ON p.course_id=mc.course_id
WHERE mc.curriculum_id=? ORDER BY mc.module_no,mc.course_id
""",
            student,
            id);
    Map<Integer, List<Map<String, Object>>> grouped = new HashMap<>();
    for (var course : courses) {
      int module = Db.num(course, "moduleNo");
      course.remove("moduleNo");
      course.put("passed", Db.num(course, "credits") > 0);
      grouped.computeIfAbsent(module, k -> new ArrayList<>()).add(course);
    }
    var modules =
        db.rows(
            "SELECT module_no,name,required_credits FROM cs3_module WHERE curriculum_id=? ORDER BY"
                + " module_no",
            id);
    int earned = 0, required = 0;
    for (var m : modules) {
      int need = Db.num(m, "requiredCredits");
      var list = grouped.getOrDefault(Db.num(m, "moduleNo"), List.of());
      int passed = list.stream().mapToInt(c -> Db.num(c, "credits")).sum();
      int counted = Math.min(need, passed);
      m.put("courses", list);
      m.put("earnedCredits", counted);
      m.put("passedCredits", passed);
      m.put("remainingCredits", need - counted);
      earned += counted;
      required += need;
    }
    return Api.object(
        "curriculum",
        plans.get(0),
        "modules",
        modules,
        "earnedCredits",
        earned,
        "requiredCredits",
        required,
        "remainingCredits",
        required - earned);
  }
}
