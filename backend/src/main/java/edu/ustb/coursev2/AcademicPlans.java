package edu.ustb.coursev2;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AcademicPlans {
  private final Db db;
  private final EnrollmentService enrollment;
  private final QueryService queries;

  AcademicPlans(Db db, EnrollmentService enrollment, QueryService queries) {
    this.db = db;
    this.enrollment = enrollment;
    this.queries = queries;
  }

  private void term(String term) {
    db.one("SELECT id FROM cs2_term WHERE id=?", term);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> saved(String term) {
    Actor.require("STUDENT");
    term(term);
    String student = Actor.current().studentId();
    long version =
        db.count(
            "SELECT COALESCE(MAX(revision),0) FROM cs3_plan WHERE term_id=? AND student_id=?",
            term,
            student);
    List<String> a = new ArrayList<>(), b = new ArrayList<>();
    for (var row :
        db.rows(
            "SELECT side,section_id FROM cs3_plan_section WHERE term_id=? AND student_id=? ORDER BY"
                + " side,position_no",
            term,
            student)) ("A".equals(row.get("side")) ? a : b).add(Db.str(row, "sectionId"));
    return Api.object("termId", term, "left", a, "right", b, "version", version);
  }

  private Map<String, Object> visible(String id, String term) {
    var section = queries.section(id);
    if (!term.equals(section.get("termId"))
        || !"PUBLISHED".equals(section.get("state"))
        || db.count(
                "SELECT COUNT(*) FROM cs2_offering WHERE id=? AND state='PUBLISHED'",
                section.get("offeringId"))
            != 1) throw Api.invalid("只能选择本学期已发布的教学班");
    return section;
  }

  @Transactional
  public Map<String, Object> save(String term, Map<String, Object> body) {
    Actor.require("STUDENT");
    String student = Actor.current().studentId();
    term(term);
    List<String> left = AcademicCurriculum.ids(body.get("left"), 0, 20, "方案 A"),
        right = AcademicCurriculum.ids(body.get("right"), 0, 20, "方案 B");
    db.one("SELECT id FROM cs2_student WHERE id=? FOR UPDATE", student);
    long previous =
        db.count(
            "SELECT COALESCE(MAX(revision),0) FROM cs3_plan WHERE student_id=? AND term_id=?",
            student,
            term);
    if (previous != Api.version(body)) throw Api.conflict("VERSION_CONFLICT", "方案已在其他页面更新，请刷新");
    Set<String> all = new LinkedHashSet<>(left);
    all.addAll(right);
    for (String id : all) visible(id, term);
    if (previous == 0)
      db.update("INSERT INTO cs3_plan(student_id,term_id,revision) VALUES(?,?,1)", student, term);
    else {
      db.update(
          "UPDATE cs3_plan SET revision=revision+1 WHERE student_id=? AND term_id=?",
          student,
          term);
      db.update("DELETE FROM cs3_plan_section WHERE student_id=? AND term_id=?", student, term);
    }
    for (var side : List.of(Map.entry("A", left), Map.entry("B", right))) {
      int n = 0;
      for (String id : side.getValue())
        db.update(
            "INSERT INTO cs3_plan_section(student_id,term_id,side,position_no,section_id)"
                + " VALUES(?,?,?,?,?)",
            student,
            term,
            side.getKey(),
            ++n,
            id);
    }
    return Api.object("termId", term, "left", left, "right", right, "version", previous + 1);
  }

  private static boolean overlaps(Map<String, Object> a, Map<String, Object> b) {
    return Db.num(a, "weekday") == Db.num(b, "weekday")
        && Db.num(a, "startPeriod") <= Db.num(b, "endPeriod")
        && Db.num(b, "startPeriod") <= Db.num(a, "endPeriod")
        && Db.num(a, "startWeek") <= Db.num(b, "endWeek")
        && Db.num(b, "startWeek") <= Db.num(a, "endWeek");
  }

  private record Candidate(
      String id,
      String course,
      String title,
      String teacher,
      int credits,
      List<Map<String, Object>> meetings,
      List<String> reasons) {}

  @SuppressWarnings("unchecked")
  private Candidate candidate(
      String student, String term, String id, Map<String, Candidate> cache) {
    return cache.computeIfAbsent(
        id,
        key -> {
          var section = visible(key, term);
          var checks =
              (List<Map<String, Object>>) enrollment.eligibility(student, key).get("reasons");
          return new Candidate(
              key,
              Db.str(section, "courseId"),
              Db.str(section, "courseTitle"),
              Db.str(section, "teacherName"),
              Db.num(section, "credits"),
              (List<Map<String, Object>>) section.get("meetings"),
              checks.stream().map(row -> Db.str(row, "message")).toList());
        });
  }

  private List<String> reasons(Candidate target, List<Candidate> peers, int selected, int limit) {
    Set<String> reasons = new LinkedHashSet<>(target.reasons());
    int total = selected + target.credits();
    for (Candidate p : peers) {
      total += p.credits();
      if (target.course().equals(p.course())) reasons.add("方案内重复选择同一课程：" + p.id());
      if (target.meetings().stream()
          .anyMatch(a -> p.meetings().stream().anyMatch(b -> overlaps(a, b))))
        reasons.add("方案内时段冲突：" + p.id());
    }
    if (total > limit) reasons.add("整套方案与已选学分合计超过学期上限 " + limit);
    return List.copyOf(reasons);
  }

  private Map<String, Object> evaluate(
      String student,
      String term,
      List<String> ids,
      Map<String, Candidate> cache,
      int selected,
      int limit) {
    var choices = ids.stream().map(id -> candidate(student, term, id, cache)).toList();
    List<Map<String, Object>> items = new ArrayList<>();
    boolean feasible = true;
    for (Candidate c : choices) {
      var peers = choices.stream().filter(p -> !p.id().equals(c.id())).toList();
      var failures = reasons(c, peers, selected, limit);
      List<Map<String, Object>> alternatives = new ArrayList<>();
      if (!failures.isEmpty()) {
        feasible = false;
        var alternativesForCourse =
            db.rows(
                """
SELECT s.id FROM cs2_section s JOIN cs2_offering o ON o.id=s.offering_id
WHERE o.term_id=? AND o.course_id=? AND o.state='PUBLISHED' AND s.state='PUBLISHED' AND s.id<>?
ORDER BY s.id
""",
                term,
                c.course(),
                c.id());
        for (var row : alternativesForCourse) {
          Candidate alt = candidate(student, term, Db.str(row, "id"), cache);
          if (reasons(alt, peers, selected, limit).isEmpty()) {
            alternatives.add(Api.object("sectionId", alt.id(), "teacherName", alt.teacher()));
            if (alternatives.size() == 3) break;
          }
        }
      }
      items.add(
          Api.object(
              "sectionId",
              c.id(),
              "courseTitle",
              c.title(),
              "eligible",
              failures.isEmpty(),
              "credits",
              c.credits(),
              "reasons",
              failures,
              "alternatives",
              alternatives));
    }
    int credits = choices.stream().mapToInt(Candidate::credits).sum();
    return Api.object(
        "credits",
        credits,
        "projectedCredits",
        selected + credits,
        "creditLimit",
        limit,
        "feasible",
        feasible,
        "items",
        items);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> compare(Map<String, Object> body) {
    Actor.require("STUDENT");
    String student = Actor.current().studentId(), term = Api.id(body, "termId");
    term(term);
    var left = AcademicCurriculum.ids(body.get("left"), 0, 20, "方案 A");
    var right = AcademicCurriculum.ids(body.get("right"), 0, 20, "方案 B");
    var summary = queries.studentTerm(student, term);
    int selected = Db.num(summary, "selectedCredits"), limit = Db.num(summary, "creditLimit");
    Map<String, Candidate> cache = new HashMap<>();
    return Api.object(
        "termId",
        term,
        "left",
        evaluate(student, term, left, cache, selected, limit),
        "right",
        evaluate(student, term, right, cache, selected, limit));
  }
}
