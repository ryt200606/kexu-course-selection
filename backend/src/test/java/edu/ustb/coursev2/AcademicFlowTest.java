package edu.ustb.coursev2;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    properties = {
      "app.mode=${TEST_APP_MODE:test}",
      "app.database.type=${TEST_DB_TYPE:h2}",
      "app.database.url=${TEST_DB_URL:jdbc:h2:mem:academic_v3;MODE=PostgreSQL;DB_CLOSE_DELAY=-1}",
      "app.database.user=${TEST_DB_USER:sa}",
      "app.database.password=${TEST_DB_PASSWORD:}",
      "app.bootstrap.admin-password=Test-only-Admin-2026",
      "app.bootstrap.teacher-password=Test-only-Teacher-2026",
      "app.bootstrap.student-password=Test-only-Student-2026",
      "app.promotions.enabled=false"
    })
@Transactional
@Timeout(25)
class AcademicFlowTest {
  @Autowired AcademicCurriculum curriculum;
  @Autowired AcademicReviews reviews;
  @Autowired AcademicPlans plans;
  @Autowired AcademicController controller;
  @Autowired Db db;

  @BeforeEach
  void fixture() {
    for (String student : List.of("ACST1", "ACST2"))
      db.update("INSERT INTO cs2_student(id,name,department) VALUES(?,?,'演示院')", student, student);
    for (String teacher : List.of("ACT1", "ACT2"))
      db.update("INSERT INTO cs2_teacher(id,name,department) VALUES(?,?,'演示院')", teacher, teacher);
    for (String term : List.of("ACNOW", "ACPAST", "ACOLDER"))
      db.update("INSERT INTO cs2_term(id,name,state) VALUES(?,?,'ACTIVE')", term, term);
    var now = Instant.now();
    db.update(
        "INSERT INTO"
            + " cs2_round(id,term_id,name,state,starts_at,ends_at,withdrawal_ends_at,credit_limit) "
            + "VALUES('ACRD','ACNOW','轮次','OPEN',?,?,?,8)",
        Timestamp.from(now.minusSeconds(300)),
        Timestamp.from(now.plusSeconds(3600)),
        Timestamp.from(now.plusSeconds(7200)));
    for (String c : List.of("ACC1", "ACC2", "ACC3"))
      db.update(
          "INSERT INTO cs2_course(id,title,department,description) VALUES(?,?,'院','虚构')", c, c);
    for (int i = 1; i <= 3; i++)
      db.update(
          "INSERT INTO cs2_offering(id,course_id,term_id,credits,state)"
              + " VALUES(?,?,'ACNOW',3,'PUBLISHED')",
          "ACO" + i,
          "ACC" + i);
    for (int i = 1; i <= 4; i++) {
      String section = "ACS" + i;
      db.update(
          "INSERT INTO cs2_section(id,offering_id,teacher_id,capacity,state)"
              + " VALUES(?,?,'ACT1',10,'PUBLISHED')",
          section,
          i == 4 ? "ACO2" : "ACO" + i);
      db.update(
          "INSERT INTO"
              + " cs2_meeting(id,section_id,weekday,start_period,end_period,start_week,end_week,room)"
              + " VALUES(?,?,?,1,2,1,16,'教一')",
          Db.uid(),
          section,
          i < 3 ? 1 : i);
    }
    for (var term : List.of("ACPAST", "ACOLDER")) {
      String o = "O" + term, s = "S" + term;
      db.update(
          "INSERT INTO cs2_offering(id,course_id,term_id,credits,state)"
              + " VALUES(?,'ACC1',?,3,'PUBLISHED')",
          o,
          term);
      db.update(
          "INSERT INTO"
              + " cs2_section(id,offering_id,teacher_id,capacity,enrolled_count,state,grade_state,grade_revision)"
              + " VALUES(?,?,'ACT1',10,1,'ARCHIVED','PUBLISHED',1)",
          s,
          o);
      db.update(
          "INSERT INTO"
              + " cs2_enrollment(id,student_id,section_id,offering_id,state,grade,published_grade,created_at,updated_at)"
              + " VALUES(?,'ACST1',?,?,'ENROLLED',65,65,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
          "E" + term,
          s,
          o);
    }
    as("ADMIN", null);
  }

