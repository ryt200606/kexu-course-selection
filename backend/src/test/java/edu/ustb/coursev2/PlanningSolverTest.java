package edu.ustb.coursev2;

import static edu.ustb.coursev2.AllocationSolver.*;
import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class PlanningSolverTest {
  static Slot slot(int day, String room) {
    return new Slot(day, 1, 2, 1, 16, room);
  }

  static Choice choice(String st, String section, String offering, int rank, int day) {
    return new Choice(st, section, offering, rank, 3, List.of(slot(day, "R" + day)), List.of());
  }

  @Test
  void matchesExhaustiveOracleWithCreditsCapacityTimesAndDuplicateCourse() {
    for (int seed = 0; seed < 10; seed++) {
      var r = new Random(seed);
      List<Choice> choices = new ArrayList<>();
      for (String student : List.of("A", "B", "C"))
        for (int rank = 1; rank <= 3; rank++) {
          int sec = (rank + r.nextInt(3)) % 4;
          if (choices.stream()
              .anyMatch(c -> c.studentId().equals(student) && c.sectionId().equals("S" + sec)))
            continue;
          choices.add(choice(student, "S" + sec, "O" + (sec % 3), rank, sec % 2 + 1));
        }
      Input input =
          new Input(
              "oracle" + seed,
              Map.of("S0", 1, "S1", 1, "S2", 2, "S3", 1),
              Map.of("A", 6, "B", 3, "C", 6),
              choices);
      Score best = score(List.of());
      for (int mask = 0; mask < (1 << choices.size()); mask++) {
        List<Choice> selected = new ArrayList<>();
        boolean valid = true;
        for (int i = 0; i < choices.size(); i++)
          if ((mask & (1 << i)) != 0) {
            Choice c = choices.get(i);
            var previous =
                selected.stream().filter(a -> a.studentId().equals(c.studentId())).toList();
            if (previous.stream().anyMatch(c::clashes)
                || previous.stream().mapToInt(Choice::credits).sum() + c.credits()
                    > input.budgets().get(c.studentId())
                || selected.stream().filter(a -> a.sectionId().equals(c.sectionId())).count()
                    >= input.seats().get(c.sectionId())) {
              valid = false;
              break;
            }
            selected.add(c);
          }
        var s = score(assigned(selected));
        if (valid && compare(s, best) > 0) best = s;
      }
      var solved = solve(input);
      assertThat(solved.solverStatus()).isEqualTo("OPTIMAL");
      assertThat(solved.metrics()).isEqualTo(best);
      assertThat(compare(solved.metrics(), solved.greedy())).isGreaterThanOrEqualTo(0);
      assertThat(solve(input).assignments()).isEqualTo(solved.assignments());
    }
  }

  @Test
  void maximumCoverageRearrangesFlexibleStudentAndPermitsMultipleCourses() {
    var input =
        new Input(
            "frozen-seed",
            Map.of("X", 1, "Y", 1, "Z", 2),
            Map.of("A", 6, "B", 6),
            List.of(
                choice("A", "X", "OX", 1, 1),
                choice("A", "Y", "OY", 2, 2),
                choice("A", "Z", "OZ", 3, 3),
                choice("B", "X", "OX", 1, 1),
                choice("B", "Z", "OZ", 2, 3)));
    var best = solve(input);
    assertThat(best.metrics().covered()).isEqualTo(2);
    assertThat(best.assignments()).hasSize(4);
    assertThat(best.provenObjectives()).contains("covered", "rank1", "seedTie");
  }

  @Test
  void coverageBeatsSeededGreedyWhenFlexibleStudentCanMove() {
    // This committed seed orders B before A; B can move to Y, while A has only X.
    var input =
        new Input(
            "teaching-demo",
            Map.of("X", 1, "Y", 1),
            Map.of("A", 3, "B", 3),
            List.of(
                choice("B", "X", "OX", 1, 1),
                choice("B", "Y", "OY", 2, 2),
                choice("A", "X", "OX", 1, 1)));
    var best = solve(input);
    assertThat(best.metrics().covered()).isEqualTo(2);
    assertThat(best.greedy().covered()).isEqualTo(1);
  }

  @Test
  void weeklyTeacherGapsTakePriorityOverInputCandidateOrder() {
    var rooms = List.of(new ScheduleSolver.Room("A", 30));
    var existing = new ScheduleSolver.Booking("E", "T", new Slot(1, 1, 2, 1, 4, "A"));
    var close = new Slot(1, 3, 4, 1, 4, "A");
    var far = new Slot(1, 7, 8, 1, 4, "A");
    var d = new ScheduleSolver.Demand("D", "T", 20, -1, List.of(far, close));
    var input = new ScheduleSolver.Input(rooms, List.of(d), List.of(), List.of(existing));
    var result = ScheduleSolver.solve(input);
    assertThat(result.assignments().get(0).slot()).isEqualTo(close);
    assertThat(ScheduleSolver.teacherGaps(input, result.assignments())).isZero();
    assertThat(
            ScheduleSolver.teacherGaps(
                input, List.of(new ScheduleSolver.Choice("D", "T", 0, 10, far))))
        .isEqualTo(16);
  }

  @Test
  void schedulingHonorsWeekRoomCapacityAvailabilityAndFixedSlots() {
    var rooms = List.of(new ScheduleSolver.Room("A", 30), new ScheduleSolver.Room("B", 10));
    var bad = slot(1, "A");
    var free = slot(2, "A");
    var one = new ScheduleSolver.Demand("S1", "T", 20, -1, List.of(bad, free, slot(2, "B")));
    var two =
        new ScheduleSolver.Demand("S2", "T", 10, 0, List.of(new Slot(2, 1, 2, 17, 20, "A"), free));
    var blocks = List.of(new ScheduleSolver.Unavailable("T", "", bad));
    var res =
        ScheduleSolver.solve(new ScheduleSolver.Input(rooms, List.of(one, two), blocks, List.of()));
    assertThat(res.solverStatus()).isEqualTo("OPTIMAL");
    assertThat(res.scheduled()).isEqualTo(2);
    assertThat(res.assignments().get(0).slot()).isEqualTo(free);
    assertThat(res.excluded())
        .extracting(m -> m.get("reason"))
        .contains("UNAVAILABLE", "LOCKED_SLOT", "ROOM_CAPACITY");
  }
}
