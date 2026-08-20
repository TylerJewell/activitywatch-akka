package io.akka.activitywatch.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 rules 1–5, 7 and 10 — the decision that turns a ping into time. */
public class HeartbeatsTest {

  private static final Instant T = Corpus.EPOCH;

  private static Event at(double offset, double duration, Map<String, Object> data) {
    return Event.of(T.plus(Corpus.seconds(offset)), Corpus.seconds(duration), data);
  }

  @Test
  public void mergesOnlyWhenTheDataIsEqual() {
    // Rule 1.
    assertThat(Heartbeats.merge(at(0, 10, Map.of("app", "a")), at(11, 0, Map.of("app", "b")), 5))
        .isEmpty();
    assertThat(Heartbeats.merge(at(0, 0, Map.of("a", 1, "b", 2)),
        at(1, 0, Map.of("b", 2, "a", 1)), 5)).isPresent();
  }

  @Test
  public void theWindowIsClosedAtBothEnds() {
    // Rule 2. The far edge is the event's end plus the pulsetime; the near edge is the
    // event's own start, so a heartbeat inside it merges and one before it does not.
    assertThat(Heartbeats.merge(at(0, 10, Map.of("a", 1)), at(15, 0, Map.of("a", 1)), 5))
        .isPresent();
    assertThat(Heartbeats.merge(at(0, 10, Map.of("a", 1)), at(15.001, 0, Map.of("a", 1)), 5))
        .isEmpty();
    assertThat(Heartbeats.merge(at(0, 10, Map.of("a", 1)), at(5, 0, Map.of("a", 1)), 5))
        .isPresent();
    assertThat(Heartbeats.merge(at(10, 10, Map.of("a", 1)), at(5, 0, Map.of("a", 1)), 5))
        .isEmpty();
    assertThat(Heartbeats.merge(at(0, 10, Map.of("a", 1)), at(10, 0, Map.of("a", 1)), 0))
        .isPresent();
  }

  @Test
  public void theDurationIsMeasuredFromTheStartAndNeverShrinks() {
    // Rule 3.
    assertThat(Heartbeats.merge(at(0, 10, Map.of("a", 1)), at(12, 3, Map.of("a", 1)), 5))
        .get().extracting(Event::duration).isEqualTo(Corpus.seconds(15));
    assertThat(Heartbeats.merge(at(0, 10, Map.of("a", 1)), at(5, 0, Map.of("a", 1)), 5))
        .get().extracting(Event::duration).isEqualTo(Corpus.seconds(10));
  }

  @Test
  public void aNegativeDurationRefusesToMerge() {
    // Rule 4.
    assertThat(Heartbeats.merge(at(0, -1, Map.of("a", 1)), at(1, 0, Map.of("a", 1)), 5))
        .isEmpty();
  }

  @Test
  public void theMergeLeavesTheEventItWasGivenAlone() {
    // Rule 7. The original mutates the event it merged into and hands the same object back.
    Event last = at(0, 10, Map.of("a", 1));
    Optional<Event> merged = Heartbeats.merge(last, at(12, 3, Map.of("a", 1)), 5);
    assertThat(merged).isPresent();
    assertThat(merged.get()).isNotSameAs(last);
    assertThat(last.duration()).isEqualTo(Corpus.seconds(10));
  }

  @Test
  public void reducingDoesNotConsumeTheListItWasGiven() {
    // Rule 7. The original removes the first element from the caller's list.
    List<Event> events = new ArrayList<>(List.of(
        at(0, 0, Map.of("a", 1)), at(1, 0, Map.of("a", 1)), at(2, 0, Map.of("a", 1))));
    List<Event> reduced = Heartbeats.reduce(events, 5);
    assertThat(reduced).hasSize(1);
    assertThat(reduced.get(0).duration()).isEqualTo(Corpus.seconds(2));
    assertThat(events).hasSize(3);
  }

  @Test
  public void timestampsAreTruncatedToMillisecondsRatherThanRounded() {
    // Rule 10.
    Event e = Event.of(Instant.parse("2026-01-01T12:00:00.123999Z"), Duration.ZERO, Map.of());
    assertThat(e.timestamp()).isEqualTo(Instant.parse("2026-01-01T12:00:00.123Z"));
  }

  @Test
  public void everyMergeDecisionInTheCorpusIsReproduced() {
    // Rules 1–5 against every heartbeat the original was actually given.
    for (Corpus.Ingest ingest : Corpus.load().ingests()) {
      List<Event> written = new ArrayList<>();
      List<String> got = new ArrayList<>();
      for (Event heartbeat : ingest.heartbeats()) {
        Optional<Event> merged = written.isEmpty()
            ? Optional.empty()
            : Heartbeats.merge(written.get(written.size() - 1), heartbeat, ingest.pulsetime());
        if (merged.isPresent()) {
          written.set(written.size() - 1, merged.get());
          got.add("merge");
        } else {
          written.add(heartbeat);
          got.add("insert");
        }
      }
      assertThat(got)
          .as("actions for %s", ingest.name())
          .isEqualTo(ingest.decisions().stream().map(Corpus.Decision::action).toList());
    }
  }

  @Test
  public void everyResultingEventInTheCorpusIsReproduced() {
    // The event each decision produced, not only whether it merged.
    for (Corpus.Ingest ingest : Corpus.load().ingests()) {
      List<Event> written = new ArrayList<>();
      List<Event> produced = new ArrayList<>();
      for (Event heartbeat : ingest.heartbeats()) {
        Optional<Event> merged = written.isEmpty()
            ? Optional.empty()
            : Heartbeats.merge(written.get(written.size() - 1), heartbeat, ingest.pulsetime());
        if (merged.isPresent()) {
          written.set(written.size() - 1, merged.get());
          produced.add(merged.get());
        } else {
          written.add(heartbeat);
          produced.add(heartbeat);
        }
      }
      assertThat(produced)
          .as("events produced for %s", ingest.name())
          .isEqualTo(ingest.decisions().stream().map(Corpus.Decision::event).toList());
    }
  }
}
