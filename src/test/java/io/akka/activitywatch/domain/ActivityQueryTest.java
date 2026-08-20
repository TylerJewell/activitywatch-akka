package io.akka.activitywatch.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 rules 28–31 — selecting the active periods, clipping to them, adding up. */
public class ActivityQueryTest {

  private static Event at(double offset, double duration, Map<String, Object> data) {
    return Event.of(Corpus.at(offset), Corpus.seconds(duration), data);
  }

  @Test
  public void theActivePeriodsAreSelectedByExactValue() {
    // Rule 28.
    List<Event> idle = List.of(
        at(0, 10, Map.of("status", "not-afk")), at(10, 5, Map.of("status", "afk")));
    assertThat(Activities.filterKeyvals(idle, "status", List.of("not-afk")))
        .isEqualTo(List.of(at(0, 10, Map.of("status", "not-afk"))));
  }

  @Test
  public void anEventIsClippedToEveryPeriodItOverlaps() {
    // Rule 29.
    assertThat(Activities.filterPeriodIntersect(
        List.of(at(0, 20, Map.of("app", "x"))),
        List.of(at(2, 3, Map.of()), at(10, 4, Map.of()))))
        .isEqualTo(List.of(at(2, 3, Map.of("app", "x")), at(10, 4, Map.of("app", "x"))));
  }

  @Test
  public void touchingAtASingleInstantIsNotAnOverlap() {
    // Rule 29.
    assertThat(Activities.filterPeriodIntersect(
        List.of(at(0, 10, Map.of("app", "x"))), List.of(at(10, 5, Map.of())))).isEmpty();
  }

  @Test
  public void anEventWhollyInsideAPeriodSurvivesWhole() {
    // Rule 29.
    assertThat(Activities.filterPeriodIntersect(
        List.of(at(5, 2, Map.of("app", "x"))), List.of(at(0, 20, Map.of()))))
        .isEqualTo(List.of(at(5, 2, Map.of("app", "x"))));
  }

  @Test
  public void durationsAddUpPerKeyAndTheFirstTimestampIsKept() {
    // Rule 30. The original's own documentation says merged events have no timestamp; they
    // carry the first contributor's.
    assertThat(Activities.mergeEventsByKeys(
        List.of(at(0, 10, Map.of("app", "x")), at(20, 5, Map.of("app", "y")),
            at(30, 20, Map.of("app", "x"))),
        List.of("app")))
        .isEqualTo(List.of(at(0, 30, Map.of("app", "x")), at(20, 5, Map.of("app", "y"))));
  }

  @Test
  public void anEventMissingTheKeyFormsItsOwnGroup() {
    // Rule 30.
    assertThat(Activities.mergeEventsByKeys(
        List.of(at(0, 5, Map.of("app", "x")), at(9, 5, Map.of())), List.of("app")))
        .isEqualTo(List.of(at(0, 5, Map.of("app", "x")), at(9, 5, Map.of())));
  }

  @Test
  public void equalDurationsKeepTheOrderTheyCameIn() {
    // Rule 31.
    assertThat(Activities.sortByDuration(
        List.of(at(0, 5, Map.of("app", "a")), at(9, 5, Map.of("app", "b")))))
        .isEqualTo(List.of(at(0, 5, Map.of("app", "a")), at(9, 5, Map.of("app", "b"))));
  }

  @Test
  public void everyStageOfEveryActivityCaseInTheCorpusIsReproduced() {
    // Rules 28–31 end to end, against what the original produced.
    for (Corpus.Activity c : Corpus.load().activities()) {
      List<Event> notAfk = Activities.filterKeyvals(
          Flood.flood(c.afk(), c.pulsetime()), "status", List.of("not-afk"));
      assertThat(notAfk).as("active periods for %s", c.name()).isEqualTo(c.notAfk());

      List<Event> clipped =
          Activities.filterPeriodIntersect(Flood.flood(c.window(), c.pulsetime()), notAfk);
      assertThat(clipped).as("clipped events for %s", c.name()).isEqualTo(c.clipped());

      assertThat(Activities.query(c.window(), c.afk(), c.pulsetime(), c.keys()))
          .as("activities for %s", c.name())
          .isEqualTo(c.activities());
    }
  }
}