  static void as(String role, String id) {
    Actor actor =
        new Actor(
            "U" + (id == null ? "A" : id),
            role,
            "x",
            role,
            role,
            role.equals("STUDENT") ? id : null,
            role.equals("TEACHER") ? id : null,
            true);
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(actor, "x", actor.getAuthorities()));
    SecurityContextHolder.setContext(context);
  }

  @AfterEach
  void logout() {
    SecurityContextHolder.clearContext();
  }

  Map<String, Object> publishPlan(String cohort, List<String> courses) {
    return curriculum.create(
        Map.of(
            "cohort",
            cohort,
            "name",
            "计算机培养方案",
            "modules",
            List.of(Map.of("name", "专业必修", "requiredCredits", 6, "courseIds", courses))));
  }

  @Test
  void publishedCurriculumIsPinnedAndRetakesCountOnce() {
    var first = publishPlan("AC2026", List.of("ACC1", "ACC2"));
    curriculum.assign(Db.str(first, "id"), Map.of("studentIds", List.of("ACST1")));
    var second = publishPlan("AC2026", List.of("ACC3"));
    assertThat(Db.lng(second, "version")).isEqualTo(2);
    var cohort = curriculum.cohortProgress(Db.str(first, "id"), 0, 20);
    assertThat(cohort.total()).isEqualTo(1);
    assertThat(Db.num(cohort.items().get(0), "earnedCredits")).isEqualTo(3);
    assertThat(Db.num(cohort.items().get(0), "remainingCredits")).isEqualTo(3);
    as("STUDENT", "ACST1");
    var p = curriculum.progress();
    assertThat(Db.num(p, "earnedCredits")).isEqualTo(3);
    assertThat(Db.num(p, "remainingCredits")).isEqualTo(3);
    assertThat(((Map<?, ?>) p.get("curriculum")).get("id")).isEqualTo(first.get("id"));
    as("STUDENT", "ACST2");
    assertThat(curriculum.progress().get("curriculum")).isNull();
  }

  @Test
  void pairSimulationFindsConflictsAcrossCandidatesAndFeasibleAlternative() {
    as("STUDENT", "ACST1");
    var result =
        plans.compare(
            Map.of(
                "termId",
                "ACNOW",
                "left",
                List.of("ACS1", "ACS2"),
                "right",
                List.of("ACS1", "ACS4")));
    var left = (Map<?, ?>) result.get("left");
    var right = (Map<?, ?>) result.get("right");
    assertThat(left.get("feasible")).isEqualTo(false);
    assertThat(right.get("feasible")).isEqualTo(true);
    var row = (Map<?, ?>) ((List<?>) left.get("items")).get(1);
    assertThat(row.get("alternatives").toString()).contains("ACS4");
    assertThat(
            db.count(
                "SELECT COUNT(*) FROM cs2_enrollment WHERE student_id='ACST1' AND section_id IN"
                    + " ('ACS1','ACS2','ACS4')"))
        .isZero();
  }

  @Test
  void pairSimulationChecksTotalCreditsAndCourseUniqueness() {
    as("STUDENT", "ACST1");
    var r =
        plans.compare(
            Map.of(
                "termId",
                "ACNOW",
                "left",
                List.of("ACS1", "ACS3", "ACS4"),
                "right",
                List.of("ACS2", "ACS4")));
    assertThat(r.get("left").toString()).contains("学期上限");
    assertThat(r.get("right").toString()).contains("重复选择");
  }

  @Test
  void savedPlansRejectStaleEditorsAndStayPrivate() {
    as("STUDENT", "ACST1");
    assertThat(plans.saved("ACNOW").get("version")).isEqualTo(0L);
    var draft =
        Map.<String, Object>of(
            "left", List.of("ACS1"), "right", List.of("ACS3", "ACS4"), "version", 0);
    assertThat(plans.save("ACNOW", draft).get("version")).isEqualTo(1L);
    assertThat(plans.saved("ACNOW").get("right")).isEqualTo(List.of("ACS3", "ACS4"));
    assertThatThrownBy(() -> plans.save("ACNOW", draft))
        .isInstanceOfSatisfying(
            Api.Failure.class, e -> assertThat(e.code).isEqualTo("VERSION_CONFLICT"));
    as("STUDENT", "ACST2");
    assertThat(plans.saved("ACNOW").get("right")).isEqualTo(List.of());
  }

  String request() {
    as("STUDENT", "ACST1");
    return Db.str(reviews.request(Map.of("enrollmentId", "EACPAST", "reason", "请核对评分记录")), "id");
  }

  @Test
  void onlyFinalTeacherPublicationChangesOfficialGradeAndFourStagesAreAudited() {
    String id = request();
    as("TEACHER", "ACT1");
    var rec =
        reviews.recommend(id, Map.of("version", 0, "reason", "核对原卷", "proposedGrade", "59.50"));
    assertThat(rec.get("crossesPassThreshold")).isEqualTo(true);
    as("ADMIN", null);
    assertThat(
            reviews.decide(id, Map.of("version", 1, "approve", true, "reason", "已复核")).get("state"))
        .isEqualTo("APPROVED");
    assertThat(officialGrade()).isEqualByComparingTo("65");
    as("TEACHER", "ACT1");
    assertThat(reviews.publish(id, Map.of("version", 2)).get("state")).isEqualTo("PUBLISHED");
    assertThat(officialGrade()).isEqualByComparingTo("59.5");
    as("STUDENT", "ACST1");
    assertThat((List<?>) reviews.get(id).get("history")).hasSize(4);
  }

  BigDecimal officialGrade() {
    return (BigDecimal)
        db.one("SELECT published_grade FROM cs2_v_transcript WHERE enrollment_id='EACPAST'")
            .get("publishedGrade");
  }

  @Test
  void anotherStudentAndAnUnassignedTeacherCannotAccessTheReview() {
    String id = request();
    as("STUDENT", "ACST2");
    assertThat(reviews.list(null, "ACPAST", 0, 20).total()).isZero();
    assertThatThrownBy(() -> reviews.get(id))
        .isInstanceOfSatisfying(Api.Failure.class, e -> assertThat(e.status).isEqualTo(404));
    as("TEACHER", "ACT2");
    assertThatThrownBy(
            () -> reviews.recommend(id, Map.of("version", 0, "reason", "复核", "proposedGrade", 70)))
        .isInstanceOf(Api.Failure.class);
    assertThat(officialGrade()).isEqualByComparingTo("65");
  }

  @Test
  void aNewerSourceGradePreventsPublishingAnOldRecommendation() {
    String id = request();
    as("TEACHER", "ACT1");
    reviews.recommend(id, Map.of("version", 0, "reason", "复核", "proposedGrade", 70));
    as("ADMIN", null);
    reviews.decide(id, Map.of("version", 1, "approve", true, "reason", "同意"));
    db.update(
        "UPDATE cs2_enrollment SET grade=88,published_grade=88,grade_version=grade_version+1 WHERE"
            + " id='EACPAST'");
    as("TEACHER", "ACT1");
    assertThatThrownBy(() -> reviews.publish(id, Map.of("version", 2)))
        .isInstanceOfSatisfying(
            Api.Failure.class, e -> assertThat(e.code).isEqualTo("GRADE_CHANGED"));
    assertThat(officialGrade()).isEqualByComparingTo("88");
    as("STUDENT", "ACST1");
    String newer = request();
    assertThat(newer).isNotEqualTo(id);
    assertThat(reviews.get(id).get("state")).isEqualTo("STALE");
  }

  @Test
  void rejectedSuggestionLeavesOfficialGradeAndDrillCountsUnchanged() {
    String id = request();
    as("TEACHER", "ACT1");
    reviews.recommend(id, Map.of("version", 0, "reason", "复核", "proposedGrade", 50));
    as("ADMIN", null);
    reviews.decide(id, Map.of("version", 1, "approve", false, "reason", "缺少依据"));
    var drill = controller.drill("ACPAST", "GRADES", 0, 20);
    assertThat(drill.total()).isEqualTo(1);
    assertThat(Db.num(drill.items().get(0), "denominator")).isEqualTo(1);
    assertThat(Db.num(drill.items().get(0), "numerator")).isZero();
    assertThat(officialGrade()).isEqualByComparingTo("65");
  }
}
