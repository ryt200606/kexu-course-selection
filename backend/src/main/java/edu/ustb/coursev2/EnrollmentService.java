package edu.ustb.coursev2;

import static edu.ustb.coursev2.Db.*;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * All writes lock complete sorted section set, then complete sorted student set, then student_term.
 */
@Service
public class EnrollmentService {
  private final Db db;
  private final QueryService queries;
  private final Journal journal;
  private final Idempotency idempotency;

  public EnrollmentService(Db db, QueryService queries, Journal journal, Idempotency idempotency) {
    this.db = db;
    this.queries = queries;
    this.journal = journal;
    this.idempotency = idempotency;
  }

  private Map<String, Object> lockSection(String section) {
    db.one("SELECT id FROM cs2_section WHERE id=? FOR UPDATE", section);
    return queries.section(section);
  }

  private void lockStudents(Collection<String> students) {
    for (String student : new TreeSet<>(students))
      db.one("SELECT id FROM cs2_student WHERE id=? FOR UPDATE", student);
  }

  private List<Map<String, Object>> candidates(String section) {
    return db.rows(
        "SELECT w.id,w.student_id,w.round_id,w.queue_no FROM cs2_waitlist w WHERE w.section_id=?"
            + " AND w.state='WAITING' ORDER BY w.queue_no,w.id LIMIT 20",
        section);
  }

  private void lockTerm(String student, String term, int limit) {
    if (db.count(
            "SELECT COUNT(*) FROM cs2_student_term WHERE student_id=? AND term_id=?", student, term)
        == 0)
      db.update(
          "INSERT INTO cs2_student_term(student_id,term_id,selected_credits,credit_limit)"
              + " VALUES(?,?,0,?)",
          student,
          term,
          limit);
    db.one(
        "SELECT student_id FROM cs2_student_term WHERE student_id=? AND term_id=? FOR UPDATE",
        student,
        term);
  }

  private Map<String, Object> round(String term, boolean withdrawal) {
    List<Map<String, Object>> rounds =
        db.rows(
            "SELECT id,term_id,state,starts_at,ends_at,withdrawal_ends_at,credit_limit FROM"
                + " cs2_round r WHERE term_id=? AND state='OPEN' AND NOT EXISTS"
                + " (SELECT 1 FROM cs3_preference_round p WHERE p.round_id=r.id) ORDER BY starts_at DESC",
            term);
    Instant now = db.now();
    for (Map<String, Object> r : rounds) {
      Instant start = Instant.parse(str(r, "startsAt")),
          end = Instant.parse(str(r, withdrawal ? "withdrawalEndsAt" : "endsAt"));
      if (!now.isBefore(start) && now.isBefore(end)) return r;
    }
    throw Api.conflict("WINDOW_CLOSED", withdrawal ? "当前不在退课开放时间内" : "当前不在选课开放时间内");
  }

  public Map<String, Object> eligibility(String student, String section) {
    Map<String, Object> s = queries.section(section);
    List<Map<String, Object>> reasons = new ArrayList<>();
    try {
      round(str(s, "termId"), false);
    } catch (Api.Failure f) {
      reasons.add(reason(f.code, f.getMessage(), List.of()));
    }
    reasons.addAll(qualification(student, s, null));
    boolean full = num(s, "enrolledCount") >= num(s, "capacity"), queued = liveQueue(section) > 0;
    boolean qualified = reasons.isEmpty();
    if (full) reasons.add(reason("SECTION_FULL", "教学班容量已满", List.of()));
    else if (queued) reasons.add(reason("WAITLIST_FIRST", "已有候补队列，空位须按序递补", List.of()));
    return Api.object(
        "canEnroll",
        reasons.isEmpty(),
        "eligible",
        reasons.isEmpty(),
        "canWaitlist",
        qualified && (full || queued),
        "reasons",
        reasons);
  }

  private Map<String, Object> reason(String code, String message, List<String> related) {
    return Api.object("code", code, "message", message, "relatedSectionIds", related);
  }

