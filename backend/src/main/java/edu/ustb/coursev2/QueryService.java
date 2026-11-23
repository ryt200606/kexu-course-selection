package edu.ustb.coursev2;

import static edu.ustb.coursev2.Db.*;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class QueryService {
  public static final String SECTIONS =
      "SELECT"
          + " s.id,s.offering_id,s.teacher_id,s.capacity,s.enrolled_count,s.state,s.grade_state,s.grade_revision,s.last_published_at,s.promotion_pending,s.version,o.course_id,o.term_id,o.credits,c.title"
          + " AS course_title,c.department,c.description,t.name AS teacher_name,tm.name AS"
          + " term_name FROM cs2_section s JOIN cs2_offering o ON o.id=s.offering_id JOIN"
          + " cs2_course c ON c.id=o.course_id JOIN cs2_teacher t ON t.id=s.teacher_id JOIN"
          + " cs2_term tm ON tm.id=o.term_id";
  public static final String ENROLLMENTS =
      "SELECT e.id,e.student_id,st.name AS student_name,e.section_id,o.course_id,c.title AS"
          + " course_title,o.term_id,tm.name AS term_name,o.credits,s.teacher_id,t.name AS"
          + " teacher_name,e.state,e.grade,e.published_grade,e.grade_version,s.grade_state,e.created_at"
          + " FROM cs2_enrollment e JOIN cs2_student st ON st.id=e.student_id JOIN cs2_section s ON"
          + " s.id=e.section_id JOIN cs2_offering o ON o.id=e.offering_id JOIN cs2_course c ON"
          + " c.id=o.course_id JOIN cs2_teacher t ON t.id=s.teacher_id JOIN cs2_term tm ON"
          + " tm.id=o.term_id";
  private final Db db;

  public QueryService(Db db) {
    this.db = db;
  }

  public String term(String value) {
    if (value != null && !value.isBlank()) {
      db.one("SELECT id FROM cs2_term WHERE id=?", value);
      return value;
    }
    List<Map<String, Object>> ts =
        db.rows(
            "SELECT id FROM cs2_term ORDER BY CASE WHEN state='ACTIVE' THEN 0 ELSE 1 END,id DESC");
    if (ts.isEmpty()) throw Api.missing();
    return str(ts.get(0), "id");
  }

  public Api.Page terms(int page, int size) {
    return db.page(
        "SELECT id,name,state,version FROM cs2_term ORDER BY id DESC", List.of(), page, size);
  }

  public Api.Page rounds(String term, int page, int size) {
    return db.page(
        "SELECT id,term_id,name,state,starts_at,ends_at,withdrawal_ends_at,credit_limit,version"
            + " FROM cs2_round WHERE term_id=? ORDER BY starts_at DESC",
        List.of(term(term)),
        page,
        size);
  }

  public Api.Page catalog(
      String term,
      String query,
      String department,
      boolean available,
      int page,
      int size,
      String teacher) {
    StringBuilder sql =
        new StringBuilder(
            SECTIONS + " WHERE o.term_id=? AND s.state='PUBLISHED' AND o.state='PUBLISHED'");
    List<Object> args = new ArrayList<>(List.of(term(term)));
    if (query != null && !query.isBlank()) {
      sql.append(
          " AND (LOWER(c.title) LIKE ? OR LOWER(s.id) LIKE ? OR LOWER(t.name) LIKE ? OR"
              + " LOWER(o.course_id) LIKE ?)");
      String q = "%" + query.toLowerCase(Locale.ROOT) + "%";
      args.add(q);
      args.add(q);
      args.add(q);
      args.add(q);
    }
    if (department != null && !department.isBlank()) {
      sql.append(" AND c.department=?");
      args.add(department);
    }
    if (available) sql.append(" AND s.enrolled_count<s.capacity");
    if (teacher != null) {
      sql.append(" AND s.teacher_id=?");
      args.add(teacher);
    }
    sql.append(" ORDER BY c.title,s.id");
    Api.Page result = db.page(sql.toString(), args, page, size);
    decorateSections(result.items());
    return result;
  }

  public Map<String, Object> section(String id) {
    Map<String, Object> s = db.one(SECTIONS + " WHERE s.id=?", id);
    decorateSections(List.of(s));
    return s;
  }

  public List<Map<String, Object>> meetings(String section) {
    return db.rows(
        "SELECT weekday,start_period,end_period,start_week,end_week,room FROM cs2_meeting WHERE"
            + " section_id=? ORDER BY weekday,start_period",
        section);
  }

  public void decorateSections(List<Map<String, Object>> sections) {
    if (sections.isEmpty()) return;
    List<Object> sectionIds = sections.stream().map(row -> row.get("id")).toList();
    List<Object> offeringIds =
        new ArrayList<>(
            new LinkedHashSet<>(sections.stream().map(row -> row.get("offeringId")).toList()));
    String sectionPlaceholders = String.join(",", Collections.nCopies(sectionIds.size(), "?"));
    String offeringPlaceholders = String.join(",", Collections.nCopies(offeringIds.size(), "?"));
    Map<String, List<Map<String, Object>>> meetings = new HashMap<>(),
        prerequisites = new HashMap<>();
    for (Map<String, Object> m :
        db.rows(
            "SELECT section_id,weekday,start_period,end_period,start_week,end_week,room FROM"
                + " cs2_meeting WHERE section_id IN ("
                + sectionPlaceholders
                + ") ORDER BY weekday,start_period",
            sectionIds.toArray())) {
      String sid = str(m, "sectionId");
      m.remove("sectionId");
      meetings.computeIfAbsent(sid, x -> new ArrayList<>()).add(m);
    }
    for (Map<String, Object> p :
        db.rows(
            "SELECT p.offering_id,p.course_id,c.title AS course_title,p.min_grade FROM"
                + " cs2_prerequisite p JOIN cs2_course c ON c.id=p.course_id WHERE p.offering_id IN"
                + " ("
                + offeringPlaceholders
                + ") ORDER BY p.course_id",
            offeringIds.toArray())) {
      String oid = str(p, "offeringId");
      p.remove("offeringId");
      prerequisites.computeIfAbsent(oid, x -> new ArrayList<>()).add(p);
    }
    Map<String, Long> counts = new HashMap<>();
    for (Map<String, Object> c :
        db.rows(
            "SELECT w.section_id,COUNT(*) AS waiting_count FROM cs2_waitlist w JOIN cs2_round r ON"
                + " r.id=w.round_id WHERE w.section_id IN ("
                + sectionPlaceholders
                + ") AND w.state='WAITING' AND r.state='OPEN' AND r.ends_at>CURRENT_TIMESTAMP GROUP"
                + " BY w.section_id",
            sectionIds.toArray())) counts.put(str(c, "sectionId"), lng(c, "waitingCount"));
    for (Map<String, Object> row : sections) {
      String id = str(row, "id");
      row.put("meetings", meetings.getOrDefault(id, List.of()));
      row.put("prerequisites", prerequisites.getOrDefault(str(row, "offeringId"), List.of()));
      row.put("waitingCount", counts.getOrDefault(id, 0L));
      row.put("promotionPending", num(row, "promotionPending") == 1);
    }
  }

  public Api.Page enrollments(
      String term,
      String student,
      String section,
      String teacher,
      int page,
      int size,
      boolean hideDraft) {
    StringBuilder sql =
        new StringBuilder(ENROLLMENTS + " WHERE o.term_id=? AND e.state='ENROLLED'");
    List<Object> a = new ArrayList<>(List.of(term(term)));
    if (student != null) {
      sql.append(" AND e.student_id=?");
      a.add(student);
    }
    if (section != null && !section.isBlank()) {
      sql.append(" AND e.section_id=?");
      a.add(section);
    }
    if (teacher != null) {
      sql.append(" AND s.teacher_id=?");
      a.add(teacher);
    }
    sql.append(" ORDER BY e.student_id,c.title");
    Api.Page result = db.page(sql.toString(), a, page, size);
    for (Map<String, Object> row : result.items()) {
      if (hideDraft) row.put("grade", row.get("publishedGrade"));
      row.put("meetings", meetings(str(row, "sectionId")));
    }
    return result;
  }

  public Map<String, Object> enrollment(String student, String section) {
    Map<String, Object> e =
        db.one(
            ENROLLMENTS + " WHERE e.student_id=? AND e.section_id=? AND e.state='ENROLLED'",
            student,
            section);
    e.put("grade", e.get("publishedGrade"));
    return e;
  }

  public Map<String, Object> studentSummary(String student, String term) {
    String t = term(term);
    Map<String, Object> s =
        db.one("SELECT id AS student_id,name,department FROM cs2_student WHERE id=?", student);
    Map<String, Object> totals = studentTerm(student, t);
    s.putAll(totals);
    s.put("termId", t);
    s.put(
        "earnedCredits",
        db.count(
            "SELECT COALESCE(SUM(passed.credits),0) FROM (SELECT course_id,MAX(credits) AS"
                + " credits FROM cs2_v_transcript WHERE student_id=? AND published_grade>=60"
                + " GROUP BY course_id) passed",
            student));
    s.put(
        "waitingCount",
        db.count(
            "SELECT COUNT(*) FROM cs2_waitlist w JOIN cs2_round r ON r.id=w.round_id WHERE"
                + " w.student_id=? AND r.term_id=? AND w.state='WAITING' AND r.state='OPEN' AND"
                + " r.ends_at>CURRENT_TIMESTAMP",
            student,
            t));
    return s;
  }

  public Map<String, Object> studentTerm(String student, String term) {
    List<Map<String, Object>> st =
        db.rows(
            "SELECT selected_credits,credit_limit FROM cs2_student_term WHERE student_id=? AND"
                + " term_id=?",
            student,
            term);
    if (!st.isEmpty()) return st.get(0);
    return Api.object("selectedCredits", 0, "creditLimit", defaultLimit(term));
  }

  public int defaultLimit(String term) {
    List<Map<String, Object>> r =
        db.rows("SELECT credit_limit FROM cs2_round WHERE term_id=? ORDER BY starts_at DESC", term);
    return r.isEmpty() ? 24 : num(r.get(0), "creditLimit");
  }

  public Map<String, Object> timetable(String student, String term) {
    return Api.object(
        "termId",
        term(term),
        "items",
        db.rows(
            "SELECT section_id,course_title,teacher_name,room,weekday,start_period,end_period,"
                + "start_week,end_week FROM cs2_v_student_timetable WHERE student_id=? AND"
                + " term_id=? ORDER BY weekday,start_period",
            student,
            term(term)));
  }

  public Api.Page waitlist(String student, String term, int page, int size) {
    Api.Page result =
        db.page(
            "SELECT w.id,w.section_id,c.title AS"
                + " course_title,w.round_id,w.queue_no,w.state,w.reason,w.created_at,r.state AS"
                + " round_state,r.ends_at FROM cs2_waitlist w JOIN cs2_section s ON"
                + " s.id=w.section_id JOIN cs2_offering o ON o.id=s.offering_id JOIN cs2_course c"
                + " ON c.id=o.course_id JOIN cs2_round r ON r.id=w.round_id WHERE w.student_id=?"
                + " AND o.term_id=? ORDER BY w.created_at DESC,w.queue_no",
            List.of(student, term(term)),
            page,
            size);
    for (Map<String, Object> w : result.items()) {
      if (str(w, "state").equals("WAITING")
          && (!str(w, "roundState").equals("OPEN")
              || java.time.Instant.parse(str(w, "endsAt")).isBefore(db.now())))
        w.put("state", "EXPIRED");
      long position =
          str(w, "state").equals("WAITING")
              ? db.count(
                  "SELECT COUNT(*) FROM cs2_waitlist WHERE section_id=? AND round_id=? AND"
                      + " state='WAITING' AND queue_no<=?",
                  w.get("sectionId"),
                  w.get("roundId"),
                  w.get("queueNo"))
              : 0;
      w.put("position", position);
      w.remove("roundState");
      w.remove("endsAt");
    }
    return result;
  }

  public Api.Page notifications(int page, int size) {
    return db.page(
        "SELECT id,title,message,created_at,read_at FROM cs2_notification WHERE user_id=? ORDER BY"
            + " created_at DESC,id",
        List.of(Actor.current().id()),
        page,
        size);
  }

  @Transactional(
      readOnly = true,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public Map<String, Object> dashboard(String term) {
    Actor a = Actor.current();
    String t = term(term);
    String filter = "", teacherFilter = "";
    List<Object> args = new ArrayList<>(List.of(t));
    if (a.role().equals("STUDENT")) {
      filter = " AND e.student_id=?";
      args.add(a.studentId());
    }
    if (a.role().equals("TEACHER")) {
      filter = " AND s.teacher_id=?";
      args.add(a.teacherId());
      teacherFilter = " AND teacher_id=?";
    }
    String join =
        " FROM cs2_enrollment e JOIN cs2_section s ON s.id=e.section_id JOIN cs2_offering o ON"
            + " o.id=e.offering_id WHERE o.term_id=? AND e.state='ENROLLED'"
            + filter;
    Map<String, Object> out =
        Api.object(
            "students",
            db.count("SELECT COUNT(DISTINCT e.student_id)" + join, args.toArray()),
            "enrollments",
            db.count("SELECT COUNT(*)" + join, args.toArray()));
    List<Object> sa = new ArrayList<>(List.of(t));
    String sf = "";
    if (a.role().equals("TEACHER")) {
      sf = " AND s.teacher_id=?";
      sa.add(a.teacherId());
    }
    Map<String, Object> termSummary = null;
    if (a.role().equals("ADMIN")) {
      termSummary = db.one("SELECT * FROM cs2_v_term_summary WHERE term_id=?", t);
      out.put("students", db.count("SELECT COUNT(*) FROM cs2_student"));
      out.put("enrollments", termSummary.get("enrollmentCount"));
      out.put("courses", termSummary.get("courseCount"));
      out.put("sections", termSummary.get("sectionCount"));
      out.put("totalCapacity", termSummary.get("totalCapacity"));
    } else {
      out.put(
          "courses",
          db.count(
              "SELECT COUNT(DISTINCT o.course_id) FROM cs2_section s JOIN cs2_offering o ON"
                  + " o.id=s.offering_id WHERE o.term_id=?"
                  + sf,
              sa.toArray()));
      out.put(
          "sections",
          db.count(
              "SELECT COUNT(*) FROM cs2_section s JOIN cs2_offering o ON o.id=s.offering_id WHERE"
                  + " o.term_id=?"
                  + sf,
              sa.toArray()));
    }
    Map<String, Object> averages =
        db.one(
            "SELECT AVG(e.published_grade) AS average_grade,AVG(CASE WHEN e.published_grade IS NOT"
                + " NULL THEN CASE WHEN e.published_grade>=60 THEN 100.0 ELSE 0.0 END ELSE NULL"
                + " END) AS pass_rate"
                + join,
            args.toArray());
    out.putAll(averages);
    out.put(
        "gradeDistribution",
        db.rows(
            "SELECT CASE WHEN e.published_grade<60 THEN '不及格' WHEN e.published_grade<70 THEN"
                + " '60—69' WHEN e.published_grade<80 THEN '70—79' WHEN e.published_grade<90 THEN"
                + " '80—89' ELSE '90—100' END AS label,COUNT(*) AS count"
                + join
                + " AND e.published_grade IS NOT NULL GROUP BY CASE WHEN e.published_grade<60 THEN"
                + " '不及格' WHEN e.published_grade<70 THEN '60—69' WHEN e.published_grade<80 THEN"
                + " '70—79' WHEN e.published_grade<90 THEN '80—89' ELSE '90—100' END",
            args.toArray()));
    out.put(
        "capacity",
        db.rows(
            "SELECT s.id AS section_id,c.title,s.enrolled_count,s.capacity FROM cs2_section s JOIN"
                + " cs2_offering o ON o.id=s.offering_id JOIN cs2_course c ON c.id=o.course_id"
                + " WHERE o.term_id=?"
                + sf
                + " ORDER BY s.enrolled_count DESC,s.id",
            sa.toArray()));
    String wf = "";
    List<Object> wa = new ArrayList<>(List.of(t));
    if (a.role().equals("STUDENT")) {
      wf = " AND w.student_id=?";
      wa.add(a.studentId());
    }
    if (a.role().equals("TEACHER")) {
      wf = " AND s.teacher_id=?";
      wa.add(a.teacherId());
    }
    out.put(
        "waiting",
        db.count(
            "SELECT COUNT(*) FROM cs2_waitlist w JOIN cs2_section s ON s.id=w.section_id JOIN"
                + " cs2_round r ON r.id=w.round_id WHERE r.term_id=? AND w.state='WAITING' AND"
                + " r.state='OPEN' AND r.ends_at>CURRENT_TIMESTAMP"
                + wf,
            wa.toArray()));
    if (termSummary != null) out.put("waiting", termSummary.get("waitingCount"));
    out.put(
        "selectedCredits",
        a.role().equals("STUDENT") ? studentTerm(a.studentId(), t).get("selectedCredits") : 0);
    out.put(
        "creditLimit",
        a.role().equals("STUDENT") ? studentTerm(a.studentId(), t).get("creditLimit") : 0);
    out.put("termId", t);
    out.put("snapshotAt", db.now().toString());
    return out;
  }

  @Transactional(
      readOnly = true,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public Map<String, Object> consistency(String term) {
    String t = term(term);
    List<Map<String, Object>> mismatches = new ArrayList<>();
    List<Map<String, Object>> sections =
        db.rows(
            "SELECT s.id,s.enrolled_count,(SELECT COUNT(*) FROM cs2_enrollment e WHERE"
                + " e.section_id=s.id AND e.state='ENROLLED') AS actual FROM cs2_section s JOIN"
                + " cs2_offering o ON o.id=s.offering_id WHERE o.term_id=?",
            t);
    for (Map<String, Object> s : sections)
      if (num(s, "enrolledCount") != num(s, "actual"))
        mismatches.add(
            Api.object(
                "kind",
                "SECTION_COUNT",
                "id",
                s.get("id"),
                "stored",
                s.get("enrolledCount"),
                "actual",
                s.get("actual")));
    List<Map<String, Object>> students =
        db.rows(
            "SELECT st.student_id,st.selected_credits,COALESCE((SELECT SUM(o.credits) FROM"
                + " cs2_enrollment e JOIN cs2_offering o ON o.id=e.offering_id WHERE"
                + " e.student_id=st.student_id AND o.term_id=st.term_id AND e.state='ENROLLED'),0)"
                + " AS actual FROM cs2_student_term st WHERE st.term_id=?",
            t);
    for (Map<String, Object> s : students)
      if (num(s, "selectedCredits") != num(s, "actual"))
        mismatches.add(
            Api.object(
                "kind",
                "STUDENT_CREDITS",
                "id",
                s.get("studentId"),
                "stored",
                s.get("selectedCredits"),
                "actual",
                s.get("actual")));
    return Api.object(
        "status",
        mismatches.isEmpty() ? "PASS" : "FAIL",
        "checkedSections",
        sections.size(),
        "checkedStudents",
        students.size(),
        "mismatches",
        mismatches,
        "snapshotAt",
        db.now().toString());
  }
}
