package edu.ustb.coursev2;

import static edu.ustb.coursev2.Api.*;
import static edu.ustb.coursev2.Db.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminService {
  private final Db db;
  private final QueryService queries;
  private final Journal journal;
  private final PasswordEncoder encoder;

  public AdminService(Db db, QueryService queries, Journal journal, PasswordEncoder encoder) {
    this.db = db;
    this.queries = queries;
    this.journal = journal;
    this.encoder = encoder;
  }

  private static final Map<String, String> TABLES =
      Map.of(
          "students",
          "cs2_student",
          "teachers",
          "cs2_teacher",
          "courses",
          "cs2_course",
          "terms",
          "cs2_term",
          "rounds",
          "cs2_round",
          "offerings",
          "cs2_offering",
          "sections",
          "cs2_section",
          "users",
          "cs2_user");

  private String table(String resource) {
    String t = TABLES.get(resource);
    if (t == null) throw missing();
    return t;
  }

  public Api.Page list(
      String resource, String term, String query, String state, int page, int size) {
    Actor.require("ADMIN");
    String sql;
    switch (resource) {
      case "students" -> sql = "SELECT id,name,department,state,version FROM cs2_student";
      case "teachers" -> sql = "SELECT id,name,department,version FROM cs2_teacher";
      case "courses" -> sql = "SELECT id,title,department,description,version FROM cs2_course";
      case "terms" -> sql = "SELECT id,name,state,version FROM cs2_term";
      case "rounds" ->
          sql =
              "SELECT"
                  + " id,term_id,name,state,starts_at,ends_at,withdrawal_ends_at,credit_limit,version"
                  + " FROM cs2_round";
      case "offerings" ->
          sql =
              "SELECT o.id,o.course_id,o.term_id,o.credits,o.state,o.version,c.title AS"
                  + " course_title,t.name AS term_name FROM cs2_offering o JOIN cs2_course c ON"
                  + " c.id=o.course_id JOIN cs2_term t ON t.id=o.term_id";
      case "sections" -> sql = QueryService.SECTIONS;
      case "users" ->
          sql =
              "SELECT id,username,display_name,role,student_id,teacher_id,enabled,version FROM"
                  + " cs2_user";
      default -> throw missing();
    }
    // Outer projection makes filters independent of joins and keeps identifiers strictly
    // allowlisted.
    sql = "SELECT * FROM (" + sql + ") listed WHERE 1=1";
    List<Object> args = new ArrayList<>();
    if (term != null
        && !term.isBlank()
        && Set.of("rounds", "offerings", "sections").contains(resource)) {
      sql += " AND term_id=?";
      args.add(term);
    }
    if (state != null
        && !state.isBlank()
        && Set.of("students", "terms", "rounds", "offerings", "sections").contains(resource)) {
      sql += " AND state=?";
      args.add(state);
    }
    if (query != null && !query.isBlank()) {
      String title =
          switch (resource) {
            case "courses" -> "title";
            case "sections", "offerings" -> "course_title";
            case "users" -> "display_name";
            default -> "name";
          };
      sql += " AND (LOWER(id) LIKE ? OR LOWER(" + title + ") LIKE ?)";
      String q = "%" + query.toLowerCase(Locale.ROOT) + "%";
      args.add(q);
      args.add(q);
    }
    Api.Page result = db.page(sql + " ORDER BY id", args, page, size);
    decorate(resource, result.items());
    return result;
  }

  public Map<String, Object> detail(String resource, String id) {
    Actor.require("ADMIN");
    if (resource.equals("sections")) return queries.section(id);
    String fields =
        resource.equals("users")
            ? "id,username,display_name,role,student_id,teacher_id,enabled,version"
            : "*";
    Map<String, Object> result =
        db.one("SELECT " + fields + " FROM " + table(resource) + " WHERE id=?", id);
    decorate(resource, List.of(result));
    return result;
  }

  private void decorate(String resource, List<Map<String, Object>> rows) {
    if (resource.equals("sections")) queries.decorateSections(rows);
    if (resource.equals("offerings"))
      for (Map<String, Object> r : rows)
        r.put(
            "prerequisites",
            db.rows(
                "SELECT p.course_id,p.min_grade,c.title AS course_title FROM cs2_prerequisite p"
                    + " JOIN cs2_course c ON c.id=p.course_id WHERE offering_id=? ORDER BY"
                    + " p.course_id",
                r.get("id")));
    if (resource.equals("users"))
      for (Map<String, Object> r : rows) r.put("enabled", num(r, "enabled") == 1);
  }

  private String state(Map<String, Object> b, String... allowed) {
    String s = text(b, "state", 20);
    if (Arrays.stream(allowed).noneMatch(s::equals)) throw invalid("state不符合要求");
    return s;
  }

  private Map<String, Object> existing(String resource, String id, Map<String, Object> b) {
    Map<String, Object> old =
        db.one("SELECT * FROM " + table(resource) + " WHERE id=? FOR UPDATE", id);
    if (lng(old, "version") != version(b)) throw conflict("VERSION_CONFLICT", "数据已被修改，请刷新重试");
    return old;
  }

  @Transactional(timeout = 10)
  public Map<String, Object> save(String resource, String id, Map<String, Object> body) {
    Actor.require("ADMIN");
    boolean create = id == null;
    if (create) id = resource.equals("users") ? uid() : Api.id(body, "id");
    String term = null;
    switch (resource) {
      case "students" -> {
        String name = text(body, "name", 100),
            dept = text(body, "department", 80),
            s = state(body, "ACTIVE", "SUSPENDED");
        if (create)
          db.update(
              "INSERT INTO cs2_student(id,name,department,state) VALUES(?,?,?,?)",
              id,
              name,
              dept,
              s);
        else {
          existing(resource, id, body);
          db.update(
              "UPDATE cs2_student SET name=?,department=?,state=?,version=version+1 WHERE id=?",
              name,
              dept,
              s,
              id);
        }
      }
      case "teachers" -> {
        String name = text(body, "name", 100), dept = text(body, "department", 80);
        if (create)
          db.update("INSERT INTO cs2_teacher(id,name,department) VALUES(?,?,?)", id, name, dept);
        else {
          existing(resource, id, body);
          db.update(
              "UPDATE cs2_teacher SET name=?,department=?,version=version+1 WHERE id=?",
              name,
              dept,
              id);
        }
      }
      case "courses" -> {
        String title = text(body, "title", 120),
            dept = text(body, "department", 80),
            description = optional(body, "description", 2000);
        if (description.isEmpty()) description = "未填写课程说明";
        if (create)
          db.update(
              "INSERT INTO cs2_course(id,title,department,description) VALUES(?,?,?,?)",
              id,
              title,
              dept,
              description);
        else {
          existing(resource, id, body);
          db.update(
              "UPDATE cs2_course SET title=?,department=?,description=?,version=version+1 WHERE"
                  + " id=?",
              title,
              dept,
              description,
              id);
        }
      }
      case "terms" -> {
        String name = text(body, "name", 100), s = state(body, "PLANNING", "ACTIVE", "ARCHIVED");
        if (create) db.update("INSERT INTO cs2_term(id,name,state) VALUES(?,?,?)", id, name, s);
        else {
          existing(resource, id, body);
          if (!s.equals("ACTIVE")
              && db.count("SELECT COUNT(*) FROM cs2_round WHERE term_id=? AND state='OPEN'", id)
                  > 0) throw conflict("ROUND_STILL_OPEN", "请先关闭该学期的开放轮次");
          db.update("UPDATE cs2_term SET name=?,state=?,version=version+1 WHERE id=?", name, s, id);
        }
        term = id;
      }
      case "rounds" -> {
        saveRound(id, body, create);
        term = Api.id(body, "termId");
      }
      case "offerings" -> {
        saveOffering(id, body, create);
        term = Api.id(body, "termId");
      }
      case "sections" -> {
        saveSection(id, body, create);
        term = str(queries.section(id), "termId");
      }
      case "users" -> saveUser(id, body, create);
      default -> throw missing();
    }
    journal.audit(
        create
            ? "ADMIN_CREATE_" + resource.toUpperCase(Locale.ROOT)
            : "ADMIN_UPDATE_" + resource.toUpperCase(Locale.ROOT),
        id,
        "教务维护" + resource,
        term);
    return detail(resource, id);
  }

  private void saveRound(String id, Map<String, Object> b, boolean create) {
    String term = Api.id(b, "termId"),
        name = text(b, "name", 100),
        state = state(b, "DRAFT", "OPEN", "CLOSED");
    int limit = integer(b, "creditLimit", 1, 60);
    Timestamp starts = timestamp(b.get("startsAt")),
        ends = timestamp(b.get("endsAt")),
        withdrawal = timestamp(b.get("withdrawalEndsAt"));
    if (!starts.before(ends) || ends.after(withdrawal)) throw invalid("轮次时间顺序不正确");
    db.one("SELECT id FROM cs2_term WHERE id=? FOR UPDATE", term);
    Map<String, Object> old = create ? null : existing("rounds", id, b);
    if (old != null && !str(old, "termId").equals(term))
      throw conflict("FROZEN_RESOURCE", "已有轮次不能移动到另一学期");
    if (old != null && !str(old, "state").equals("DRAFT")) {
      if (!str(old, "startsAt").equals(starts.toInstant().toString())
          || !str(old, "endsAt").equals(ends.toInstant().toString())
          || !str(old, "withdrawalEndsAt").equals(withdrawal.toInstant().toString())
          || num(old, "creditLimit") != limit)
        throw conflict("FROZEN_RESOURCE", "已开放轮次的时间和学分规则已冻结");
      if (str(old, "state").equals("CLOSED") && !state.equals("CLOSED"))
        throw conflict("FROZEN_RESOURCE", "关闭的轮次不能重开，请建立新轮次");
    }
    if (db.count(
            "SELECT COUNT(*) FROM cs2_round WHERE term_id=? AND id<>? AND credit_limit<>?",
            term,
            id,
            limit)
        > 0) throw conflict("TERM_CREDIT_RULE_FROZEN", "同一学期各轮次须使用一致学分上限");
    if (state.equals("OPEN")
        && db.count(
                "SELECT COUNT(*) FROM cs2_round WHERE term_id=? AND state='OPEN' AND id<>?",
                term,
                id)
            > 0) throw conflict("ROUND_ALREADY_OPEN", "本学期已有开放轮次");
    if (state.equals("OPEN")
        && !str(db.one("SELECT state FROM cs2_term WHERE id=?", term), "state").equals("ACTIVE"))
      throw conflict("TERM_NOT_ACTIVE", "请先启用学期");
    if (create)
      db.update(
          "INSERT INTO"
              + " cs2_round(id,term_id,name,state,starts_at,ends_at,withdrawal_ends_at,credit_limit)"
              + " VALUES(?,?,?,?,?,?,?,?)",
          id,
          term,
          name,
          state,
          starts,
          ends,
          withdrawal,
          limit);
    else
      db.update(
          "UPDATE cs2_round SET"
              + " name=?,state=?,starts_at=?,ends_at=?,withdrawal_ends_at=?,credit_limit=?,version=version+1"
              + " WHERE id=?",
          name,
          state,
          starts,
          ends,
          withdrawal,
          limit,
          id);
    if (state.equals("CLOSED"))
      db.update(
          "UPDATE cs2_section SET promotion_pending=1 WHERE id IN(SELECT section_id FROM"
              + " cs2_waitlist WHERE round_id=? AND state='WAITING')",
          id);
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> listBody(Map<String, Object> b, String key, int maximum) {
    Object raw = b.get(key);
    if (!(raw instanceof List<?> list) || list.size() > maximum) throw invalid(key + "必须是限定长度数组");
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?>)) throw invalid(key + "条目格式不正确");
      rows.add((Map<String, Object>) item);
    }
    return rows;
  }

  private void saveOffering(String id, Map<String, Object> b, boolean create) {
    String course = Api.id(b, "courseId"),
        term = Api.id(b, "termId"),
        state = state(b, "DRAFT", "PUBLISHED");
    int credits = integer(b, "credits", 1, 10);
    List<Map<String, Object>> ps = listBody(b, "prerequisites", 20);
    db.one("SELECT id FROM cs2_term WHERE id=? FOR UPDATE", term);
    Map<String, Object> old = create ? null : existing("offerings", id, b);
    if (old != null && str(old, "state").equals("PUBLISHED")) {
      if (!str(old, "courseId").equals(course)
          || !str(old, "termId").equals(term)
          || num(old, "credits") != credits
          || !state.equals("PUBLISHED")) throw conflict("FROZEN_RESOURCE", "已发布开设的学分和课程学期不能修改");
      List<Map<String, Object>> existing =
          db.rows(
              "SELECT course_id,min_grade FROM cs2_prerequisite WHERE offering_id=? ORDER BY"
                  + " course_id",
              id);
      Map<String, String> expected = new TreeMap<>(), actual = new TreeMap<>();
      for (Map<String, Object> p : ps)
        expected.put(Api.id(p, "courseId"), gradeThreshold(p).stripTrailingZeros().toPlainString());
      for (Map<String, Object> p : existing)
        actual.put(
            str(p, "courseId"),
            new BigDecimal(p.get("minGrade").toString()).stripTrailingZeros().toPlainString());
      if (!expected.equals(actual)) throw conflict("FROZEN_RESOURCE", "已发布开设的先修规则不能修改");
      return;
    }
    Set<String> unique = new HashSet<>();
    for (Map<String, Object> p : ps) {
      String required = Api.id(p, "courseId");
      gradeThreshold(p);
      if (required.equals(course) || !unique.add(required)) throw invalid("先修课程不能自身引用或重复");
      db.one("SELECT id FROM cs2_course WHERE id=?", required);
    }
    if (create)
      db.update(
          "INSERT INTO cs2_offering(id,course_id,term_id,credits,state) VALUES(?,?,?,?,?)",
          id,
          course,
          term,
          credits,
          state);
    else
      db.update(
          "UPDATE cs2_offering SET course_id=?,term_id=?,credits=?,state=?,version=version+1 WHERE"
              + " id=?",
          course,
          term,
          credits,
          state,
          id);
    db.update("DELETE FROM cs2_prerequisite WHERE offering_id=?", id);
    for (Map<String, Object> p : ps)
      db.update(
          "INSERT INTO cs2_prerequisite(offering_id,course_id,min_grade) VALUES(?,?,?)",
          id,
          p.get("courseId"),
          gradeThreshold(p));
    Map<String, List<String>> graph = new HashMap<>();
    for (Map<String, Object> p :
        db.rows(
            "SELECT o.course_id AS from_id,p.course_id AS to_id FROM cs2_prerequisite p JOIN"
                + " cs2_offering o ON o.id=p.offering_id WHERE o.term_id=?",
            term))
      graph.computeIfAbsent(str(p, "fromId"), x -> new ArrayList<>()).add(str(p, "toId"));
    for (String node : graph.keySet())
      if (cycle(node, graph, new HashSet<>(), new HashSet<>()))
        throw conflict("PREREQUISITE_CYCLE", "先修课程形成循环，无法发布");
  }

  private boolean cycle(
      String id, Map<String, List<String>> graph, Set<String> visited, Set<String> active) {
    if (active.contains(id)) return true;
    if (!visited.add(id)) return false;
    active.add(id);
    for (String next : graph.getOrDefault(id, List.of()))
      if (cycle(next, graph, visited, active)) return true;
    active.remove(id);
    return false;
  }

  private BigDecimal gradeThreshold(Map<String, Object> p) {
    try {
      BigDecimal d = new BigDecimal(Objects.toString(p.getOrDefault("minGrade", 60)));
      if (d.compareTo(BigDecimal.ZERO) < 0
          || d.compareTo(new BigDecimal("100")) > 0
          || d.scale() > 2) throw invalid("先修成绩范围不正确");
      return d;
    } catch (NumberFormatException ex) {
      throw invalid("先修成绩格式不正确");
    }
  }

  private void saveSection(String id, Map<String, Object> b, boolean create) {
    String offering = Api.id(b, "offeringId"),
        teacher = Api.id(b, "teacherId"),
        state = state(b, "DRAFT", "PUBLISHED", "ARCHIVED");
    int capacity = integer(b, "capacity", 1, 1000);
    List<Map<String, Object>> meetings = listBody(b, "meetings", 12);
    Map<String, Object> o = db.one("SELECT term_id,state FROM cs2_offering WHERE id=?", offering);
    db.one("SELECT id FROM cs2_term WHERE id=? FOR UPDATE", o.get("termId"));
    Map<String, Object> old = create ? null : existing("sections", id, b);
    for (Map<String, Object> m : meetings) {
      integer(m, "weekday", 1, 7);
      int start = integer(m, "startPeriod", 1, 14),
          end = integer(m, "endPeriod", 1, 14),
          sw = integer(m, "startWeek", 1, 30),
          ew = integer(m, "endWeek", 1, 30);
      text(m, "room", 80);
      if (start > end || sw > ew) throw invalid("时段或周次顺序不正确");
    }
    if (old != null && num(old, "enrolledCount") > capacity)
      throw conflict("CAPACITY_TOO_SMALL", "容量不能小于当前人数");
    boolean frozen = old != null && !str(old, "state").equals("DRAFT");
    if (frozen) {
      if (!str(old, "offeringId").equals(offering) || !str(old, "teacherId").equals(teacher))
        throw conflict("FROZEN_RESOURCE", "已发布教学班的教师/开设不可修改");
      List<Map<String, Object>> oldMeetings = queries.meetings(id);
      if (!canonicalMeetings(oldMeetings).equals(canonicalMeetings(meetings)))
        throw conflict("FROZEN_RESOURCE", "已发布课表不可修改");
      if (state.equals("DRAFT")) throw conflict("FROZEN_RESOURCE", "已发布教学班不能改回草稿");
    }
    if (state.equals("PUBLISHED")) {
      if (!str(o, "state").equals("PUBLISHED"))
        throw conflict("OFFERING_NOT_PUBLISHED", "请先发布课程开设");
      if (meetings.isEmpty()) throw invalid("发布教学班前至少配置一个上课时段");
      checkSchedule(id, offering, teacher, meetings, str(o, "termId"));
    }
    if (state.equals("ARCHIVED")
        && old != null
        && num(old, "enrolledCount") > 0
        && num(old, "gradeRevision") == 0) throw conflict("HAS_ENROLLMENTS", "有在读学生且尚未发布成绩，不能归档");
    if (create)
      db.update(
          "INSERT INTO cs2_section(id,offering_id,teacher_id,capacity,state) VALUES(?,?,?,?,?)",
          id,
          offering,
          teacher,
          capacity,
          state);
    else
      db.update(
          "UPDATE cs2_section SET offering_id=?,teacher_id=?,capacity=?,state=?,version=version+1"
              + " WHERE id=?",
          offering,
          teacher,
          capacity,
          state,
          id);
    if (!frozen) {
      db.update("DELETE FROM cs2_meeting WHERE section_id=?", id);
      for (Map<String, Object> m : meetings)
        db.update(
            "INSERT INTO"
                + " cs2_meeting(id,section_id,weekday,start_period,end_period,start_week,end_week,room)"
                + " VALUES(?,?,?,?,?,?,?,?)",
            uid(),
            id,
            m.get("weekday"),
            m.get("startPeriod"),
            m.get("endPeriod"),
            m.get("startWeek"),
            m.get("endWeek"),
            m.get("room"));
    }
    if (old != null && capacity > num(old, "capacity"))
      db.update("UPDATE cs2_section SET promotion_pending=1 WHERE id=?", id);
  }

  private List<String> canonicalMeetings(List<Map<String, Object>> ms) {
    return ms.stream()
        .map(
            m ->
                m.get("weekday")
                    + "/"
                    + m.get("startPeriod")
                    + "/"
                    + m.get("endPeriod")
                    + "/"
                    + m.get("startWeek")
                    + "/"
                    + m.get("endWeek")
                    + "/"
                    + m.get("room"))
        .sorted()
        .toList();
  }

  private void checkSchedule(
      String id, String offering, String teacher, List<Map<String, Object>> ms, String term) {
    for (Map<String, Object> m : ms) {
      if (db.count(
              "SELECT COUNT(*) FROM cs2_meeting x JOIN cs2_section s ON s.id=x.section_id JOIN"
                  + " cs2_offering o ON o.id=s.offering_id WHERE o.term_id=? AND"
                  + " s.state='PUBLISHED' AND s.id<>? AND (s.teacher_id=? OR x.room=?) AND"
                  + " x.weekday=? AND x.start_period<=? AND x.end_period>=? AND x.start_week<=? AND"
                  + " x.end_week>=?",
              term,
              id,
              teacher,
              m.get("room"),
              m.get("weekday"),
              m.get("endPeriod"),
              m.get("startPeriod"),
              m.get("endWeek"),
              m.get("startWeek"))
          > 0) throw conflict("TEACHING_SCHEDULE_CONFLICT", "教师或教室与已发布教学班时段冲突");
    }
  }

  private void saveUser(String id, Map<String, Object> b, boolean create) {
    String name = text(b, "displayName", 100);
    if (create) {
      String username = text(b, "username", 80), role = text(b, "role", 16);
      if (!username.matches("[A-Za-z0-9_.-]{3,80}")
          || !Set.of("ADMIN", "TEACHER", "STUDENT").contains(role)) throw invalid("账号或角色不正确");
      String password = password(b);
      String student = role.equals("STUDENT") ? Api.id(b, "studentId") : null,
          teacher = role.equals("TEACHER") ? Api.id(b, "teacherId") : null;
      db.update(
          "INSERT INTO cs2_user(id,username,password_hash,display_name,role,student_id,teacher_id)"
              + " VALUES(?,?,?,?,?,?,?)",
          id,
          username,
          encoder.encode(password),
          name,
          role,
          student,
          teacher);
    } else {
      existing("users", id, b);
      Object enabled = b.get("enabled");
      if (!(enabled instanceof Boolean)) throw invalid("enabled必须是布尔值");
      if (id.equals(Actor.current().id()) && !Boolean.TRUE.equals(enabled))
        throw conflict("SELF_DISABLE", "不能停用当前登录账号");
      String pass = optional(b, "password", 128);
      if (!pass.isEmpty()) {
        password(b);
        db.update(
            "UPDATE cs2_user SET display_name=?,enabled=?,password_hash=?,version=version+1 WHERE"
                + " id=?",
            name,
            Boolean.TRUE.equals(enabled) ? 1 : 0,
            encoder.encode(pass),
            id);
      } else
        db.update(
            "UPDATE cs2_user SET display_name=?,enabled=?,version=version+1 WHERE id=?",
            name,
            Boolean.TRUE.equals(enabled) ? 1 : 0,
            id);
    }
  }

  private String password(Map<String, Object> b) {
    String p = text(b, "password", 128);
    if (p.length() < 10) throw invalid("密码至少10位");
    return p;
  }

  @Transactional(timeout = 10)
  public Map<String, Object> delete(String resource, String id) {
    Actor.require("ADMIN");
    if (resource.equals("users")) throw conflict("USE_DISABLE", "账号请停用，不可删除历史身份");
    String table = table(resource);
    db.one("SELECT id FROM " + table + " WHERE id=? FOR UPDATE", id);
    if (resource.equals("offerings")) {
      if (str(db.one("SELECT state FROM cs2_offering WHERE id=?", id), "state").equals("PUBLISHED"))
        throw conflict("FROZEN_RESOURCE", "已发布开设不能删除");
      db.update("DELETE FROM cs2_prerequisite WHERE offering_id=?", id);
    }
    if (resource.equals("sections")) {
      if (db.count("SELECT COUNT(*) FROM cs2_enrollment WHERE section_id=?", id) > 0
          || db.count("SELECT COUNT(*) FROM cs2_waitlist WHERE section_id=?", id) > 0)
        throw conflict("HAS_HISTORY", "存在选课或候补历史，不可删除");
      db.update("DELETE FROM cs2_meeting WHERE section_id=?", id);
    }
    db.update("DELETE FROM " + table + " WHERE id=?", id);
    journal.audit("ADMIN_DELETE_" + resource.toUpperCase(Locale.ROOT), id, "删除未使用记录", null);
    return Map.of("status", "OK");
  }

  public Api.Page audit(String query, String term, int page, int size) {
    Actor.require("ADMIN");
    String sql =
        "SELECT id,actor_id,actor_name,action,entity_id,message,request_id,term_id,created_at FROM"
            + " cs2_audit WHERE 1=1";
    List<Object> a = new ArrayList<>();
    if (query != null && !query.isBlank()) {
      sql +=
          " AND (LOWER(action) LIKE ? OR LOWER(message) LIKE ? OR LOWER(actor_name) LIKE ? OR"
              + " LOWER(entity_id) LIKE ?)";
      String q = "%" + query.toLowerCase(Locale.ROOT) + "%";
      a.add(q);
      a.add(q);
      a.add(q);
      a.add(q);
    }
    if (term != null && !term.isBlank()) {
      sql += " AND term_id=?";
      a.add(term);
    }
    return db.page(sql + " ORDER BY created_at DESC,id", a, page, size);
  }
}
