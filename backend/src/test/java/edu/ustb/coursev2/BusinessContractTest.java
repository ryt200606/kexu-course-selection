package edu.ustb.coursev2;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The identical service/MockHTTP contract runs on explicit native *_test, or H2 SURROGATE. */
@SpringBootTest(
    properties = {
      "app.mode=${TEST_APP_MODE:test}",
      "app.database.type=${TEST_DB_TYPE:h2}",
      "app.database.url=${TEST_DB_URL:jdbc:h2:mem:course_v2_contract;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=4000}",
      "app.database.user=${TEST_DB_USER:sa}",
      "app.database.password=${TEST_DB_PASSWORD:}",
      "app.bootstrap.admin-password=Test-only-Admin-2026",
      "app.bootstrap.teacher-password=Test-only-Teacher-2026",
      "app.bootstrap.student-password=Test-only-Student-2026",
      "app.promotions.enabled=false"
    })
@AutoConfigureMockMvc
@Timeout(25)
class BusinessContractTest {
  @Autowired EnrollmentService enrollment;
  @Autowired QueryService queries;
  @Autowired GradeService grades;
  @Autowired AdminService admin;
  @Autowired Bootstrap bootstrap;
  @Autowired PasswordEncoder encoder;
  @Autowired MockMvc http;
  @Autowired ObjectMapper json;
  @Autowired Environment env;
  @Autowired PlatformTransactionManager manager;
  @SpyBean Db db;
  Actor studentA, studentB, teacher, administrator;
  static final String PASSWORD = "Test-fixture-only-2026";
  static String encoded;

  @BeforeEach
  void isolatedDedicatedFixture() {
    String url = env.getRequiredProperty("app.database.url");
    assertThat(url.startsWith("jdbc:h2:") || url.matches(".*\\/course_app_v2_test(?:\\?.*)?$"))
        .as("No test cleanup in application/teaching/performance database")
        .isTrue();
    reset(db);
    for (String table :
        List.of(
            "cs2_idempotency",
            "cs2_notification",
            "cs2_audit",
            "cs2_waitlist",
            "cs2_enrollment",
            "cs2_student_term",
            "cs2_meeting",
            "cs2_section",
            "cs2_prerequisite",
            "cs2_offering",
            "cs2_round",
            "cs2_course",
            "cs2_term",
            "cs2_user",
            "cs2_teacher",
            "cs2_student")) db.update("DELETE FROM " + table);
    if (encoded == null) encoded = encoder.encode(PASSWORD);
    db.update("INSERT INTO cs2_term(id,name,state) VALUES('TERM','测试学期','ACTIVE')");
    Instant now = Instant.now();
    db.update(
        "INSERT INTO"
            + " cs2_round(id,term_id,name,state,starts_at,ends_at,withdrawal_ends_at,credit_limit)"
            + " VALUES('ROUND','TERM','测试轮次','OPEN',?,?,?,5)",
        Timestamp.from(now.minusSeconds(60)),
        Timestamp.from(now.plusSeconds(3600)),
        Timestamp.from(now.plusSeconds(7200)));
    db.update("INSERT INTO cs2_teacher(id,name,department) VALUES('TEACHER','测试教师','测试学院')");
    db.update("INSERT INTO cs2_teacher(id,name,department) VALUES('OTHER','其他教师','测试学院')");
    for (String student : List.of("A", "B", "C")) createStudent(student);
    teacher = account("ut", "teacher", "TEACHER", null, "TEACHER");
    administrator = account("ua", "admin", "ADMIN", null, null);
    studentA = account("usA", "studentA", "STUDENT", "A", null);
    studentB = account("usB", "studentB", "STUDENT", "B", null);
    for (int i = 1; i <= 3; i++) {
      db.update(
          "INSERT INTO cs2_course(id,title,department,description) VALUES(?,?,?,?)",
          "COURSE" + i,
          "课程" + i,
          "测试学院",
          "虚构测试");
      db.update(
          "INSERT INTO cs2_offering(id,course_id,term_id,credits,state)"
              + " VALUES(?,?,'TERM',3,'PUBLISHED')",
          "OFF" + i,
          "COURSE" + i);
      db.update(
          "INSERT INTO cs2_section(id,offering_id,teacher_id,capacity,state)"
              + " VALUES(?,?,'TEACHER',1,'PUBLISHED')",
          "SEC" + i,
          "OFF" + i);
      db.update(
          "INSERT INTO"
              + " cs2_meeting(id,section_id,weekday,start_period,end_period,start_week,end_week,room)"
              + " VALUES(?,?,?,1,2,1,16,?)",
          Db.uid(),
          "SEC" + i,
          i,
          "ROOM" + i);
    }
    SecurityContextHolder.clearContext();
  }