  private List<Map<String, Object>> qualification(
      String student, Map<String, Object> section, String excludedSection) {
    List<Map<String, Object>> reasons = new ArrayList<>();
    String term = str(section, "termId"), sid = str(section, "id");
    if (!str(db.one("SELECT state FROM cs2_student WHERE id=?", student), "state").equals("ACTIVE"))
      reasons.add(reason("STUDENT_INACTIVE", "学生学籍已暂停", List.of()));
    if (!str(db.one("SELECT state FROM cs2_term WHERE id=?", term), "state").equals("ACTIVE"))
      reasons.add(reason("TERM_NOT_ACTIVE", "该学期未开放教学业务", List.of()));
    if (!str(section, "state").equals("PUBLISHED")
        || !str(
                db.one("SELECT state FROM cs2_offering WHERE id=?", section.get("offeringId")),
                "state")
            .equals("PUBLISHED"))
      reasons.add(reason("FROZEN_RESOURCE", "该教学班尚未发布或已经归档", List.of()));
    if (num(section, "gradeRevision") > 0)
      reasons.add(reason("GRADES_PUBLISHED", "该教学班成绩已发布，不能再选退课", List.of()));
    List<Map<String, Object>> duplicate =
        db.rows(
            "SELECT e.section_id FROM cs2_enrollment e WHERE e.student_id=? AND e.offering_id=? AND"
                + " e.state='ENROLLED'",
            student,
            section.get("offeringId"));
    for (Map<String, Object> e : duplicate)
      if (!Objects.equals(str(e, "sectionId"), excludedSection))
        reasons.add(reason("DUPLICATE_ENROLLMENT", "本学期已选过该课程", List.of(str(e, "sectionId"))));
    Map<String, Object> totals = queries.studentTerm(student, term);
    int credits = num(totals, "selectedCredits") + num(section, "credits");
    if (excludedSection != null) {
      Map<String, Object> source = queries.section(excludedSection);
      credits -= num(source, "credits");
    }
    if (credits > num(totals, "creditLimit"))
      reasons.add(reason("CREDIT_LIMIT", "选课后将超过本学期学分上限", List.of()));
    // Empty sentinels would turn x <> '' into x <> NULL in compatibility A.
    String conflictSql =
        "SELECT DISTINCT e.section_id FROM cs2_enrollment e JOIN cs2_offering o ON"
            + " o.id=e.offering_id JOIN cs2_meeting a ON a.section_id=e.section_id JOIN"
            + " cs2_meeting b ON b.section_id=? WHERE e.student_id=? AND o.term_id=? AND"
            + " e.state='ENROLLED' AND a.weekday=b.weekday AND"
            + " a.start_period<=b.end_period AND b.start_period<=a.end_period AND"
            + " a.start_week<=b.end_week AND b.start_week<=a.end_week";
    List<Object> conflictArgs = new ArrayList<>(List.of(sid, student, term));
    if (excludedSection != null) {
      conflictSql += " AND e.section_id<>?";
      conflictArgs.add(excludedSection);
    }
    List<Map<String, Object>> conflicts = db.rows(conflictSql, conflictArgs.toArray());
    if (!conflicts.isEmpty())
      reasons.add(
          reason(
              "TIMETABLE_CONFLICT",
              "与已选教学班的上课时段冲突",
              conflicts.stream().map(c -> str(c, "sectionId")).toList()));
    for (Map<String, Object> p :
        db.rows(
            "SELECT p.course_id,p.min_grade,c.title FROM cs2_prerequisite p JOIN cs2_course c ON"
                + " c.id=p.course_id WHERE p.offering_id=?",
            section.get("offeringId"))) {
      if (db.count(
              "SELECT COUNT(*) FROM cs2_enrollment e JOIN cs2_offering o ON o.id=e.offering_id JOIN"
                  + " cs2_section s ON s.id=e.section_id WHERE e.student_id=? AND o.course_id=? AND"
                  + " e.state='ENROLLED' AND e.published_grade>=?",
              student,
              p.get("courseId"),
              p.get("minGrade"))
          == 0)
        reasons.add(reason("PREREQUISITE_REQUIRED", "尚未通过先修课程：" + p.get("title"), List.of()));
    }
    return reasons;
  }

