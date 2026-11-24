package edu.ustb.coursev2;

import static edu.ustb.coursev2.Db.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PlanningService {
  private final Db db;
  private final EnrollmentService enrollment;
  private final QueryService queries;
  private final AdminService admin;
  private final Journal journal;
  private final Idempotency idem;
  private final ObjectMapper json;
  private final TransactionTemplate tx;
  private final TransactionTemplate snapshotTx;
  private final Semaphore solves = new Semaphore(1, true);
  private final SecureRandom random = new SecureRandom();

  public PlanningService(
      Db db,
      EnrollmentService enrollment,
      QueryService queries,
      AdminService admin,
      Journal journal,
      Idempotency idem,
      ObjectMapper mapper,
      PlatformTransactionManager tm) {
    this.db = db;
    this.enrollment = enrollment;
    this.queries = queries;
    this.admin = admin;
    this.journal = journal;
    this.idem = idem;
    this.json = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    this.tx = new TransactionTemplate(tm);
    this.snapshotTx = new TransactionTemplate(tm);
    this.snapshotTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.snapshotTx.setReadOnly(true);
  }

  private String serialize(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Map<String, Object> data(String text) {
    try {
      return json.readValue(text, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("Cannot read stored plan", e);
    }
  }

  private String hash(Object value) {
    return AllocationSolver.sha(serialize(value));
  }

  private <T> T cast(Object obj, Class<T> type) {
    return json.convertValue(obj, type);
  }

  static List<Map<String, Object>> records(Object object, int max, String name) {
    if (!(object instanceof List<?> raw) || raw.size() > max) throw Api.invalid(name + "数组超出限制");
    var list = new ArrayList<Map<String, Object>>();
    for (Object row : raw) {
      if (!(row instanceof Map<?, ?> map)) throw Api.invalid(name + "元素格式错误");
      var copy = new LinkedHashMap<String, Object>();
      map.forEach((k, v) -> copy.put(k.toString(), v));
      list.add(copy);
    }
    return list;
  }

  private Map<String, Object> round(String id, boolean lock) {
    return db.one(
        "SELECT id,term_id,state,starts_at,ends_at,credit_limit,version FROM cs2_round WHERE id=?"
            + (lock ? " FOR UPDATE" : ""),
        id);
  }

  private void open(Map<String, Object> r) {
    Instant now = db.now();
    if (!"OPEN".equals(r.get("state"))
        || now.isBefore(Instant.parse(str(r, "startsAt")))
        || !now.isBefore(Instant.parse(str(r, "endsAt"))))
      throw Api.conflict("WINDOW_CLOSED", "此志愿轮次当前未开放");
  }

  private void collectionEnded(Map<String,Object> round) {
    String state=str(round,"state");
    if (!state.equals("CLOSED") && !(state.equals("OPEN") && !db.now().isBefore(Instant.parse(str(round,"endsAt")))))
      throw Api.conflict("PREFERENCES_STILL_OPEN","请在本轮填报关闭后再封存、求解并公布抽签种子");
  }

  private Map<String, Object> policy(String id, boolean lock) {
    var rows =
        db.rows(
            "SELECT round_id,phase,seed_secret,seed_commitment FROM cs3_preference_round WHERE"
                + " round_id=?"
                + (lock ? " FOR UPDATE" : ""),
            id);
    if (rows.isEmpty()) throw Api.conflict("SEED_NOT_COMMITTED", "教务尚未为本轮建立志愿分配");
    return rows.get(0);
  }

  @Transactional
  public Map<String, Object> commit(String id) {
    Actor.require("ADMIN");
    var r = round(id, true);
    if ("CLOSED".equals(r.get("state"))) throw Api.conflict("WINDOW_CLOSED", "不能为已结束轮次创建分配");
    if (db.count("SELECT COUNT(*) FROM cs3_preference_round WHERE round_id=?", id) == 0) {
      if ("OPEN".equals(r.get("state")) && !db.now().isBefore(Instant.parse(str(r,"startsAt"))))
        throw Api.conflict("COMMIT_TOO_LATE","请在本轮首次开放填报前建立抽签承诺");
      byte[] bytes = new byte[32];
      random.nextBytes(bytes);
      String seed = HexFormat.of().formatHex(bytes);
      db.update(
          "INSERT INTO cs3_preference_round(round_id,seed_secret,seed_commitment,phase)"
              + " VALUES(?,?,?,'COLLECTING')",
          id,
          seed,
          AllocationSolver.sha(seed));
      journal.audit("PREFERENCE_COMMIT", id, "预先承诺随机种子摘要", str(r, "termId"));
    }
    var p = policy(id, false);
    return Map.of(
        "roundId", id, "phase", p.get("phase"), "seedCommitment", p.get("seedCommitment"));
  }

  public Map<String, Object> preferences(String id) {
    Actor.require("STUDENT");
    round(id, false);
    var ps = db.rows("SELECT phase,seed_commitment FROM cs3_preference_round WHERE round_id=?", id);
    String student = Actor.current().studentId();
    var forms =
        db.rows(
            "SELECT version FROM cs3_preference_form WHERE round_id=? AND student_id=?",
            id,
            student);
    var items =
        db.rows(
            "SELECT section_id FROM cs3_preference_choice WHERE round_id=? AND student_id=? ORDER"
                + " BY choice_rank",
            id,
            student);
    List<Object> assigned = new ArrayList<>(), unassigned = new ArrayList<>();
    if (!ps.isEmpty() && "PUBLISHED".equals(ps.get(0).get("phase"))) {
      var run =
          db.one(
              "SELECT id FROM cs3_planning_run WHERE round_id=? AND state='PUBLISHED' AND"
                  + " kind='ALLOCATION'",
              id);
      var result = runResult(str(run, "id"));
      for (var row : records(result.get("assignments"), 2000, "assignments"))
        if (student.equals(row.get("studentId"))) assigned.add(row);
      for (var row : records(result.get("unassigned"), 2000, "unassigned"))
        if (student.equals(row.get("studentId"))) unassigned.add(row);
    }
    return Api.object(
        "roundId",
        id,
        "phase",
        ps.isEmpty() ? "UNCONFIGURED" : ps.get(0).get("phase"),
        "seedCommitment",
        ps.isEmpty() ? null : ps.get(0).get("seedCommitment"),
        "version",
        forms.isEmpty() ? 0 : lng(forms.get(0), "version"),
        "sectionIds",
        items.stream().map(m -> m.get("sectionId")).toList(),
        "assignments",
        assigned,
        "unassigned",
        unassigned);
  }

  @Transactional
  public Map<String, Object> savePreferences(String id, Map<String, Object> body) {
    Actor.require("STUDENT");
    var p = policy(id, true);
    if (!"COLLECTING".equals(p.get("phase"))) throw Api.conflict("PREFERENCES_FROZEN", "志愿已封存");
    var r = round(id, false);
    open(r);
    String student = Actor.current().studentId();
    var current =
        db.rows(
            "SELECT version FROM cs3_preference_form WHERE round_id=? AND student_id=?",
            id,
            student);
    long ver = current.isEmpty() ? 0 : lng(current.get(0), "version");
    if (ver != Api.version(body)) throw Api.conflict("VERSION_CONFLICT", "志愿已更新，请重新载入");
    if (!(body.get("sectionIds") instanceof List<?> raw) || raw.size() > AllocationSolver.MAX_RANK)
      throw Api.invalid("志愿请填写0—8个不重复的教学班");
    var choices = new LinkedHashSet<String>();
    for (Object v : raw) {
      if (!(v instanceof String section)
          || !section.matches("[A-Za-z0-9_-]{1,32}")
          || !choices.add(section)) throw Api.invalid("教学班编号格式或重复项错误");
      if (db.count(
              "SELECT COUNT(*) FROM cs2_section s JOIN cs2_offering o ON o.id=s.offering_id WHERE"
                  + " s.id=? AND o.term_id=? AND s.state='PUBLISHED' AND o.state='PUBLISHED'",
              section,
              r.get("termId"))
          != 1) throw Api.invalid("只能填报本学期已发布教学班");
    }
    if (current.isEmpty())
      db.update(
          "INSERT INTO cs3_preference_form(round_id,student_id,version) VALUES(?,?,1)",
          id,
          student);
    else
      db.update(
          "UPDATE cs3_preference_form SET version=version+1 WHERE round_id=? AND student_id=?",
          id,
          student);
    db.update("DELETE FROM cs3_preference_choice WHERE round_id=? AND student_id=?", id, student);
    int rank = 0;
    for (String section : choices)
      db.update(
          "INSERT INTO cs3_preference_choice(round_id,student_id,section_id,choice_rank)"
              + " VALUES(?,?,?,?)",
          id,
          student,
          section,
          ++rank);
    journal.audit("PREFERENCE_SAVE", id, "提交" + choices.size() + "项志愿", str(r, "termId"));
    return preferences(id);
  }

  record AllocationSnapshot(
      Map<String, Object> round,
      List<Map<String, Object>> forms,
      String sourceDigest,
      Map<String, Long> sectionVersions,
      AllocationSolver.Input model) {}

  /** A fixed number of set queries materializes all constraints at one repeatable-read snapshot. */
  private AllocationSnapshot allocationInput(String id) {
    var round = round(id, false);
    collectionEnded(round);
    String term = str(round, "termId");
    var policy = policy(id, false);
    var rows =
        db.rows(
            "SELECT student_id,section_id,choice_rank FROM cs3_preference_choice WHERE round_id=?"
                + " ORDER BY student_id,choice_rank LIMIT 2001",
            id);
    if (rows.isEmpty() || rows.size() > 2000) throw Api.invalid("本次分配支持1—2000条志愿");
    var forms =
        db.rows(
            "SELECT f.student_id,f.version,s.state AS student_state,s.version AS"
                + " student_version,COALESCE(t.selected_credits,0) AS"
                + " selected_credits,COALESCE(t.credit_limit,?) AS credit_limit FROM"
                + " cs3_preference_form f JOIN cs2_student s ON s.id=f.student_id LEFT JOIN"
                + " cs2_student_term t ON t.student_id=f.student_id AND t.term_id=? WHERE"
                + " f.round_id=? ORDER BY f.student_id",
            num(round, "creditLimit"),
            term,
            id);
    var sectionRows =
        db.rows(
            "SELECT"
                + " s.id,s.version,s.state,s.grade_revision,s.capacity,s.enrolled_count,s.offering_id,o.credits,o.state"
                + " AS offering_state FROM cs2_section s JOIN cs2_offering o ON o.id=s.offering_id"
                + " WHERE EXISTS(SELECT 1 FROM cs3_preference_choice p WHERE p.round_id=? AND"
                + " p.section_id=s.id) ORDER BY s.id",
            id);
    var meetings =
        db.rows(
            "SELECT"
                + " m.section_id,m.weekday,m.start_period,m.end_period,m.start_week,m.end_week,m.room"
                + " FROM cs2_meeting m WHERE EXISTS(SELECT 1 FROM cs3_preference_choice p WHERE"
                + " p.round_id=? AND p.section_id=m.section_id) ORDER BY m.section_id,m.id",
            id);
    var prereqs =
        db.rows(
            "SELECT p.offering_id,p.course_id,p.min_grade FROM cs2_prerequisite p WHERE"
                + " EXISTS(SELECT 1 FROM cs3_preference_choice c JOIN cs2_section s ON"
                + " s.id=c.section_id WHERE c.round_id=? AND s.offering_id=p.offering_id) ORDER BY"
                + " p.offering_id,p.course_id",
            id);
    var history =
        db.rows(
            "SELECT"
                + " e.student_id,e.section_id,e.offering_id,o.course_id,o.term_id,e.published_grade,e.grade_version"
                + " FROM cs2_enrollment e JOIN cs3_preference_form f ON f.student_id=e.student_id"
                + " JOIN cs2_offering o ON o.id=e.offering_id WHERE f.round_id=? AND"
                + " e.state='ENROLLED' ORDER BY e.student_id,e.offering_id",
            id);
    var previousMeetings =
        db.rows(
            "SELECT"
                + " e.student_id,m.weekday,m.start_period,m.end_period,m.start_week,m.end_week,m.room"
                + " FROM cs2_enrollment e JOIN cs3_preference_form f ON f.student_id=e.student_id"
                + " JOIN cs2_offering o ON o.id=e.offering_id JOIN cs2_meeting m ON"
                + " m.section_id=e.section_id WHERE f.round_id=? AND o.term_id=? AND"
                + " e.state='ENROLLED' ORDER BY e.student_id,m.id",
            id,
            term);
    var waitlists =
        db.rows(
            "SELECT DISTINCT w.section_id FROM cs2_waitlist w JOIN cs2_round r ON r.id=w.round_id"
                + " WHERE w.state='WAITING' AND r.state='OPEN' AND r.starts_at<=CURRENT_TIMESTAMP"
                + " AND r.ends_at>CURRENT_TIMESTAMP AND EXISTS(SELECT 1 FROM cs3_preference_choice"
                + " p WHERE p.round_id=? AND p.section_id=w.section_id) ORDER BY w.section_id",
            id);
    var termRow = db.one("SELECT state,version FROM cs2_term WHERE id=?", term);
    var students = byId(forms, "studentId");
    var sections = byId(sectionRows, "id");
    var slots = groupSlots(meetings, "sectionId");
    var occupied = groupSlots(previousMeetings, "studentId");
    var requirements = groupRows(prereqs, "offeringId");
    var enrolled = groupRows(history, "studentId");
    Set<String> waiting = new HashSet<>();
    waitlists.forEach(w -> waiting.add(str(w, "sectionId")));
    var versions = new TreeMap<String, Long>();
    var seats = new TreeMap<String, Integer>();
    var budgets = new TreeMap<String, Integer>();
    forms.forEach(
        f ->
            budgets.put(
                str(f, "studentId"),
                Math.max(0, num(f, "creditLimit") - num(f, "selectedCredits"))));
    sectionRows.forEach(
        s -> {
          versions.put(str(s, "id"), lng(s, "version"));
          seats.put(str(s, "id"), num(s, "capacity") - num(s, "enrolledCount"));
        });
    var choices = new ArrayList<AllocationSolver.Choice>();
    for (var row : rows) {
      String student = str(row, "studentId"), section = str(row, "sectionId");
      var s = sections.get(section);
      String offering = str(s, "offeringId");
      var planned = slots.getOrDefault(section, List.of());
      var existing = enrolled.getOrDefault(student, List.of());
      var reasons = new ArrayList<String>();
      if (!"ACTIVE".equals(students.get(student).get("studentState")))
        reasons.add("STUDENT_INACTIVE");
      if (!"ACTIVE".equals(termRow.get("state"))) reasons.add("TERM_NOT_ACTIVE");
      if (!"PUBLISHED".equals(s.get("state")) || !"PUBLISHED".equals(s.get("offeringState")))
        reasons.add("FROZEN_RESOURCE");
      if (num(s, "gradeRevision") > 0) reasons.add("GRADES_PUBLISHED");
      if (existing.stream().anyMatch(e -> offering.equals(e.get("offeringId"))))
        reasons.add("DUPLICATE_ENROLLMENT");
      if (num(s, "credits") > budgets.get(student)) reasons.add("CREDIT_LIMIT");
      if (planned.stream()
          .anyMatch(a -> occupied.getOrDefault(student, List.of()).stream().anyMatch(a::overlaps)))
        reasons.add("TIMETABLE_CONFLICT");
      for (var p : requirements.getOrDefault(offering, List.of())) {
        if (existing.stream()
            .noneMatch(
                e ->
                    p.get("courseId").equals(e.get("courseId"))
                        && e.get("publishedGrade") instanceof Number grade
                        && new java.math.BigDecimal(grade.toString())
                                .compareTo(new java.math.BigDecimal(p.get("minGrade").toString()))
                            >= 0)) {
          reasons.add("PREREQUISITE_REQUIRED");
          break;
        }
      }
      if (seats.get(section) == 0) reasons.add("SECTION_FULL");
      if (waiting.contains(section)) reasons.add("WAITLIST_FIRST");
      choices.add(
          new AllocationSolver.Choice(
              student,
              section,
              offering,
              num(row, "choiceRank"),
              num(s, "credits"),
              planned,
              reasons));
    }
    String sourceDigest =
        hash(
            List.of(termRow, sectionRows, meetings, prereqs, history, previousMeetings, waitlists));
    return new AllocationSnapshot(
        round,
        forms,
        sourceDigest,
        versions,
        new AllocationSolver.Input(str(policy, "seedSecret"), seats, budgets, choices));
  }

  private Map<String, Map<String, Object>> byId(List<Map<String, Object>> rows, String key) {
    var result = new HashMap<String, Map<String, Object>>();
    rows.forEach(r -> result.put(str(r, key), r));
    return result;
  }

  private Map<String, List<Map<String, Object>>> groupRows(
      List<Map<String, Object>> rows, String key) {
    var result = new HashMap<String, List<Map<String, Object>>>();
    rows.forEach(r -> result.computeIfAbsent(str(r, key), k -> new ArrayList<>()).add(r));
    return result;
  }

  private Map<String, List<AllocationSolver.Slot>> groupSlots(
      List<Map<String, Object>> rows, String key) {
    var result = new HashMap<String, List<AllocationSolver.Slot>>();
    rows.forEach(r -> result.computeIfAbsent(str(r, key), k -> new ArrayList<>()).add(slot(r)));
    return result;
  }

  AllocationSolver.Slot slot(Map<String, Object> s) {
    int weekday = Api.integer(s, "weekday", 1, 7),
        start = Api.integer(s, "startPeriod", 1, 14),
        end = Api.integer(s, "endPeriod", start, 14);
    int first = Api.integer(s, "startWeek", 1, 30), last = Api.integer(s, "endWeek", first, 30);
    return new AllocationSolver.Slot(weekday, start, end, first, last, Api.text(s, "room", 80));
  }

  public Map<String, Object> allocationPreview(String id) {
    Actor.require("ADMIN");
    if (!solves.tryAcquire()) throw new Api.Failure(429, "SOLVER_BUSY", "另一个规划正在求解");
    try {
      tx.executeWithoutResult(
          st -> {
            var p = policy(id, true);
            collectionEnded(round(id, false));
            if ("PUBLISHED".equals(p.get("phase")))
              throw Api.conflict("ROUND_PUBLISHED", "本轮志愿已发布");
            if (db.count("SELECT COUNT(*) FROM cs3_preference_choice WHERE round_id=?", id) == 0)
              throw Api.invalid("尚无已提交志愿");
            if ("COLLECTING".equals(p.get("phase"))) {
              db.update("UPDATE cs3_preference_round SET phase='FROZEN' WHERE round_id=?", id);
              journal.audit("PREFERENCE_FREEZE", id, "封存志愿，开始求解", str(round(id, false), "termId"));
            }
          });
      AllocationSnapshot snapshot = snapshotTx.execute(st -> allocationInput(id));
      var result = AllocationSolver.solve(snapshot.model());
      var chosen =
          result.assignments().stream()
              .map(a -> a.studentId() + "/" + a.sectionId())
              .collect(java.util.stream.Collectors.toSet());
      var selected =
          snapshot.model.choices().stream().filter(c -> chosen.contains(c.key())).toList();
      var missed =
          snapshot.model.choices().stream()
              .filter(c -> !chosen.contains(c.key()))
              .map(c -> explain(c, selected, snapshot.model))
              .toList();
      var output =
          Api.object(
              "solverStatus",
              result.solverStatus(),
              "assignments",
              result.assignments(),
              "metrics",
              result.metrics(),
              "greedy",
              result.greedy(),
              "greedyAssignments",
              result.greedyAssignments(),
              "provenObjectives",
              result.provenObjectives(),
              "stages",
              result.stages(),
              "unassigned",
              missed,
              "seedReveal",
              snapshot.model.seed(),
              "seedCommitment",
              AllocationSolver.sha(snapshot.model.seed()));
      return store(
          "ALLOCATION", str(snapshot.round, "termId"), id, Map.of("roundId", id), snapshot, output);
    } finally {
      solves.release();
    }
  }

  private static final Map<String, String> REASONS =
      Map.ofEntries(
          Map.entry("STUDENT_INACTIVE", "学籍当前未激活"), Map.entry("TERM_NOT_ACTIVE", "学期未开放"),
          Map.entry("FROZEN_RESOURCE", "教学班或开设未发布"), Map.entry("GRADES_PUBLISHED", "教学班成绩已发布"),
          Map.entry("DUPLICATE_ENROLLMENT", "本学期已有同一课程"), Map.entry("CREDIT_LIMIT", "超过本学期可选学分"),
          Map.entry("TIMETABLE_CONFLICT", "与已有或同批分配课程的周次和节次冲突"),
              Map.entry("PREREQUISITE_REQUIRED", "先修课程正式成绩未达到要求"),
          Map.entry("SECTION_FULL", "按本轮方案分配后无剩余名额"), Map.entry("WAITLIST_FIRST", "待处理候补享有优先权"),
          Map.entry("SAME_COURSE", "本批已获得同一课程的另一个教学班"),
              Map.entry("GLOBAL_OBJECTIVE", "在本轮覆盖、志愿顺位与种子排序下未入选"));

  private Map<String, Object> explain(
      AllocationSolver.Choice c,
      List<AllocationSolver.Choice> selected,
      AllocationSolver.Input input) {
    var reasons = new LinkedHashSet<>(c.reasons());
    if (reasons.isEmpty()) {
      var own = selected.stream().filter(a -> a.studentId().equals(c.studentId())).toList();
      if (own.stream().anyMatch(a -> a.offeringId().equals(c.offeringId())))
        reasons.add("SAME_COURSE");
      if (own.stream()
          .anyMatch(
              a ->
                  a.meetings().stream().anyMatch(s -> c.meetings().stream().anyMatch(s::overlaps))))
        reasons.add("TIMETABLE_CONFLICT");
      if (own.stream().mapToInt(AllocationSolver.Choice::credits).sum() + c.credits()
          > input.budgets().get(c.studentId())) reasons.add("CREDIT_LIMIT");
      if (selected.stream().filter(a -> a.sectionId().equals(c.sectionId())).count()
          >= input.seats().get(c.sectionId())) reasons.add("SECTION_FULL");
      if (reasons.isEmpty()) reasons.add("GLOBAL_OBJECTIVE");
    }
    return Api.object(
        "studentId",
        c.studentId(),
        "sectionId",
        c.sectionId(),
        "rank",
        c.rank(),
        "reasons",
        reasons,
        "explanation",
        String.join("；", reasons.stream().map(REASONS::get).toList()));
  }

  private String runField(String id, String field) {
    if (!Set.of("input_json", "result_json").contains(field)) throw new IllegalArgumentException();
    return db.jdbc.queryForObject(
        "SELECT " + field + " FROM cs3_planning_run WHERE id=?", String.class, id);
  }

  private Map<String, Object> runResult(String id) {
    return data(runField(id, "result_json"));
  }

  private Map<String, Object> metadata(String id) {
    return db.one(
        "SELECT id,kind,term_id,round_id,state,snapshot_hash,created_at,published_at FROM"
            + " cs3_planning_run WHERE id=?",
        id);
  }

  public Map<String, Object> run(String id) {
    Actor.require("ADMIN");
    var result = metadata(id);
    result.putAll(runResult(id));
    return result;
  }

  public Api.Page runs(String term, int page, int size) {
    Actor.require("ADMIN");
    return db.page(
        "SELECT id,kind,term_id,round_id,state,snapshot_hash,created_at,published_at FROM"
            + " cs3_planning_run"
            + (term == null ? "" : " WHERE term_id=?")
            + " ORDER BY created_at DESC,id",
        term == null ? List.of() : List.of(term),
        page,
        size);
  }

  private Map<String, Object> store(
      String kind,
      String term,
      String round,
      Map<String, Object> request,
      Object snapshot,
      Map<String, Object> result) {
    String id = uid(), digest = hash(snapshot);
    return tx.execute(
        st -> {
          db.update(
              "INSERT INTO"
                  + " cs3_planning_run(id,kind,term_id,round_id,state,snapshot_hash,input_json,result_json)"
                  + " VALUES(?,?,?,?,'PREVIEW',?,?,?)",
              id,
              kind,
              term,
              round,
              digest,
              serialize(Map.of("request", request, "snapshot", snapshot)),
              serialize(result));
          journal.audit("PLANNING_PREVIEW", id, kind + " " + digest.substring(0, 12), term);
          return run(id);
        });
  }

  record ScheduleSnapshot(
      String termId,
      long termVersion,
      long resourceVersion,
      Map<String, Long> sectionVersions,
      ScheduleSolver.Input model) {}

  private ScheduleSnapshot scheduleInput(Map<String, Object> request) {
    String term = Api.id(request, "termId");
    var termRow = db.one("SELECT state,version FROM cs2_term WHERE id=?", term);
    if ("ARCHIVED".equals(termRow.get("state"))) throw Api.conflict("TERM_ARCHIVED", "已归档学期不能排课");
    var registered = PlanningResources.read(db, term);
    long resourceVersion = lng(registered, "version");
    var savedRooms = byId(records(registered.get("rooms"), 160, "rooms"), "id");
    Map<String, ScheduleSolver.Room> rooms = new TreeMap<>();
    for (var r : records(request.getOrDefault("rooms", registered.get("rooms")), 160, "rooms")) {
      String room = Api.text(r, "id", 80);
      if (resourceVersion > 0 && !savedRooms.containsKey(room))
        throw Api.invalid("候选教室未登记：" + room);
      int capacity =
          resourceVersion > 0
              ? num(savedRooms.get(room), "capacity")
              : Api.integer(r, "capacity", 1, 10000);
      if (rooms.put(room, new ScheduleSolver.Room(room, capacity)) != null)
        throw Api.invalid("教室编号重复");
    }
    var demands = new TreeMap<String, ScheduleSolver.Demand>();
    Map<String, Long> versions = new TreeMap<>();
    for (var d : records(request.get("sections"), 80, "sections")) {
      String id = Api.id(d, "sectionId");
      var s = queries.section(id);
      if (!term.equals(s.get("termId")) || !"DRAFT".equals(s.get("state")))
        throw Api.conflict("DRAFT_REQUIRED", "排课只能使用本学期草稿教学班：" + id);
      if (!"PUBLISHED"
          .equals(
              db.one("SELECT state FROM cs2_offering WHERE id=?", s.get("offeringId"))
                  .get("state"))) throw Api.conflict("OFFERING_NOT_PUBLISHED", "请先发布课程开设");
      List<AllocationSolver.Slot> slots =
          records(d.get("slots"), 12, "slots").stream().map(this::slot).toList();
      if (slots.isEmpty() || new HashSet<>(slots).size() != slots.size())
        throw Api.invalid("每个教学班需提供1—12个不同候选时段");
      if (slots.stream().anyMatch(x -> !rooms.containsKey(x.room())))
        throw Api.invalid("候选时段引用未知教室");
      int locked =
          d.get("lockedSlot") == null ? -1 : Api.integer(d, "lockedSlot", 0, slots.size() - 1);
      var demand =
          new ScheduleSolver.Demand(id, str(s, "teacherId"), num(s, "capacity"), locked, slots);
      if (demands.put(id, demand) != null) throw Api.invalid("同一教学班不能重复排课");
      versions.put(id, lng(s, "version"));
    }
    if (rooms.isEmpty() || demands.isEmpty()) throw Api.invalid("需提供教室与草稿教学班");
    var unavailable = new LinkedHashSet<ScheduleSolver.Unavailable>();
    var blocks = new ArrayList<>(records(registered.get("unavailable"), 500, "unavailable"));
    blocks.addAll(records(request.getOrDefault("unavailable", List.of()), 300, "unavailable"));
    for (var u : blocks) {
      String teacher = Api.optional(u, "teacherId", 32), room = Api.optional(u, "room", 80);
      if (teacher.isEmpty() && room.isEmpty()) throw Api.invalid("禁排时段须指定教师或教室");
      if (!room.isEmpty() && !rooms.containsKey(room)) throw Api.invalid("禁排时段引用未知教室");
      if (!teacher.isEmpty()
          && db.count("SELECT COUNT(*) FROM cs2_teacher WHERE id=?", teacher) != 1)
        throw Api.invalid("禁排时段引用未知教师");
      var input = new HashMap<>(u);
      input.put("room", room.isEmpty() ? "*" : room);
      unavailable.add(new ScheduleSolver.Unavailable(teacher, room, slot(input)));
    }
    var existing =
        db
            .rows(
                "SELECT s.id AS"
                    + " section_id,s.teacher_id,m.room,m.weekday,m.start_period,m.end_period,m.start_week,m.end_week"
                    + " FROM cs2_section s JOIN cs2_offering o ON o.id=s.offering_id JOIN"
                    + " cs2_meeting m ON m.section_id=s.id WHERE o.term_id=? AND"
                    + " s.state='PUBLISHED' ORDER BY s.id,m.id",
                term)
            .stream()
            .map(m -> new ScheduleSolver.Booking(str(m, "sectionId"), str(m, "teacherId"), slot(m)))
            .toList();
    return new ScheduleSnapshot(
        term,
        lng(termRow, "version"),
        resourceVersion,
        versions,
        new ScheduleSolver.Input(
            List.copyOf(rooms.values()),
            List.copyOf(demands.values()),
            List.copyOf(unavailable),
            existing));
  }

  public Map<String, Object> schedulePreview(Map<String, Object> request) {
    Actor.require("ADMIN");
    if (!solves.tryAcquire()) throw new Api.Failure(429, "SOLVER_BUSY", "另一个规划正在求解");
    try {
      var snapshot = snapshotTx.execute(st -> scheduleInput(request));
      var result = ScheduleSolver.solve(snapshot.model);
      return store(
          "SCHEDULE",
          snapshot.termId,
          null,
          request,
          snapshot,
          Api.object(
              "solverStatus",
              result.solverStatus(),
              "assignments",
              result.assignments(),
              "resourceVersion",
              snapshot.resourceVersion,
              "resourceMode",
              snapshot.resourceVersion > 0 ? "REGISTERED" : "EXAMPLE",
              "metrics",
              Map.of(
                  "scheduled",
                  result.scheduled(),
                  "requested",
                  result.requested(),
                  "teacherGapPeriods",
                  ScheduleSolver.teacherGaps(snapshot.model, result.assignments())),
              "greedy",
              Map.of(
                  "scheduled",
                  result.greedyScheduled(),
                  "teacherGapPeriods",
                  result.greedyTeacherGaps()),
              "excluded",
              result.excluded(),
              "stages",
              result.stages()));
    } finally {
      solves.release();
    }
  }

  private void unchanged(String expected, Object snapshot) {
    if (!hash(snapshot).equals(expected)) throw Api.conflict("STALE_PREVIEW", "数据已变化，请重新预览后再发布");
  }

  private void publishAllocation(
      String id, String roundId, String expected, Map<String, Object> result) {
    var p = policy(roundId, true);
    if (!"FROZEN".equals(p.get("phase"))) throw Api.conflict("ROUND_PUBLISHED", "此轮志愿已发布或未封存");
    var r = round(roundId, true);
    var sections = new TreeSet<String>();
    db.rows("SELECT DISTINCT section_id FROM cs3_preference_choice WHERE round_id=?", roundId)
        .forEach(row -> sections.add(str(row, "sectionId")));
    // Grade revisions on existing enrollments can change prerequisite eligibility.
    db.rows(
            "SELECT DISTINCT e.section_id FROM cs2_enrollment e JOIN cs3_preference_form f ON"
                + " f.student_id=e.student_id WHERE f.round_id=? AND e.state='ENROLLED'",
            roundId)
        .forEach(row -> sections.add(str(row, "sectionId")));
    var students =
        db
            .rows(
                "SELECT student_id FROM cs3_preference_form WHERE round_id=? ORDER BY student_id",
                roundId)
            .stream()
            .map(row -> str(row, "studentId"))
            .toList();
    enrollment.lockForAllocation(sections, students, str(r, "termId"), num(r, "creditLimit"));
    unchanged(expected, allocationInput(roundId));
    for (var a : records(result.get("assignments"), 2000, "assignments")) {
      enrollment.applyAllocatedSeat(str(a, "studentId"), str(a, "sectionId"), roundId, id);
      db.update(
          "INSERT INTO cs3_allocation_award(run_id,student_id,section_id) VALUES(?,?,?)",
          id,
          a.get("studentId"),
          a.get("sectionId"));
    }
    db.update("UPDATE cs3_preference_round SET phase='PUBLISHED' WHERE round_id=?", roundId);
  }

  @Transactional(timeout = 180)
  public Map<String, Object> publish(String id, String expected, String key) {
    Actor.require("ADMIN");
    if (expected == null || !expected.matches("[0-9a-f]{64}"))
      throw Api.invalid("snapshotHash须为SHA-256摘要");
    return idem.run(
        "PLANNING_PUBLISH",
        key,
        Map.of("runId", id, "snapshotHash", expected),
        () -> {
          db.one("SELECT id FROM cs3_planning_run WHERE id=? FOR UPDATE", id);
          var meta = metadata(id);
          if (!expected.equals(meta.get("snapshotHash")))
            throw Api.conflict("STALE_PREVIEW", "提交的快照摘要不匹配");
          if ("PUBLISHED".equals(meta.get("state")))
            return Map.of("id", id, "state", "PUBLISHED", "snapshotHash", expected);
          var result = runResult(id);
          if (!"OPTIMAL".equals(result.get("solverStatus")))
            throw Api.conflict("OPTIMUM_UNPROVEN", "限时内未证实最优，不能正式发布");
          String term = str(meta, "termId");
          db.one("SELECT id FROM cs2_term WHERE id=? FOR UPDATE", term);
          if ("ALLOCATION".equals(meta.get("kind")))
            publishAllocation(id, str(meta, "roundId"), expected, result);
          else {
            var request = cast(data(runField(id, "input_json")).get("request"), Map.class);
            long savedResourceVersion =
                lng(
                    cast(data(runField(id, "input_json")).get("snapshot"), Map.class),
                    "resourceVersion");
            long currentResourceVersion = lng(PlanningResources.read(db, term), "version");
            if (savedResourceVersion != currentResourceVersion)
              throw Api.conflict("STALE_PREVIEW", "排课资源版本已变化，请重新预览");
            if (currentResourceVersion == 0)
              throw Api.conflict("RESOURCES_REQUIRED", "请先登记教室和教师禁排资源，重新预览后发布");
            ScheduleSnapshot snapshot = scheduleInput(request);
            unchanged(expected, snapshot);
            List<Map<String, Object>> assignments =
                records(result.get("assignments"), 80, "assignments");
            if (assignments.size() != snapshot.model.sections().size())
              throw Api.conflict("PARTIAL_SCHEDULE", "存在未排教学班，请补充教室或候选时段");
            for (var a : assignments) {
              var current = queries.section(str(a, "sectionId"));
              admin.save(
                  "sections",
                  str(a, "sectionId"),
                  Api.object(
                      "offeringId",
                      current.get("offeringId"),
                      "teacherId",
                      current.get("teacherId"),
                      "capacity",
                      current.get("capacity"),
                      "version",
                      current.get("version"),
                      "state",
                      "PUBLISHED",
                      "meetings",
                      List.of(a.get("slot"))));
            }
          }
          db.update(
              "UPDATE cs3_planning_run SET state='PUBLISHED',published_at=CURRENT_TIMESTAMP WHERE"
                  + " id=?",
              id);
          journal.audit(
              "PLANNING_PUBLISH", id, str(meta, "kind") + " " + expected.substring(0, 12), term);
          return Map.of("id", id, "state", "PUBLISHED", "snapshotHash", expected);
        });
  }
}