  @AfterEach
  void cleanAuth() {
    reset(db);
    SecurityContextHolder.clearContext();
  }

  void createStudent(String id) {
    db.update(
        "INSERT INTO cs2_student(id,name,department,state) VALUES(?,?,?,'ACTIVE')",
        id,
        "测试学生" + id,
        "测试学院");
    db.update(
        "INSERT INTO cs2_student_term(student_id,term_id,selected_credits,credit_limit)"
            + " VALUES(?,'TERM',0,5)",
        id);
  }

  Actor account(String id, String username, String role, String student, String teacher) {
    db.update(
        "INSERT INTO cs2_user(id,username,password_hash,display_name,role,student_id,teacher_id)"
            + " VALUES(?,?,?,?,?,?,?)",
        id,
        username,
        encoded,
        username,
        role,
        student,
        teacher);
    return new Actor(id, username, encoded, username, role, student, teacher, true);
  }

  <T> T as(Actor actor, Supplier<T> task) {
    var previous = SecurityContextHolder.getContext();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            actor, actor.password(), actor.getAuthorities()));
    SecurityContextHolder.setContext(context);
    try {
      return task.get();
    } finally {
      SecurityContextHolder.setContext(previous);
    }
  }

  void expectCode(String code, Runnable task) {
    assertThatThrownBy(task::run)
        .isInstanceOfSatisfying(Api.Failure.class, e -> assertThat(e.code).isEqualTo(code));
  }

  String key() {
    return "test-" + Db.uid();
  }

  long count(String section) {
    return db.count("SELECT enrolled_count FROM cs2_section WHERE id=?", section);
  }

  long credits(String student) {
    return db.count(
        "SELECT selected_credits FROM cs2_student_term WHERE student_id=? AND term_id='TERM'",
        student);
  }

  @Test
  void authenticationUsesCsrfSessionRotationAndRoleChecks() throws Exception {
    var tokenResponse =
        http.perform(get("/api/v2/auth/csrf")).andExpect(status().isOk()).andReturn();
    MockHttpSession session = (MockHttpSession) tokenResponse.getRequest().getSession();
    String before = session.getId();
    String token =
        json.readTree(tokenResponse.getResponse().getContentAsString()).path("token").asText();
    http.perform(
            post("/api/v2/auth/login")
                .session(session)
                .header("X-CSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"studentA\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("STUDENT"));
    assertThat(session.getId()).isNotEqualTo(before);
    http.perform(get("/api/v2/auth/me").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.studentId").value("A"));
    http.perform(get("/api/v2/admin/students").session(session)).andExpect(status().isForbidden());
    http.perform(
            post("/api/v2/student/enrollments")
                .session(session)
                .header("X-CSRF-TOKEN", token)
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sectionId\":\"SEC1\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
  }

  @Test
  void anonymousAndMissingCsrfDoNotWrite() throws Exception {
    http.perform(get("/api/v2/student/summary"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.requestId").isString());
    http.perform(
            post("/api/v2/student/enrollments")
                .with(user(studentA))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sectionId\":\"SEC1\"}"))
        .andExpect(status().isForbidden());
    assertThat(count("SEC1")).isZero();
  }

  @Test
  void enrollmentIsAtomicAndSequentialIdempotencyReplaysOriginal() {
    String key = key();
    Map<String, Object> first = as(studentA, () -> enrollment.enroll("SEC1", key));
    Map<String, Object> repeat = as(studentA, () -> enrollment.enroll("SEC1", key));
    assertThat(repeat.get("id")).isEqualTo(first.get("id"));
    assertThat(count("SEC1")).isOne();
    assertThat(credits("A")).isEqualTo(3);
    expectCode("IDEMPOTENCY_CONFLICT", () -> as(studentA, () -> enrollment.enroll("SEC2", key)));
    assertThat(db.count("SELECT COUNT(*) FROM cs2_audit WHERE action='ENROLL'")).isOne();
  }

  @Test
  void duplicateEnrollmentDoesNotInflateAggregates() {
    as(studentA, () -> enrollment.enroll("SEC1", key()));
    expectCode("DUPLICATE_ENROLLMENT", () -> as(studentA, () -> enrollment.enroll("SEC1", key())));
    assertThat(credits("A")).isEqualTo(3);
    assertThat(count("SEC1")).isOne();
  }

  @Test
  void concurrentLastSeatHasOneWinner() throws Exception {
    List<String> results =
        race(
            () -> attempt(studentA, () -> enrollment.enroll("SEC1", key())),
            () -> attempt(studentB, () -> enrollment.enroll("SEC1", key())));
    assertThat(results).containsExactlyInAnyOrder("OK", "SECTION_FULL");
    assertThat(count("SEC1")).isOne();
    assertThat(credits("A") + credits("B")).isEqualTo(3);
  }

  @Test
  void concurrentSameKeyReplaysCommittedResult() throws Exception {
    String k = key();
    List<String> ids =
        race(
            () -> as(studentA, () -> enrollment.enroll("SEC1", k)).get("id").toString(),
            () -> as(studentA, () -> enrollment.enroll("SEC1", k)).get("id").toString());
    assertThat(ids.get(0)).isEqualTo(ids.get(1));
    assertThat(count("SEC1")).isOne();
    assertThat(db.count("SELECT COUNT(*) FROM cs2_idempotency")).isOne();
  }

  @Test
  void sameStudentAcrossSectionsCannotExceedTermCreditLimit() throws Exception {
    List<String> results =
        race(
            () -> attempt(studentA, () -> enrollment.enroll("SEC1", key())),
            () -> attempt(studentA, () -> enrollment.enroll("SEC2", key())));
    assertThat(results).containsExactlyInAnyOrder("OK", "CREDIT_LIMIT");
    assertThat(credits("A")).isEqualTo(3);
  }

  @Test
  void timetableConflictUsesActualWeekAndPeriodOverlap() {
    db.update("UPDATE cs2_student_term SET credit_limit=10 WHERE student_id='A'");
    db.update("UPDATE cs2_meeting SET weekday=1 WHERE section_id='SEC2'");
    as(studentA, () -> enrollment.enroll("SEC1", key()));
    expectCode("TIMETABLE_CONFLICT", () -> as(studentA, () -> enrollment.enroll("SEC2", key())));
    Map<String, Object> eligibility = as(studentA, () -> enrollment.eligibility("A", "SEC2"));
    assertThat(eligibility.get("canEnroll")).isEqualTo(false);
    assertThat(count("SEC2")).isZero();
  }

  @Test
  void prerequisiteRequiresActualPreviouslyPublishedPassingGrade() {
    db.update(
        "INSERT INTO cs2_prerequisite(offering_id,course_id,min_grade)"
            + " VALUES('OFF2','COURSE1',60)");
    expectCode("PREREQUISITE_REQUIRED", () -> as(studentA, () -> enrollment.enroll("SEC2", key())));
    as(studentA, () -> enrollment.enroll("SEC1", key()));
    gradeAndPublish("SEC1", 80);
    db.update("UPDATE cs2_student_term SET credit_limit=10 WHERE student_id='A'");
    as(studentA, () -> enrollment.enroll("SEC2", key()));
    assertThat(count("SEC2")).isOne();
  }

  @Test
  void closedWindowRejectsAdmissionsAndLeavesNoIdempotencyClaim() {
    db.update("UPDATE cs2_round SET state='CLOSED' WHERE id='ROUND'");
    expectCode("WINDOW_CLOSED", () -> as(studentA, () -> enrollment.enroll("SEC1", key())));
    assertThat(count("SEC1")).isZero();
    assertThat(db.count("SELECT COUNT(*) FROM cs2_idempotency")).isZero();
  }

  @Test
  void failureAfterWritesRollsBackEnrollmentCountersAuditAndIdempotency() {
    String thirdUpdate =
        "UPDATE cs2_student_term SET selected_credits=selected_credits+? WHERE student_id=?"
            + " AND term_id=?";
    var reachedThirdWrite = new java.util.concurrent.atomic.AtomicInteger();
    doAnswer(
            invocation -> {
              boolean active =
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .isActualTransactionActive();
              long inserted =
                  db.count(
                      "SELECT COUNT(*) FROM cs2_enrollment WHERE student_id='A' AND"
                          + " section_id='SEC1' AND state='ENROLLED'");
              long occupied = count("SEC1");
              System.out.println(
                  "ROLLBACK_PROOF_BEFORE activeTransaction="
                      + active
                      + " enrollmentRows="
                      + inserted
                      + " enrolledCount="
                      + occupied);
              assertThat(active).as("fault executes inside the real JDBC transaction").isTrue();
              assertThat(inserted)
                  .as("first write is visible on the transaction connection")
                  .isOne();
              assertThat(occupied)
                  .as("second write is visible before injected third write")
                  .isOne();
              reachedThirdWrite.incrementAndGet();
              throw new org.springframework.dao.DataAccessResourceFailureException(
                  "injected third-write outage");
            })
        .when(db)
        .update(eq(thirdUpdate), any(), any(), any());
    assertThatThrownBy(() -> as(studentA, () -> enrollment.enroll("SEC1", key())))
        .isExactlyInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class)
        .hasMessage("injected third-write outage");
    assertThat(reachedThirdWrite.get()).isOne();
    verify(db, times(1)).update(eq(thirdUpdate), any(), any(), any());
    reset(db);
    long afterSeats = count("SEC1");
    long afterEnrollments = db.count("SELECT COUNT(*) FROM cs2_enrollment");
    long afterCredits = credits("A");
    long afterClaims = db.count("SELECT COUNT(*) FROM cs2_idempotency");
    long afterAudit = db.count("SELECT COUNT(*) FROM cs2_audit");
    System.out.println(
        "ROLLBACK_PROOF_AFTER enrollmentRows="
            + afterEnrollments
            + " enrolledCount="
            + afterSeats
            + " selectedCredits="
            + afterCredits
            + " idempotencyRows="
            + afterClaims
            + " auditRows="
            + afterAudit);
    assertThat(afterSeats).isZero();
    assertThat(afterEnrollments).isZero();
    assertThat(afterCredits).isZero();
    assertThat(afterClaims).isZero();
    assertThat(afterAudit).isZero();
  }

  @Test
  void waitlistIsFifoAndWithdrawalPromotesHead() {
    as(studentA, () -> enrollment.enroll("SEC1", key()));
    as(studentB, () -> enrollment.joinWaitlist("SEC1", key()));
    Actor c = account("uc", "studentC", "STUDENT", "C", null);
    as(c, () -> enrollment.joinWaitlist("SEC1", key()));
    String k = key();
    as(studentA, () -> enrollment.withdraw("SEC1", k));
    as(studentA, () -> enrollment.withdraw("SEC1", k));
    assertThat(
            db.one(
                    "SELECT student_id FROM cs2_enrollment WHERE section_id='SEC1' AND"
                        + " state='ENROLLED'")
                .get("studentId"))
        .isEqualTo("B");
    assertThat(count("SEC1")).isOne();
    assertThat(credits("A")).isZero();
    assertThat(credits("B")).isEqualTo(3);
    assertThat(db.count("SELECT COUNT(*) FROM cs2_waitlist WHERE state='WAITING'")).isOne();
  }

  @Test
  void boundedQueuePersistsPendingAndResumesAfterTwentyCandidates() {
    as(studentA, () -> enrollment.enroll("SEC1", key()));
    for (int i = 1; i <= 25; i++) {
      String s = "Q" + i;
      createStudent(s);
      Actor a = account("uq" + i, "queue" + i, "STUDENT", s, null);
      as(a, () -> enrollment.joinWaitlist("SEC1", key()));
      if (i <= 20) db.update("UPDATE cs2_student SET state='SUSPENDED' WHERE id=?", s);
    }
    Map<String, Object> result = as(studentA, () -> enrollment.withdraw("SEC1", key()));
    assertThat(result.get("promotionPending")).isEqualTo(true);
    assertThat(db.count("SELECT COUNT(*) FROM cs2_waitlist WHERE state='SKIPPED'")).isEqualTo(20);
    assertThat(count("SEC1")).isZero();
    expectCode("WAITLIST_FIRST", () -> as(studentB, () -> enrollment.enroll("SEC1", key())));
    enrollment.promote("SEC1");
    assertThat(
            db.one(
                    "SELECT student_id FROM cs2_enrollment WHERE section_id='SEC1' AND"
                        + " state='ENROLLED'")
                .get("studentId"))
        .isEqualTo("Q21");
    assertThat(count("SEC1")).isOne();
  }

  @Test
  void oldRoundEntriesExpireEvenWhenNewRoundClassIsFull() {
    as(studentA, () -> enrollment.enroll("SEC1", key()));
    as(studentB, () -> enrollment.joinWaitlist("SEC1", key()));
    db.update("UPDATE cs2_round SET state='CLOSED' WHERE id='ROUND'");
    Instant now = Instant.now();
    db.update(
        "INSERT INTO"
            + " cs2_round(id,term_id,name,state,starts_at,ends_at,withdrawal_ends_at,credit_limit)"
            + " VALUES('ROUND2','TERM','第二轮','OPEN',?,?,?,5)",
        Timestamp.from(now.minusSeconds(30)),
        Timestamp.from(now.plusSeconds(500)),
        Timestamp.from(now.plusSeconds(600)));
    enrollment.promote("SEC1");
    assertThat(db.count("SELECT COUNT(*) FROM cs2_waitlist WHERE state='EXPIRED'")).isOne();
    assertThat(count("SEC1")).isOne();
  }

  @Test
  void failedSwapPreservesOriginalAndSuccessfulSwapMovesCreditsAtomically() {
    as(studentA, () -> enrollment.enroll("SEC1", key()));
    as(studentB, () -> enrollment.enroll("SEC2", key()));
    expectCode("SECTION_FULL", () -> as(studentA, () -> enrollment.swap("SEC1", "SEC2", key())));
    assertThat(count("SEC1")).isOne();
    assertThat(credits("A")).isEqualTo(3);
    as(studentA, () -> enrollment.swap("SEC1", "SEC3", key()));
    assertThat(count("SEC1")).isZero();
    assertThat(count("SEC3")).isOne();
    assertThat(credits("A")).isEqualTo(3);
    assertThat(db.count("SELECT COUNT(*) FROM cs2_audit WHERE action='SWAP'")).isOne();
  }

  @Test
  void reverseConcurrentSwapsDoNotDeadlock() throws Exception {
    as(studentA, () -> enrollment.enroll("SEC1", key()));
    as(studentB, () -> enrollment.enroll("SEC2", key()));
    List<String> r =
        race(
            () -> attempt(studentA, () -> enrollment.swap("SEC1", "SEC2", key())),
            () -> attempt(studentB, () -> enrollment.swap("SEC2", "SEC1", key())));
    assertThat(r).containsExactly("SECTION_FULL", "SECTION_FULL");
    assertThat(count("SEC1")).isOne();
    assertThat(count("SEC2")).isOne();
  }

  @Test
  void draftHiddenPublishVisibleCorrectionRetainsPreviousPublishedGrade() {
    as(studentA, () -> enrollment.enroll("SEC1", key()));
    Map<String, Object> sheet = as(teacher, () -> grades.sheet("SEC1"));
    Map<String, Object> row = ((List<Map<String, Object>>) sheet.get("items")).get(0);
    as(
        teacher,
        () ->
            grades.save(
                "SEC1",
                Map.of(
                    "items",
                    List.of(
                        Api.object(
                            "enrollmentId",
                            row.get("enrollmentId"),
                            "grade",
                            88.5,
                            "version",
                            row.get("version"))))));
    assertThat(
            queries.enrollments("TERM", "A", null, null, 0, 20, true).items().get(0).get("grade"))
        .isNull();
    sheet = as(teacher, () -> grades.sheet("SEC1"));
    final long version = Db.lng(sheet, "version");
    as(teacher, () -> grades.publish("SEC1", Map.of("version", version)));
    assertThat(
            queries.enrollments("TERM", "A", null, null, 0, 20, true).items().get(0).get("grade"))
        .isEqualTo(new BigDecimal("88.50"));
    Map<String, Object> published = as(teacher, () -> grades.sheet("SEC1"));
    as(
        administrator,
        () ->
            grades.reopen(
                "SEC1", Api.object("version", published.get("version"), "reason", "测试明确更正原因")));
    assertThat(
            queries.enrollments("TERM", "A", null, null, 0, 20, true).items().get(0).get("grade"))
        .isEqualTo(new BigDecimal("88.50"));
    assertThat(queries.studentSummary("A", "TERM").get("earnedCredits")).isEqualTo(3L);
    expectCode("GRADES_PUBLISHED", () -> as(studentA, () -> enrollment.withdraw("SEC1", key())));
  }

  @Test
  void staleGradeBatchRollsBackEveryRow() {
    db.update("UPDATE cs2_section SET capacity=2 WHERE id='SEC1'");
    as(studentA, () -> enrollment.enroll("SEC1", key()));
    as(studentB, () -> enrollment.enroll("SEC1", key()));
    List<Map<String, Object>> rows =
        (List<Map<String, Object>>) as(teacher, () -> grades.sheet("SEC1")).get("items");
    List<Map<String, Object>> batch =
        List.of(
            Api.object("enrollmentId", rows.get(0).get("enrollmentId"), "grade", 80, "version", 0),
            Api.object(
                "enrollmentId", rows.get(1).get("enrollmentId"), "grade", 90, "version", 99));
    expectCode(
        "VERSION_CONFLICT", () -> as(teacher, () -> grades.save("SEC1", Map.of("items", batch))));
    assertThat(db.count("SELECT COUNT(*) FROM cs2_enrollment WHERE grade IS NOT NULL")).isZero();
  }

  @Test
  void teacherCannotReadOrWriteAnotherTeachersGrades() {
    db.update("UPDATE cs2_section SET teacher_id='OTHER' WHERE id='SEC2'");
    expectCode("FORBIDDEN", () -> as(teacher, () -> grades.sheet("SEC2")));
    expectCode("FORBIDDEN", () -> as(studentA, () -> grades.sheet("SEC1")));
  }

  @Test
  void publishedOfferingAndTimetableRejectMutation() {
    Map<String, Object> offering = as(administrator, () -> admin.detail("offerings", "OFF1"));
    offering.put("credits", 4);
    offering.put("prerequisites", List.of());
    expectCode(
        "FROZEN_RESOURCE",
        () -> as(administrator, () -> admin.save("offerings", "OFF1", offering)));
    Map<String, Object> section = as(administrator, () -> admin.detail("sections", "SEC1"));
    section.put("teacherId", "OTHER");
    expectCode(
        "FROZEN_RESOURCE", () -> as(administrator, () -> admin.save("sections", "SEC1", section)));
  }

  @Test
  void adminCannotArchiveActiveRoundOrChangeTermCreditPolicy() {
    Map<String, Object> term = as(administrator, () -> admin.detail("terms", "TERM"));
    term.put("state", "ARCHIVED");
    expectCode(
        "ROUND_STILL_OPEN", () -> as(administrator, () -> admin.save("terms", "TERM", term)));
    Map<String, Object> round =
        Api.object(
            "id",
            "ROUND2",
            "termId",
            "TERM",
            "name",
            "第二轮",
            "state",
            "DRAFT",
            "startsAt",
            Instant.now().plusSeconds(8000).toString(),
            "endsAt",
            Instant.now().plusSeconds(9000).toString(),
            "withdrawalEndsAt",
            Instant.now().plusSeconds(10000).toString(),
            "creditLimit",
            10);
    expectCode(
        "TERM_CREDIT_RULE_FROZEN",
        () -> as(administrator, () -> admin.save("rounds", null, round)));
  }

  @Test
  void aggregateAuditChecksActualCountersAndFindsDeliberateDrift() {
    as(studentA, () -> enrollment.enroll("SEC1", key()));
    assertThat(queries.consistency("TERM").get("status")).isEqualTo("PASS");
    db.update("UPDATE cs2_student_term SET selected_credits=4 WHERE student_id='A'");
    assertThat(queries.consistency("TERM").get("status")).isEqualTo("FAIL");
  }

  @Test
  void schemaChecksumMismatchIsRejectedWithoutRequiringDdlPrivileges() {
    Map<String, Object> actual =
        db.one("SELECT version,source_version,schema_sha256 FROM cs2_schema_version");
    Map<String, Object> mismatch = new LinkedHashMap<>(actual);
    mismatch.put("schemaSha256", "0".repeat(64));
    doReturn(mismatch)
        .when(db)
        .one("SELECT version,source_version,schema_sha256 FROM cs2_schema_version");
    assertThatThrownBy(bootstrap::verify).isInstanceOf(IllegalStateException.class);
    reset(db);
    bootstrap.verify();
  }

  @Test
  void passedCreditsCountRepeatedCourseOnlyOnceAcrossTerms() {
    as(studentA, () -> enrollment.enroll("SEC1", key()));
    gradeAndPublish("SEC1", 80);
    db.update("INSERT INTO cs2_term(id,name,state) VALUES('PAST','历史学期','ARCHIVED')");
    db.update(
        "INSERT INTO cs2_offering(id,course_id,term_id,credits,state)"
            + " VALUES('PASTOFF','COURSE1','PAST',4,'PUBLISHED')");
    db.update(
        "INSERT INTO"
            + " cs2_section(id,offering_id,teacher_id,capacity,enrolled_count,state,grade_state,grade_revision)"
            + " VALUES('PASTSEC','PASTOFF','TEACHER',30,1,'ARCHIVED','PUBLISHED',1)");
    db.update(
        "INSERT INTO"
            + " cs2_enrollment(id,student_id,offering_id,section_id,state,grade,published_grade,created_at,updated_at)"
            + " VALUES(?,'A','PASTOFF','PASTSEC','ENROLLED',75,75,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
        Db.uid());
    assertThat(queries.studentSummary("A", "TERM").get("earnedCredits")).isEqualTo(4L);
  }

  @Test
  void disabledAccountInvalidatesAnExistingSession() throws Exception {
    db.update("UPDATE cs2_user SET enabled=0,version=version+1 WHERE id=?", studentA.id());
    http.perform(get("/api/v2/auth/me").with(user(studentA)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("ACCOUNT_CHANGED"));
  }

  @Test
  void fractionalVersionsAreRejectedRatherThanTruncated() {
    expectCode("INVALID_INPUT", () -> Api.version(Map.of("version", 1.5)));
    expectCode(
        "INVALID_INPUT",
        () -> Api.version(Map.of("version", new java.math.BigInteger("9223372036854775808"))));
  }

  @Test
  void searchSupportsCourseCodeAndAuditObjectWithoutSqlInterpolation() {
    assertThat(queries.catalog("TERM", "COURSE1", null, false, 0, 20, null).total()).isEqualTo(1);
    assertThat(queries.catalog("TERM", "x' OR 1=1 --", null, false, 0, 20, null).total()).isZero();
    as(studentA, () -> enrollment.enroll("SEC1", key()));
    assertThat(as(administrator, () -> admin.audit("SEC1", "TERM", 0, 20)).total()).isEqualTo(1);
  }

  @Test
  void httpPaginationHealthAndValidationNeverLeakPasswordHash() throws Exception {
    http.perform(get("/api/v2/health"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.surrogate").value(env.getProperty("app.database.type").equals("h2")));
    http.perform(get("/api/v2/admin/users").with(user(administrator)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].passwordHash").doesNotExist());
    http.perform(get("/api/v2/catalog").with(user(studentA)).param("page", "0").param("size", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.total").value(3));
    http.perform(get("/api/v2/catalog").with(user(studentA)).param("size", "1001"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    http.perform(
            post("/api/v2/admin/courses")
                .with(user(administrator))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"id\":\"OPTIONAL\",\"title\":\"可选说明课程\",\"department\":\"测试学院\",\"description\":\"\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.description").value("未填写课程说明"));
  }

  @Test
  void relationalViewsDriveTimetablePublishedTranscriptAndAdminSummary() {
    as(studentA, () -> enrollment.enroll("SEC1", key()));
    assertThat((List<?>) queries.timetable("A", "TERM").get("items")).hasSize(1);
    assertThat((List<?>) queries.timetable("B", "TERM").get("items")).isEmpty();
    assertThat(db.count("SELECT COUNT(*) FROM cs2_v_transcript WHERE student_id=?", "A")).isZero();
    gradeAndPublish("SEC1", 80);
    assertThat(
            db.count(
                "SELECT COUNT(*) FROM cs2_v_transcript WHERE student_id=? AND published_grade=80",
                "A"))
        .isOne();
    Map<String, Object> summary =
        db.one("SELECT * FROM cs2_v_term_summary WHERE term_id=?", "TERM");
    assertThat(Db.lng(summary, "sectionCount")).isEqualTo(3);
    assertThat(Db.lng(summary, "courseCount")).isEqualTo(3);
    assertThat(Db.lng(summary, "enrollmentCount")).isEqualTo(1);
    assertThat(Db.lng(summary, "totalCapacity")).isEqualTo(3);
    Map<String, Object> dashboard = as(administrator, () -> queries.dashboard("TERM"));
    assertThat(dashboard.get("courses")).isEqualTo(summary.get("courseCount"));
    assertThat(dashboard.get("sections")).isEqualTo(summary.get("sectionCount"));
    assertThat(dashboard.get("enrollments")).isEqualTo(summary.get("enrollmentCount"));
    if (env.getProperty("app.database.type").equals("opengauss")) {
      for (String view :
          List.of("cs2_v_student_timetable", "cs2_v_transcript", "cs2_v_term_summary")) {
        assertThat(
                db.jdbc.queryForObject(
                    "SELECT has_table_privilege(current_user,?,'SELECT')", Boolean.class, view))
            .as(view + " SELECT")
            .isTrue();
        for (String write : List.of("INSERT", "UPDATE", "DELETE"))
          assertThat(
                  db.jdbc.queryForObject(
                      "SELECT has_table_privilege(current_user,?,?)", Boolean.class, view, write))
              .as(view + " " + write)
              .isFalse();
      }
    }
  }

  @Test
  void nativeDatabaseClockRejectsRequestThatExpiredWhileWaitingForSectionLock() throws Exception {
    Assumptions.assumeTrue(
        env.getProperty("app.database.type").equals("opengauss"),
        "H2 surrogate is not proof of native clock_timestamp semantics");
    Instant soon = db.now().plusMillis(700);
    db.update(
        "UPDATE cs2_round SET ends_at=?,withdrawal_ends_at=? WHERE id='ROUND'",
        Timestamp.from(soon),
        Timestamp.from(soon.plusSeconds(60)));
    CountDownLatch locked = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> holder =
          executor.submit(
              () ->
                  new TransactionTemplate(manager)
                      .executeWithoutResult(
                          status -> {
                            db.one("SELECT id FROM cs2_section WHERE id='SEC1' FOR UPDATE");
                            locked.countDown();
                            try {
                              Thread.sleep(1100);
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                            }
                          }));
      assertThat(locked.await(2, TimeUnit.SECONDS)).isTrue();
      Future<String> request =
          executor.submit(() -> attempt(studentA, () -> enrollment.enroll("SEC1", key())));
      holder.get(3, TimeUnit.SECONDS);
      assertThat(request.get(4, TimeUnit.SECONDS)).isEqualTo("WINDOW_CLOSED");
      assertThat(count("SEC1")).isZero();
    } finally {
      executor.shutdownNow();
    }
  }

  void gradeAndPublish(String section, int value) {
    Map<String, Object> sheet = as(teacher, () -> grades.sheet(section));
    List<Map<String, Object>> batch = new ArrayList<>();
    for (Map<String, Object> r : (List<Map<String, Object>>) sheet.get("items"))
      batch.add(
          Api.object(
              "enrollmentId", r.get("enrollmentId"), "grade", value, "version", r.get("version")));
    as(teacher, () -> grades.save(section, Map.of("items", batch)));
    long version = Db.lng(as(teacher, () -> grades.sheet(section)), "version");
    as(teacher, () -> grades.publish(section, Map.of("version", version)));
  }

  String attempt(Actor a, Supplier<?> call) {
    try {
      as(a, call);
      return "OK";
    } catch (Api.Failure f) {
      return f.code;
    }
  }

  <T> List<T> race(Callable<T> a, Callable<T> b) throws Exception {
    CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Callable<T> one =
          () -> {
            ready.countDown();
            if (!start.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("barrier");
            return a.call();
          };
      Callable<T> two =
          () -> {
            ready.countDown();
            if (!start.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("barrier");
            return b.call();
          };
      Future<T> fa = executor.submit(one), fb = executor.submit(two);
      assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      return List.of(fa.get(15, TimeUnit.SECONDS), fb.get(15, TimeUnit.SECONDS));
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }
}
