package edu.ustb.coursev2;

import static edu.ustb.coursev2.Db.*;
import static org.assertj.core.api.Assertions.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@SpringBootTest(
    properties = {
      "app.mode=${TEST_APP_MODE:test}",
      "app.database.type=${TEST_DB_TYPE:h2}",
      "app.database.url=${TEST_DB_URL:jdbc:h2:mem:planning_contract;MODE=PostgreSQL;DB_CLOSE_DELAY=-1}",
      "app.database.user=${TEST_DB_USER:sa}",
      "app.database.password=${TEST_DB_PASSWORD:}",
      "app.bootstrap.admin-password=Test-only-Admin-2026",
      "app.bootstrap.teacher-password=Test-only-Teacher-2026",
      "app.bootstrap.student-password=Test-only-Student-2026",
      "app.promotions.enabled=false"
    })
class PlanningContractTest {
  @Autowired Db db;
  @Autowired PlanningService plans;
  @Autowired PlanningResources resources;
  @Autowired org.springframework.core.env.Environment environment;
  String prefix, term, round, teacher, a, b, x, y;
  Actor administrator, alice, bob;

  String id(String suffix) {
    return prefix + suffix;
  }

  <T> T as(Actor a, Supplier<T> action) {
    var ctx = SecurityContextHolder.createEmptyContext();
    ctx.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(a, "test", a.getAuthorities()));
    SecurityContextHolder.setContext(ctx);
    try {
      return action.get();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  void failure(String code, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(Api.Failure.class, e -> assertThat(e.code).isEqualTo(code));
  }

  Actor actor(String role, String student) {
    String user = id("u" + (student == null ? "admin" : student.charAt(student.length() - 1)));
    db.update(
        "INSERT INTO cs2_user(id,username,password_hash,display_name,role,student_id,enabled)"
            + " VALUES(?,?,?,?,?,?,1)",
        user,
        user,
        "test-fixture-unused",
        user,
        role,
        student);
    return new Actor(user, user, "test-fixture-unused", user, role, student, null, true);
  }

  void section(String section, int weekday, boolean published) {
    db.update(
        "INSERT INTO cs2_course(id,title,department,description) VALUES(?,?,'QA','fixture')",
        section,
        section);
    db.update(
        "INSERT INTO cs2_offering(id,course_id,term_id,credits,state) VALUES(?,?,?,3,'PUBLISHED')",
        section,
        section,
        term);
    db.update(
        "INSERT INTO cs2_section(id,offering_id,teacher_id,capacity,state) VALUES(?,?,?,1,?)",
        section,
        section,
        teacher,
        published ? "PUBLISHED" : "DRAFT");
    if (published)
      db.update(
          "INSERT INTO"
              + " cs2_meeting(id,section_id,weekday,start_period,end_period,start_week,end_week,room)"
              + " VALUES(?,?,?,1,2,1,16,?)",
          uid(),
          section,
          weekday,
          id("room"));
  }

  @BeforeEach
  void fixture() {
    String url = environment.getRequiredProperty("app.database.url");
    assertThat(url.startsWith("jdbc:h2:") || url.matches(".*/course_app_v3_test(?:\\?.*)?$"))
        .isTrue();
    prefix = "P" + uid().replace("-", "").substring(0, 12);
    term = id("T");
    round = id("R");
    teacher = id("F");
    a = id("A");
    b = id("B");
    x = id("X");
    y = id("Y");
    db.update("INSERT INTO cs2_term(id,name,state) VALUES(?,?,'ACTIVE')", term, term);
    db.update(
        "INSERT INTO"
            + " cs2_round(id,term_id,name,state,starts_at,ends_at,withdrawal_ends_at,credit_limit)"
            + " VALUES(?,?,?,'DRAFT',?,?,?,6)",
        round,
        term,
        round,
        Timestamp.from(Instant.now().minusSeconds(60)),
        Timestamp.from(Instant.now().plusSeconds(1800)),
        Timestamp.from(Instant.now().plusSeconds(3600)));
    db.update("INSERT INTO cs2_teacher(id,name,department) VALUES(?,?,'QA')", teacher, teacher);
    for (String st : List.of(a, b))
      db.update("INSERT INTO cs2_student(id,name,department) VALUES(?,?,'QA')", st, st);
    administrator = actor("ADMIN", null);
    alice = actor("STUDENT", a);
    bob = actor("STUDENT", b);
    section(x, 1, true);
    section(y, 2, true);
  }

  @Test
  void sealPreviewStalenessPublishReplayAndOwnOnlyResults() {
    failure("FORBIDDEN", () -> as(alice, () -> plans.commit(round)));
    var commit = as(administrator, () -> plans.commit(round));
    db.update("UPDATE cs2_round SET state='OPEN',version=version+1 WHERE id=?",round);
    assertThat(commit).doesNotContainKey("seedReveal");
    as(
        alice,
        () -> plans.savePreferences(round, Map.of("sectionIds", List.of(x, y), "version", 0)));
    failure(
        "VERSION_CONFLICT",
        () ->
            as(
                alice,
                () ->
                    plans.savePreferences(round, Map.of("sectionIds", List.of(y), "version", 0))));
    as(bob, () -> plans.savePreferences(round, Map.of("sectionIds", List.of(x), "version", 0)));
    failure("PREFERENCES_STILL_OPEN",()->as(administrator,()->plans.allocationPreview(round)));
    db.update("UPDATE cs2_round SET state='CLOSED',version=version+1 WHERE id=?",round);
    var first = as(administrator, () -> plans.allocationPreview(round));
    assertThat(AllocationSolver.sha(str(first, "seedReveal")))
        .isEqualTo(commit.get("seedCommitment"));
    failure(
        "PREFERENCES_FROZEN",
        () ->
            as(
                alice,
                () ->
                    plans.savePreferences(round, Map.of("sectionIds", List.of(x), "version", 1))));
    assertThat(db.count("SELECT COUNT(*) FROM cs2_enrollment WHERE section_id IN (?,?)", x, y))
        .isZero();
    db.update("UPDATE cs2_section SET version=version+1 WHERE id=?", x);
    failure(
        "STALE_PREVIEW",
        () ->
            as(
                administrator,
                () -> plans.publish(str(first, "id"), str(first, "snapshotHash"), uid())));
    assertThat(db.count("SELECT COUNT(*) FROM cs2_enrollment WHERE section_id IN (?,?)", x, y))
        .isZero();
    var fresh = as(administrator, () -> plans.allocationPreview(round));
    String key = uid();
    var published =
        as(administrator, () -> plans.publish(str(fresh, "id"), str(fresh, "snapshotHash"), key));
    assertThat(
            as(
                administrator,
                () -> plans.publish(str(fresh, "id"), str(fresh, "snapshotHash"), key)))
        .isEqualTo(published);
    assertThat(db.count("SELECT COUNT(*) FROM cs2_enrollment WHERE section_id IN (?,?)", x, y))
        .isEqualTo(2);
    assertThat(db.count("SELECT SUM(selected_credits) FROM cs2_student_term WHERE term_id=?", term))
        .isEqualTo(6);
    assertThat(
            db.count(
                "SELECT COUNT(*) FROM cs2_audit WHERE action='ALLOCATE_SEAT' AND term_id=?", term))
        .isEqualTo(2);
    var own = as(alice, () -> plans.preferences(round));
    assertThat(PlanningService.records(own.get("assignments"), 8, "assignments"))
        .extracting(m -> m.get("studentId"))
        .containsExactly(a);
    failure("FORBIDDEN", () -> as(bob, () -> plans.run(str(fresh, "id"))));
  }

  Map<String, Object> timing(int day, String room) {
    return Map.of(
        "weekday",
        day,
        "startPeriod",
        1,
        "endPeriod",
        2,
        "startWeek",
        1,
        "endWeek",
        16,
        "room",
        room);
  }

  Map<String, Object> request(List<Map<String, Object>> sections) {
    return Map.of(
        "termId",
        term,
        "rooms",
        List.of(Map.of("id", id("room"), "capacity", 25)),
        "sections",
        sections);
  }

  @Test
  void conflictingBatchIsPreviewOnlyAndCompleteSchedulePublishesAtomically() {
    String s1 = id("D1"), s2 = id("D2"), room = id("room");
    section(s1, 3, false);
    section(s2, 4, false);
    var same = timing(3, room);
    var later = timing(4, room);
    var d1 = Map.<String, Object>of("sectionId", s1, "slots", List.of(same), "lockedSlot", 0);
    var resourcesBody =
        Map.<String, Object>of(
            "version",
            0,
            "rooms",
            List.of(Map.of("id", room, "capacity", 25)),
            "unavailable",
            List.of());
    var example = as(administrator, () -> plans.schedulePreview(request(List.of(d1))));
    failure(
        "RESOURCES_REQUIRED",
        () ->
            as(
                administrator,
                () -> plans.publish(str(example, "id"), str(example, "snapshotHash"), uid())));
    as(administrator, () -> resources.replace(term, resourcesBody));
    failure(
        "STALE_PREVIEW",
        () ->
            as(
                administrator,
                () -> plans.publish(str(example, "id"), str(example, "snapshotHash"), uid())));
    var partial =
        as(
            administrator,
            () ->
                plans.schedulePreview(
                    request(List.of(d1, Map.of("sectionId", s2, "slots", List.of(same))))));
    failure(
        "PARTIAL_SCHEDULE",
        () ->
            as(
                administrator,
                () -> plans.publish(str(partial, "id"), str(partial, "snapshotHash"), uid())));
    assertThat(
            db.count(
                "SELECT COUNT(*) FROM cs2_section WHERE id IN (?,?) AND state='PUBLISHED'", s1, s2))
        .isZero();
    var full =
        as(
            administrator,
            () ->
                plans.schedulePreview(
                    request(List.of(d1, Map.of("sectionId", s2, "slots", List.of(same, later))))));
    as(administrator, () -> plans.publish(str(full, "id"), str(full, "snapshotHash"), uid()));
    assertThat(
            db.count(
                "SELECT COUNT(*) FROM cs2_section WHERE id IN (?,?) AND state='PUBLISHED'", s1, s2))
        .isEqualTo(2);
    assertThat(
            db.count(
                "SELECT COUNT(DISTINCT weekday) FROM cs2_meeting WHERE section_id IN (?,?)",
                s1,
                s2))
        .isEqualTo(2);
  }
}