  private void requireQualified(String student, Map<String, Object> section, String exclude) {
    List<Map<String, Object>> r = qualification(student, section, exclude);
    if (!r.isEmpty()) throw Api.conflict(str(r.get(0), "code"), str(r.get(0), "message"));
  }

  private long liveQueue(String section) {
    return db.count(
        "SELECT COUNT(*) FROM cs2_waitlist w JOIN cs2_round r ON r.id=w.round_id WHERE"
            + " w.section_id=? AND w.state='WAITING' AND r.state='OPEN' AND"
            + " r.starts_at<=CURRENT_TIMESTAMP AND r.ends_at>CURRENT_TIMESTAMP",
        section);
  }

  private void addEnrollment(String student, Map<String, Object> s) {
    List<Map<String, Object>> old =
        db.rows(
            "SELECT id,state FROM cs2_enrollment WHERE student_id=? AND offering_id=?",
            student,
            s.get("offeringId"));
    if (old.isEmpty())
      db.update(
          "INSERT INTO"
              + " cs2_enrollment(id,student_id,offering_id,section_id,state,grade,created_at,updated_at)"
              + " VALUES(?,?,?,?,'ENROLLED',NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
          uid(),
          student,
          s.get("offeringId"),
          s.get("id"));
    else
      db.update(
          "UPDATE cs2_enrollment SET"
              + " section_id=?,state='ENROLLED',grade=NULL,grade_version=grade_version+1,updated_at=CURRENT_TIMESTAMP"
              + " WHERE id=?",
          s.get("id"),
          old.get(0).get("id"));
    db.update(
        "UPDATE cs2_section SET enrolled_count=enrolled_count+1,version=version+1 WHERE id=?",
        s.get("id"));
    db.update(
        "UPDATE cs2_student_term SET selected_credits=selected_credits+? WHERE student_id=? AND"
            + " term_id=?",
        s.get("credits"),
        student,
        s.get("termId"));
    s.put("enrolledCount", num(s, "enrolledCount") + 1);
  }

  private void removeEnrollment(String student, Map<String, Object> s) {
    Map<String, Object> e =
        db.one(
            "SELECT id FROM cs2_enrollment WHERE student_id=? AND section_id=? AND"
                + " state='ENROLLED'",
            student,
            s.get("id"));
    if (num(s, "gradeRevision") > 0) throw Api.conflict("GRADES_PUBLISHED", "已发布成绩的课程不能退选");
    db.update(
        "UPDATE cs2_enrollment SET"
            + " state='WITHDRAWN',grade=NULL,grade_version=grade_version+1,updated_at=CURRENT_TIMESTAMP"
            + " WHERE id=?",
        e.get("id"));
    db.update(
        "UPDATE cs2_section SET enrolled_count=enrolled_count-1,version=version+1 WHERE id=?",
        s.get("id"));
    db.update(
        "UPDATE cs2_student_term SET selected_credits=selected_credits-? WHERE student_id=? AND"
            + " term_id=?",
        s.get("credits"),
        student,
        s.get("termId"));
    s.put("enrolledCount", num(s, "enrolledCount") - 1);
  }

  /** Called by a transactional batch publisher. Lock every affected section before any student. */
  void lockForAllocation(Collection<String> sections, Collection<String> students, String term, int limit) {
    for (String id : new TreeSet<>(sections))
      db.one("SELECT id FROM cs2_section WHERE id=? FOR UPDATE", id);
    lockStudents(students);
    for (String id : new TreeSet<>(students)) lockTerm(id, term, limit);
  }

