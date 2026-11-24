package edu.ustb.coursev2;

import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.boot.*;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Owner-only explicit initialization. Serve/verify never run DDL or seed. */
@Component
public class Bootstrap implements ApplicationRunner {
  private final Environment env;
  private final Db db;
  private final DataSource ds;
  private final PasswordEncoder encoder;
  private final DatabaseConfig.Identity identity;
  private final TransactionTemplate tx;

  public Bootstrap(
      Environment env,
      Db db,
      DataSource ds,
      PasswordEncoder encoder,
      DatabaseConfig.Identity identity,
      PlatformTransactionManager manager) {
    this.env = env;
    this.db = db;
    this.ds = ds;
    this.encoder = encoder;
    this.identity = identity;
    this.tx = new TransactionTemplate(manager);
  }

  public static String schemaHash() {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(new ClassPathResource("db/schema.sql").getInputStream().readAllBytes()));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void run(ApplicationArguments ignored) throws Exception {
    String mode = env.getProperty("app.mode", "serve");
    if (!Set.of("serve", "init", "verify", "test", "migrate").contains(mode))
      throw new IllegalStateException("Unknown APP_MODE");
    if (mode.equals("init") || mode.equals("test")) {
      if (mode.equals("test") && !identity.surrogate())
        throw new IllegalStateException(
            "Automatic test init is H2 surrogate only; native tests initialize explicit owner CLI");
      if (!mode.equals("test")) confirmTarget();
      tx.executeWithoutResult(status -> initialize());
    }
    if (Set.of("init", "test", "migrate").contains(mode)) {
      if (mode.equals("migrate")) confirmTarget();
      tx.executeWithoutResult(status -> migrate());
    }
    verify();
  }

  private void initialize() {
    String admin = requiredPassword("app.bootstrap.admin-password"),
        teacher = requiredPassword("app.bootstrap.teacher-password"),
        student = requiredPassword("app.bootstrap.student-password");
    // openGauss reports lowercase public, H2 uppercase PUBLIC; check both via information schema.
    if (db.count(
            "SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_schema)='public' AND"
                + " LOWER(table_name) LIKE 'cs2_%'")
        > 0) throw new IllegalStateException("Existing cs2 tables refuse initialization");
    ResourceDatabasePopulator populator =
        new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql"));
    populator.setSqlScriptEncoding("UTF-8");
    populator.setContinueOnError(false);
    populator.execute(ds);
    tx.executeWithoutResult(
        status -> {
          db.update(
              "INSERT INTO cs2_schema_version(version,source_version,schema_sha256,installed_at)"
                  + " VALUES(1,'2.0.0',?,CURRENT_TIMESTAMP)",
              schemaHash());
          seed(admin, teacher, student);
        });
  }

  private void confirmTarget() {
    String database = identity.surrogate() ? "h2-demo"
        : db.jdbc.queryForObject("SELECT current_database()", String.class);
    if (!Objects.equals(database, env.getProperty("app.init.confirm"))
        || (!identity.surrogate() && !Set.of("course_app_v3", "course_app_v3_test",
            "course_app_v3_perf_test", "course_app_v3_restore").contains(database)))
      throw new IllegalStateException("Owner operation requires confirmation of the dedicated V3 database");
  }

  private static final List<String> MIGRATIONS = List.of(
      "db/v3-core.sql", "db/v3-planning.sql", "db/v3-academic.sql", "db/v3-demo-academic.sql");

