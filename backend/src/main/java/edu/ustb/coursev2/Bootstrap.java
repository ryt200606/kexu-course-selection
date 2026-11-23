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
    if (!Set.of("serve", "init", "verify", "test").contains(mode))
      throw new IllegalStateException("Unknown APP_MODE");
    if (mode.equals("init") || mode.equals("test")) {
      if (mode.equals("test") && !identity.surrogate())
        throw new IllegalStateException(
            "Automatic test init is H2 surrogate only; native tests initialize explicit owner CLI");
      initialize(mode.equals("test"));
    }
    verify();
  }

  private void initialize(boolean test) throws Exception {
    String admin = requiredPassword("app.bootstrap.admin-password"),
        teacher = requiredPassword("app.bootstrap.teacher-password"),
        student = requiredPassword("app.bootstrap.student-password");
    String confirm = env.getProperty("app.init.confirm", "");
    if (!test) {
      String actual =
          identity.surrogate()
              ? "h2-demo"
              : db.jdbc.queryForObject("SELECT current_database()", String.class);
      if (!confirm.equals(actual)
          || (!identity.surrogate()
              && !Set.of(
                      "course_app_v2",
                      "course_app_v2_test",
                      "course_app_v2_perf",
                      "course_app_v2_perf_test")
                  .contains(actual)))
        throw new IllegalStateException(
            "Explicit init confirmation must equal actual dedicated database name");
    }
    try (Connection c = ds.getConnection();
        ResultSet tables =
            c.getMetaData().getTables(null, "PUBLIC", null, new String[] {"TABLE"})) {
      while (tables.next())
        if (tables.getString("TABLE_NAME").toLowerCase(Locale.ROOT).startsWith("cs2_"))
          throw new IllegalStateException(
              "cs2 schema already exists; initialization never resets data");
    }
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
    String runtime = env.getProperty("app.init.runtime-role", "");
    if (!runtime.isBlank()) {
      if (identity.surrogate() || !runtime.equals("course_v2_app"))
        throw new IllegalStateException(
            "Only explicit course_v2_app grant target is supported on openGauss");
      db.jdbc.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
      db.jdbc.execute("GRANT USAGE ON SCHEMA public TO course_v2_app");
      for (String table :
          List.of(
              "cs2_student",
              "cs2_teacher",
              "cs2_user",
              "cs2_term",
              "cs2_round",
              "cs2_course",
              "cs2_offering",
              "cs2_prerequisite",
              "cs2_section",
              "cs2_meeting",
              "cs2_student_term",
              "cs2_enrollment",
              "cs2_waitlist",
              "cs2_audit",
              "cs2_notification",
              "cs2_idempotency"))
        db.jdbc.execute(
            "GRANT SELECT,INSERT,UPDATE,DELETE ON TABLE " + table + " TO course_v2_app");
      db.jdbc.execute("GRANT SELECT ON TABLE cs2_schema_version TO course_v2_app");
      for (String view :
          List.of("cs2_v_student_timetable", "cs2_v_transcript", "cs2_v_term_summary"))
        db.jdbc.execute("GRANT SELECT ON TABLE " + view + " TO course_v2_app");
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

  public void verify() {
    Map<String, Object> row =
        db.one("SELECT version,source_version,schema_sha256 FROM cs2_schema_version");
    if (Db.num(row, "version") != 1
        || !Db.str(row, "sourceVersion").equals("2.0.0")
        || !Db.str(row, "schemaSha256").equals(schemaHash()))
      throw new IllegalStateException(
          "Schema/source checksum mismatch; explicit reviewed migration required");
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