  /** Uses the same locked eligibility rules, counters and notification transaction as direct enroll. */
  void applyAllocatedSeat(String student, String section, String roundId, String runId) {
    Map<String, Object> s = queries.section(section);
    if (db.count("SELECT COUNT(*) FROM cs3_preference_round p JOIN cs2_round r ON r.id=p.round_id"
        + " WHERE r.id=? AND r.term_id=? AND p.phase='FROZEN'",roundId,s.get("termId")) != 1)
      throw Api.conflict("STALE_PREVIEW","选课轮次已经变化");
    requireQualified(student,s,null);
    if (liveQueue(section)>0) throw Api.conflict("WAITLIST_FIRST","已有候补享有优先权");
    if (num(s,"enrolledCount")>=num(s,"capacity")) throw Api.conflict("SECTION_FULL","教学班已满");
    addEnrollment(student,s);
    journal.audit("ALLOCATE_SEAT", section, "志愿分配 "+runId+" · "+student, str(s,"termId"));
    journal.notifyStudent(student,"志愿分配成功",s.get("courseTitle")+" · "+section);
  }

  @Transactional(timeout = 10)
  public Map<String, Object> enroll(String section, String key) {
    Actor.require("STUDENT");
    String student = Actor.current().studentId();
    return idempotency.run(
        "ENROLL",
        key,
        Map.of("sectionId", section),
        () -> {
          Map<String, Object> s = lockSection(section);
          lockStudents(List.of(student));
          Map<String, Object> r = round(str(s, "termId"), false);
          lockTerm(student, str(s, "termId"), num(r, "creditLimit"));
          requireQualified(student, s, null);
          if (liveQueue(section) > 0) throw Api.conflict("WAITLIST_FIRST", "请按候补顺序等待空位");
          if (num(s, "enrolledCount") >= num(s, "capacity"))
            throw Api.conflict("SECTION_FULL", "教学班已满，可加入候补");
          addEnrollment(student, s);
          journal.audit("ENROLL", section, "学生选课成功", str(s, "termId"));
          journal.notifyStudent(student, "选课成功", s.get("courseTitle") + " · " + section);
          return queries.enrollment(student, section);
        });
  }

  @Transactional(timeout = 10)
  public Map<String, Object> withdraw(String section, String key) {
    Actor.require("STUDENT");
    String student = Actor.current().studentId();
    return idempotency.run(
        "WITHDRAW",
        key,
        Map.of("sectionId", section),
        () -> {
          Map<String, Object> s = lockSection(section);
          List<Map<String, Object>> queue = candidates(section);
          Set<String> students = new TreeSet<>(List.of(student));
          queue.forEach(w -> students.add(str(w, "studentId")));
          lockStudents(students);
          Map<String, Object> r = round(str(s, "termId"), true);
          for (String id : students) lockTerm(id, str(s, "termId"), num(r, "creditLimit"));
          removeEnrollment(student, s);
          journal.audit("WITHDRAW", section, "学生退课成功", str(s, "termId"));
          journal.notifyStudent(student, "退课成功", s.get("courseTitle") + "已退选");
          Map<String, Object> promotion = promoteLocked(s, queue);
          return Api.object(
              "status", "WITHDRAWN", "promotionPending", promotion.get("promotionPending"));
        });
  }