  static String migrationHash() {
    try {
      MessageDigest hash = MessageDigest.getInstance("SHA-256");
      for (String path : MIGRATIONS) {
        hash.update(path.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        hash.update((byte) 0);
        try (var in = new ClassPathResource(path).getInputStream()) { hash.update(in.readAllBytes()); }
        hash.update((byte) 0);
      }
      return HexFormat.of().formatHex(hash.digest());
    } catch (Exception e) { throw new IllegalStateException("Cannot read V3 migration", e); }
  }

  private void migrate() {
    db.one("SELECT version FROM cs2_schema_version WHERE version=1 FOR UPDATE");
    verifyBase();
    if (db.count("SELECT COUNT(*) FROM cs2_schema_version WHERE version=3") == 0) {
      ResourceDatabasePopulator scripts = new ResourceDatabasePopulator();
      scripts.setSqlScriptEncoding("UTF-8");
      MIGRATIONS.forEach(path -> scripts.addScript(new ClassPathResource(path)));
      scripts.execute(ds);
      db.update("INSERT INTO cs2_schema_version(version,source_version,schema_sha256,installed_at)"
          + " VALUES(3,'3.0.0',?,CURRENT_TIMESTAMP)", migrationHash());
    }
    verify();
    String role = env.getProperty("app.init.runtime-role", "");
    if (!identity.surrogate()) {
      if (!"course_v3_app".equals(role)) throw new IllegalStateException("APP_RUNTIME_ROLE must be course_v3_app");
      grantRuntime();
    }
  }

  private void grantRuntime() {
    db.jdbc.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
    db.jdbc.execute("GRANT USAGE ON SCHEMA public TO course_v3_app");
    // Only objects declared by the shipped DDL receive permissions.
    var ddlObject = java.util.regex.Pattern.compile("(?im)^CREATE\\s+(TABLE|VIEW)\\s+(cs[23]_[a-z0-9_]+)\\b");
    for (String path : List.of("db/schema.sql", "db/v3-core.sql", "db/v3-planning.sql", "db/v3-academic.sql")) {
      try (var in = new ClassPathResource(path).getInputStream()) {
        var matches = ddlObject.matcher(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        while (matches.find()) {
          String name = matches.group(2);
          String privileges = switch (name) {
            case "cs2_schema_version" -> "SELECT";
            case "cs2_audit", "cs3_allocation_award" -> "SELECT,INSERT";
            case "cs3_preference_round", "cs3_preference_form", "cs3_planning_run",
                 "cs3_grade_review" -> "SELECT,INSERT,UPDATE";
            case "cs3_preference_choice" -> "SELECT,INSERT,DELETE";
            case "cs3_promotion_cursor", "cs3_curriculum_gate" -> "SELECT,UPDATE";
            default -> matches.group(1).equalsIgnoreCase("VIEW") ? "SELECT" : "SELECT,INSERT,UPDATE,DELETE";
          };
          db.jdbc.execute("REVOKE ALL PRIVILEGES ON TABLE " + name + " FROM course_v3_app");
          db.jdbc.execute("GRANT " + privileges + " ON TABLE " + name + " TO course_v3_app");
        }
      } catch (java.io.IOException e) { throw new IllegalStateException("Grant manifest unavailable", e); }
    }
  }

  private String requiredPassword(String key) {
    String p = env.getProperty(key, "");
    if (p.length() < 10 || p.length() > 128)
      throw new IllegalStateException(
          "Bootstrap passwords must be provided through environment (10..128 characters), no"
              + " built-in credentials");
    return p;
  }

  private void verifyBase() {
    Map<String, Object> row =
        db.one("SELECT version,source_version,schema_sha256 FROM cs2_schema_version WHERE version=1");
    if (Db.num(row, "version") != 1
        || !Db.str(row, "sourceVersion").equals("2.0.0")
        || !Db.str(row, "schemaSha256").equals(schemaHash()))
      throw new IllegalStateException(
          "Schema/source checksum mismatch; explicit reviewed migration required");
  }

  public void verify() {
    verifyBase();
    var latest = db.one("SELECT version,source_version,schema_sha256 FROM cs2_schema_version ORDER BY version DESC LIMIT 1");
    if (Db.num(latest, "version") != 3 || !"3.0.0".equals(Db.str(latest, "sourceVersion"))
        || !migrationHash().equals(Db.str(latest, "schemaSha256")))
      throw new IllegalStateException("V3 migration missing or checksum mismatch");
  }

  private void account(
      String username, String name, String role, String student, String teacher, String encoded) {
    db.update(
        "INSERT INTO cs2_user(id,username,password_hash,display_name,role,student_id,teacher_id)"
            + " VALUES(?,?,?,?,?,?,?)",
        Db.uid(),
        username,
        encoded,
        name,
        role,
        student,
        teacher);
  }

  private void seed(String adminPassword, String teacherPassword, String studentPassword) {
    String adminHash = encoder.encode(adminPassword),
        teacherHash = encoder.encode(teacherPassword),
        studentHash = encoder.encode(studentPassword);
    db.update("INSERT INTO cs2_term(id,name,state) VALUES('2026AUTUMN','2026—2027 秋季学期','ACTIVE')");
    db.update(
        "INSERT INTO cs2_term(id,name,state) VALUES('2026SPRING','2025—2026 春季学期','ARCHIVED')");
    Instant now = db.now();
    db.update(
        "INSERT INTO"
            + " cs2_round(id,term_id,name,state,starts_at,ends_at,withdrawal_ends_at,credit_limit)"
            + " VALUES('R2026A','2026AUTUMN','第一轮 · 自主选课','OPEN',?,?,?,24)",
        Timestamp.from(now.minus(Duration.ofDays(1))),
        Timestamp.from(now.plus(Duration.ofDays(60))),
        Timestamp.from(now.plus(Duration.ofDays(67))));
    String[] departments = {"计算机与通信工程学院", "自动化学院", "数理学院", "经济管理学院"};
    String[] surnames = {
      "林", "周", "陈", "赵", "李", "王", "张", "刘", "杨", "吴", "许", "孙", "郭", "何", "郑", "梁", "宋", "唐", "谢",
      "韩"
    };
    for (int i = 1; i <= 120; i++) {
      String id = "S" + (1000 + i),
          name =
              surnames[(i - 1) % surnames.length]
                  + new String[] {"知远", "书宁", "亦辰", "清和", "若溪", "景行"}[(i - 1) / 20]
                  + "（演示）";
      db.update(
          "INSERT INTO cs2_student(id,name,department,state) VALUES(?,?,?,'ACTIVE')",
          id,
          name,
          departments[(i - 1) % 4]);
      db.update(
          "INSERT INTO cs2_student_term(student_id,term_id,selected_credits,credit_limit)"
              + " VALUES(?,'2026AUTUMN',0,24)",
          id);
      account(
          i == 1 ? "student" : String.format("student%02d", i),
          name,
          "STUDENT",
          id,
          null,
          studentHash);
    }
    for (int i = 1; i <= 12; i++) {
      String id = "T" + (1000 + i), name = surnames[(i + 7) % 20] + "老师（演示）";
      db.update(
          "INSERT INTO cs2_teacher(id,name,department) VALUES(?,?,?)",
          id,
          name,
          departments[(i - 1) % 4]);
      account(
          i == 1 ? "teacher" : String.format("teacher%02d", i),
          name,
          "TEACHER",
          null,
          id,
          teacherHash);
    }
    account("admin", "教务管理员（演示）", "ADMIN", null, null, adminHash);
    String[] titles = {
      "数据库系统原理",
      "程序设计基础",
      "操作系统",
      "计算机网络",
      "数据结构",
      "算法设计与分析",
      "软件工程",
      "计算机组成原理",
      "人工智能导论",
      "机器学习基础",
      "信息安全",
      "编译原理",
      "离散数学",
      "高等数学",
      "线性代数",
      "概率论与数理统计",
      "数值分析",
      "最优化方法",
      "数字逻辑",
      "嵌入式系统",
      "自动控制原理",
      "信号与系统",
      "数字信号处理",
      "传感器原理",
      "工程经济学",
      "管理信息系统",
      "项目管理",
      "科技写作",
      "大学英语",
      "学术英语",
      "工程伦理",
      "创新创业基础",
      "计算机图形学",
      "人机交互设计",
      "分布式系统",
      "云计算基础"
    };
    for (int i = 1; i <= 36; i++) {
      String course = String.format("C%03d", i), offering = String.format("O%03d", i);
      int credits = 2 + (i % 3);
      db.update(
          "INSERT INTO cs2_course(id,title,department,description) VALUES(?,?,?,?)",
          course,
          titles[i - 1],
          departments[(i - 1) % 4],
          "围绕" + titles[i - 1] + "的核心概念、工程实践与案例分析开展学习。全部人员与记录为虚构教学演示数据。");
      db.update(
          "INSERT INTO cs2_offering(id,course_id,term_id,credits,state)"
              + " VALUES(?,?,'2026AUTUMN',?,'PUBLISHED')",
          offering,
          course,
          credits);
    }
    db.update(
        "INSERT INTO cs2_prerequisite(offering_id,course_id,min_grade) VALUES('O001','C002',60)");
    db.update(
        "INSERT INTO cs2_prerequisite(offering_id,course_id,min_grade) VALUES('O010','C015',60)");
    for (int i = 1; i <= 60; i++) {
      String section = String.format("SEC%03d", i),
          offering = String.format("O%03d", 1 + (i - 1) % 36),
          teacher = "T" + (1001 + (i - 1) % 12);
      int capacity = i <= 2 ? 2 : 30 + (i % 4) * 10;
      db.update(
          "INSERT INTO cs2_section(id,offering_id,teacher_id,capacity,state)"
              + " VALUES(?,?,?,?,'PUBLISHED')",
          section,
          offering,
          teacher,
          capacity);
      int weekday = 1 + (i - 1) % 5, start = 1 + 2 * ((i - 1) / 5 % 6);
      db.update(
          "INSERT INTO"
              + " cs2_meeting(id,section_id,weekday,start_period,end_period,start_week,end_week,room)"
              + " VALUES(?,?,?,?,?,1,16,?)",
          Db.uid(),
          section,
          weekday,
          start,
          start + 1,
          "教学楼" + (1 + (i - 1) % 4) + "-" + (101 + i));
    }
    // A real prior published prerequisite, not a bypass flag.
    db.update(
        "INSERT INTO cs2_offering(id,course_id,term_id,credits,state)"
            + " VALUES('OPAST02','C002','2026SPRING',4,'PUBLISHED')");
    db.update(
        "INSERT INTO"
            + " cs2_section(id,offering_id,teacher_id,capacity,enrolled_count,state,grade_state,grade_revision,last_published_at)"
            + " VALUES('SEC-PAST','OPAST02','T1001',40,20,'ARCHIVED','PUBLISHED',1,CURRENT_TIMESTAMP)");
    for (int i = 1; i <= 20; i++) {
      String student = "S" + (1000 + i);
      db.update(
          "INSERT INTO"
              + " cs2_enrollment(id,student_id,offering_id,section_id,state,grade,published_grade,created_at,updated_at)"
              + " VALUES(?,?,'OPAST02','SEC-PAST','ENROLLED',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
          Db.uid(),
          student,
          70 + i,
          70 + i);
      db.update(
          "INSERT INTO cs2_student_term(student_id,term_id,selected_credits,credit_limit)"
              + " VALUES(?,'2026SPRING',4,24)",
          student);
    }
    // Seed admitted records with deterministic IDs; counters are updated from the same source.
    for (int section = 1; section <= 60; section++) {
      int count = section <= 2 ? 2 : 3;
      for (int j = 0; j < count; j++) {
        int n = section <= 2 ? (section == 1 ? 2 + j : 4 + j) : 6 + (section * 3 + j) % 110;
        String student = "S" + (1000 + n),
            sid = String.format("SEC%03d", section),
            oid = String.format("O%03d", 1 + (section - 1) % 36);
        if (section == 37 || section == 10 || section == 46) continue;
        if (db.count(
                "SELECT COUNT(*) FROM cs2_enrollment WHERE student_id=? AND offering_id=?",
                student,
                oid)
            > 0) continue;
        db.update(
            "INSERT INTO"
                + " cs2_enrollment(id,student_id,offering_id,section_id,state,created_at,updated_at)"
                + " VALUES(?,?,?,?,'ENROLLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
            Db.uid(),
            student,
            oid,
            sid);
        db.update("UPDATE cs2_section SET enrolled_count=enrolled_count+1 WHERE id=?", sid);
        db.update(
            "UPDATE cs2_student_term SET selected_credits=selected_credits+(SELECT credits FROM"
                + " cs2_offering WHERE id=?) WHERE student_id=? AND term_id='2026AUTUMN'",
            oid,
            student);
      }
    }
    for (int i = 0; i < 2; i++) {
      String sid = i == 0 ? "SEC003" : "SEC006", oid = i == 0 ? "O003" : "O006";
      db.update(
          "INSERT INTO"
              + " cs2_enrollment(id,student_id,offering_id,section_id,state,created_at,updated_at)"
              + " VALUES(?,'S1001',?,?,'ENROLLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
          Db.uid(),
          oid,
          sid);
      db.update("UPDATE cs2_section SET enrolled_count=enrolled_count+1 WHERE id=?", sid);
      db.update(
          "UPDATE cs2_student_term SET selected_credits=selected_credits+(SELECT credits FROM"
              + " cs2_offering WHERE id=?) WHERE student_id='S1001' AND term_id='2026AUTUMN'",
          oid);
    }
    db.update(
        "INSERT INTO cs2_notification(id,user_id,title,message,created_at) SELECT"
            + " ?,id,'欢迎来到课序','这些是虚构演示数据。可体验选课、候补、课表和成绩。所有业务由真实数据库执行。',CURRENT_TIMESTAMP FROM"
            + " cs2_user WHERE username='student'",
        Db.uid());
    db.update(
        "INSERT INTO"
            + " cs2_audit(id,actor_name,action,entity_id,message,request_id,term_id,created_at)"
            + " VALUES(?,'初始化CLI','BOOTSTRAP','course_app_v2','创建虚构演示数据；未导入任何真实个人信息','bootstrap','2026AUTUMN',CURRENT_TIMESTAMP)",
        Db.uid());
  }
}