  @Transactional(timeout = 10)
  public Map<String, Object> swap(String source, String target, String key) {
    Actor.require("STUDENT");
    if (source.equals(target)) throw Api.invalid("源教学班和目标教学班不能相同");
    String student = Actor.current().studentId();
    return idempotency.run(
        "SWAP",
        key,
        Map.of("sourceSectionId", source, "targetSectionId", target),
        () -> {
          Map<String, Map<String, Object>> sections = new TreeMap<>();
          for (String id : new TreeSet<>(List.of(source, target)))
            sections.put(id, lockSection(id));
          Map<String, Object> from = sections.get(source), to = sections.get(target);
          if (!Objects.equals(from.get("termId"), to.get("termId"))) throw Api.invalid("只能在同一学期换课");
          List<Map<String, Object>> queue = candidates(source);
          Set<String> students = new TreeSet<>(List.of(student));
          queue.forEach(w -> students.add(str(w, "studentId")));
          lockStudents(students);
          Map<String, Object> r = round(str(to, "termId"), false);
          round(str(from, "termId"), true);
          for (String id : students) lockTerm(id, str(to, "termId"), num(r, "creditLimit"));
          db.one(
              "SELECT id FROM cs2_enrollment WHERE student_id=? AND section_id=? AND"
                  + " state='ENROLLED'",
              student,
              source);
          requireQualified(student, to, source);
          if (num(from, "gradeRevision") > 0) throw Api.conflict("GRADES_PUBLISHED", "源课程成绩已发布");
          if (liveQueue(target) > 0) throw Api.conflict("WAITLIST_FIRST", "目标班有候补，不能插队换入");
          if (num(to, "enrolledCount") >= num(to, "capacity"))
            throw Api.conflict("SECTION_FULL", "目标教学班已满，原选课保持不变");
          removeEnrollment(student, from);
          addEnrollment(student, to);
          promoteLocked(from, queue);
          journal.audit("SWAP", source + "→" + target, "原子换课成功", str(to, "termId"));
          journal.notifyStudent(
              student, "换课成功", from.get("courseTitle") + " → " + to.get("courseTitle"));
          return Api.object(
              "status", "SWAPPED", "sourceSectionId", source, "targetSectionId", target);
        });
  }

  @Transactional(timeout = 10)
  public Map<String, Object> joinWaitlist(String section, String key) {
    Actor.require("STUDENT");
    String student = Actor.current().studentId();
    return idempotency.run(
        "WAITLIST_JOIN",
        key,
        Map.of("sectionId", section),
        () -> {
          Map<String, Object> s = lockSection(section);
          lockStudents(List.of(student));
          Map<String, Object> r = round(str(s, "termId"), false);
          lockTerm(student, str(s, "termId"), num(r, "creditLimit"));
          requireQualified(student, s, null);
          if (num(s, "enrolledCount") < num(s, "capacity") && liveQueue(section) == 0)
            throw Api.conflict("SEAT_AVAILABLE", "当前有空位，请直接选课");
          if (db.count(
                  "SELECT COUNT(*) FROM cs2_waitlist w JOIN cs2_section sc ON sc.id=w.section_id"
                      + " JOIN cs2_round ro ON ro.id=w.round_id WHERE w.student_id=? AND"
                      + " sc.offering_id=? AND w.state='WAITING' AND ro.state='OPEN' AND"
                      + " ro.ends_at>CURRENT_TIMESTAMP",
                  student,
                  s.get("offeringId"))
              > 0) throw Api.conflict("DUPLICATE_WAITLIST", "本学期该课程已有候补记录");
          long seq =
              db.lng(
                  db.one("SELECT next_queue_no FROM cs2_section WHERE id=?", section),
                  "nextQueueNo");
          String id = uid();
          db.update("UPDATE cs2_section SET next_queue_no=next_queue_no+1 WHERE id=?", section);
          db.update(
              "INSERT INTO"
                  + " cs2_waitlist(id,student_id,section_id,round_id,queue_no,state,reason,created_at,updated_at)"
                  + " VALUES(?,?,?,?,?,'WAITING','排队等待',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
              id,
              student,
              section,
              r.get("id"),
              seq);
          journal.audit("WAITLIST_JOIN", section, "加入候补，顺序号" + seq, str(s, "termId"));
          journal.notifyStudent(student, "候补已受理", s.get("courseTitle") + "，候补序号" + seq);
          if (num(s, "enrolledCount") < num(s, "capacity"))
            db.update("UPDATE cs2_section SET promotion_pending=1 WHERE id=?", section);
          return Api.object(
              "id",
              id,
              "sectionId",
              section,
              "courseTitle",
              s.get("courseTitle"),
              "roundId",
              r.get("id"),
              "queueNo",
              seq,
              "position",
              liveQueue(section),
              "state",
              "WAITING",
              "reason",
              "排队等待");
        });
  }

  @Transactional(timeout = 10)
  public Map<String, Object> cancelWaitlist(String id, String key) {
    Actor.require("STUDENT");
    String student = Actor.current().studentId();
    return idempotency.run(
        "WAITLIST_CANCEL",
        key,
        Map.of("id", id),
        () -> {
          Map<String, Object> w =
              db.one(
                  "SELECT section_id FROM cs2_waitlist WHERE id=? AND student_id=?", id, student);
          Map<String, Object> s = lockSection(str(w, "sectionId"));
          lockStudents(List.of(student));
          int changed =
              db.update(
                  "UPDATE cs2_waitlist SET"
                      + " state='CANCELLED',reason='学生主动取消',updated_at=CURRENT_TIMESTAMP WHERE id=?"
                      + " AND student_id=? AND state='WAITING'",
                  id,
                  student);
          if (changed == 0) throw Api.conflict("WAITLIST_NOT_ACTIVE", "候补已处理，不能再取消");
          if (num(s, "enrolledCount") < num(s, "capacity"))
            db.update("UPDATE cs2_section SET promotion_pending=1 WHERE id=?", s.get("id"));
          journal.audit("WAITLIST_CANCEL", id, "学生取消候补", str(s, "termId"));
          return Map.of("status", "CANCELLED");
        });
  }

  @Transactional(timeout = 10)
  public Map<String, Object> promote(String section) {
    Map<String, Object> s = lockSection(section);
    List<Map<String, Object>> queue = candidates(section);
    Set<String> students = new TreeSet<>();
    queue.forEach(w -> students.add(str(w, "studentId")));
    lockStudents(students);
    for (String student : students)
      lockTerm(student, str(s, "termId"), queries.defaultLimit(str(s, "termId")));
    return promoteLocked(s, queue);
  }

  private Map<String, Object> promoteLocked(
      Map<String, Object> s, List<Map<String, Object>> queue) {
    long started = System.nanoTime();
    int processed = 0;
    String section = str(s, "id");
    Map<String, Object> open = null;
    try {
      open = round(str(s, "termId"), false);
    } catch (Api.Failure ignored) {
    }
    for (Map<String, Object> candidate : queue) {
      if (processed >= 20 || System.nanoTime() - started > 2_000_000_000L) break;
      boolean currentRound =
          open != null && Objects.equals(open.get("id"), candidate.get("roundId"));
      // Expired entries must drain even when a new round exists and the class is full.
      if (num(s, "enrolledCount") >= num(s, "capacity") && currentRound) break;
      String state = "EXPIRED", reason = "选课轮次已经结束";
      String student = str(candidate, "studentId");
      if (currentRound) {
        List<Map<String, Object>> invalid = qualification(student, s, null);
        if (invalid.isEmpty()) {
          addEnrollment(student, s);
          state = "ENROLLED";
          reason = "按候补顺序转正";
        } else {
          state = "SKIPPED";
          reason = str(invalid.get(0), "message");
        }
      }
      db.update(
          "UPDATE cs2_waitlist SET state=?,reason=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND"
              + " state='WAITING'",
          state,
          reason,
          candidate.get("id"));
      journal.notifyStudent(
          student,
          state.equals("ENROLLED") ? "候补转正成功" : "候补处理结果",
          s.get("courseTitle") + "：" + reason);
      journal.audit("WAITLIST_" + state, str(candidate, "id"), reason, str(s, "termId"));
      processed++;
    }
    boolean expiredRemain =
        db.count(
                "SELECT COUNT(*) FROM cs2_waitlist w JOIN cs2_round r ON r.id=w.round_id WHERE"
                    + " w.section_id=? AND w.state='WAITING' AND (r.state<>'OPEN' OR"
                    + " r.ends_at<=CURRENT_TIMESTAMP)",
                section)
            > 0;
    boolean pending =
        expiredRemain
            || db.count(
                        "SELECT COUNT(*) FROM cs2_waitlist WHERE section_id=? AND state='WAITING'",
                        section)
                    > 0
                && (open == null || num(s, "enrolledCount") < num(s, "capacity"));
    db.update("UPDATE cs2_section SET promotion_pending=? WHERE id=?", pending ? 1 : 0, section);
    return Api.object("status", "OK", "processed", processed, "promotionPending", pending);
  }
}
