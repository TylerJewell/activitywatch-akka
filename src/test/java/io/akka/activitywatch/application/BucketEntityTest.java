package io.akka.activitywatch.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.EventSourcedTestKit;
import io.akka.activitywatch.domain.BucketEvent;
import io.akka.activitywatch.domain.BucketState;
import io.akka.activitywatch.domain.Corpus;
import io.akka.activitywatch.domain.Event;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 §3 rules 6, 8, 9, 11 and 11a — the bucket as the thing that decides where one
 * stretch of activity ends.
 */
class BucketEntityTest {

  private static EventSourcedTestKit<BucketState, BucketEvent, BucketEntity> kit() {
    var testKit = EventSourcedTestKit.of("aw-watcher-window_host",
        context -> new BucketEntity(context, written -> {}));
    testKit.method(BucketEntity::create)
        .invoke(new BucketEntity.Create("currentwindow", "aw-watcher-window", "host", null));
    return testKit;
  }

  private static BucketEntity.Heartbeat beat(double offset, double duration,
      Map<String, Object> data, double pulsetime) {
    return new BucketEntity.Heartbeat(
        Event.of(Corpus.at(offset), Corpus.seconds(duration), data), pulsetime, null);
  }

  @Test
  void aRunOfEqualHeartbeatsIsOneGrowingEvent() {
    var testKit = kit();
    for (double offset : new double[] {0, 5, 10}) {
      testKit.method(BucketEntity::heartbeat).invoke(beat(offset, 0, Map.of("app", "editor"), 10));
    }
    var events = testKit.method(BucketEntity::events)
        .invoke(new BucketEntity.Range(null, null, null)).getReply();
    assertThat(events.events()).hasSize(1);
    assertThat(events.events().get(0).event().duration()).isEqualTo(Corpus.seconds(10));
  }

  @Test
  void onlyTheMostRecentlyWrittenEventIsEverConsidered() {
    // Rule 6. The heartbeat at 7 is older than the event at 10, so it starts its own event
    // even though it would have fitted the one before that.
    var testKit = kit();
    testKit.method(BucketEntity::heartbeat).invoke(beat(0, 0, Map.of("a", "x"), 5));
    testKit.method(BucketEntity::heartbeat).invoke(beat(10, 0, Map.of("a", "x"), 5));
    var late = testKit.method(BucketEntity::heartbeat).invoke(beat(7, 0, Map.of("a", "x"), 5));
    assertThat(late.getReply().action()).isEqualTo("insert");

    var next = testKit.method(BucketEntity::heartbeat).invoke(beat(9, 0, Map.of("a", "x"), 5));
    assertThat(next.getReply().action()).isEqualTo("merge");
    assertThat(next.getReply().id()).isEqualTo(late.getReply().id());
  }

  @Test
  void aMergeRewritesTheEventItMergedIntoAndNotTheLatestOne() {
    // Rule 8. This is the case where the original's own two storage backends disagree.
    var testKit = kit();
    testKit.method(BucketEntity::heartbeat).invoke(beat(22, 0, Map.of("s", "b"), 5));
    var late = testKit.method(BucketEntity::heartbeat).invoke(beat(21, 0, Map.of("s", "b"), 5));
    testKit.method(BucketEntity::heartbeat).invoke(beat(23, 0, Map.of("s", "b"), 5));

    var events = testKit.method(BucketEntity::events)
        .invoke(new BucketEntity.Range(null, null, null)).getReply();
    assertThat(events.events()).hasSize(2);
    var rewritten = events.events().stream()
        .filter(s -> s.id() == late.getReply().id()).findFirst().orElseThrow();
    assertThat(rewritten.event().duration()).isEqualTo(Corpus.seconds(2));
    var untouched = events.events().stream()
        .filter(s -> s.id() != late.getReply().id()).findFirst().orElseThrow();
    assertThat(untouched.event().timestamp()).isEqualTo(Corpus.at(22));
    assertThat(untouched.event().duration()).isEqualTo(Corpus.seconds(0));
  }

  @Test
  void theEventNextComparedAgainstIsRecoveredFromTheJournal() {
    // Rule 9. Replaying the journal into a fresh entity gives the same "most recently
    // written", so a restart cannot change which event the next heartbeat is compared to.
    var testKit = kit();
    List<Object> journal = new ArrayList<>();
    journal.addAll(testKit.method(BucketEntity::heartbeat)
        .invoke(beat(22, 0, Map.of("s", "b"), 5)).getAllEvents());
    journal.addAll(testKit.method(BucketEntity::heartbeat)
        .invoke(beat(21, 0, Map.of("s", "b"), 5)).getAllEvents());

    BucketState state = BucketState.empty("aw-watcher-window_host")
        .withCreated("currentwindow", "aw-watcher-window", "host", BucketState.DEFAULT_RETAINED);
    for (Object event : journal) {
      state = state.with((BucketEvent) event);
    }
    assertThat(state.lastWritten().orElseThrow().event().timestamp()).isEqualTo(Corpus.at(21));
  }

  @Test
  void eventsComeBackNewestFirst() {
    // Rule 11.
    var testKit = kit();
    testKit.method(BucketEntity::heartbeat).invoke(beat(0, 0, Map.of("a", "x"), 5));
    testKit.method(BucketEntity::heartbeat).invoke(beat(40, 0, Map.of("a", "y"), 5));
    var events = testKit.method(BucketEntity::events)
        .invoke(new BucketEntity.Range(null, null, null)).getReply();
    assertThat(events.events().stream().map(s -> s.event().timestamp()).toList())
        .containsExactly(Corpus.at(40), Corpus.at(0));
  }

  @Test
  void aRangeSelectsEveryOverlappingEventWholeAndInclusively() {
    // Rule 11a.
    var testKit = kit();
    testKit.method(BucketEntity::heartbeat).invoke(beat(0, 10, Map.of("a", "x"), 5));
    testKit.method(BucketEntity::heartbeat).invoke(beat(40, 10, Map.of("a", "y"), 5));

    var touching = testKit.method(BucketEntity::events)
        .invoke(new BucketEntity.Range(Corpus.at(10).toEpochMilli(),
            Corpus.at(40).toEpochMilli(), null)).getReply();
    assertThat(touching.events()).hasSize(2);
    assertThat(touching.events().get(1).event().duration()).isEqualTo(Corpus.seconds(10));

    var between = testKit.method(BucketEntity::events)
        .invoke(new BucketEntity.Range(Corpus.at(11).toEpochMilli(),
            Corpus.at(39).toEpochMilli(), null)).getReply();
    assertThat(between.events()).isEmpty();

    var newest = testKit.method(BucketEntity::events)
        .invoke(new BucketEntity.Range(null, null, 1)).getReply();
    assertThat(newest.events()).hasSize(1);
    assertThat(newest.events().get(0).event().timestamp()).isEqualTo(Corpus.at(40));
  }

  @Test
  void aBucketKeepsOnlyItsMostRecentEventsAndSaysSo() {
    // SPEC-001 §4 OD-8. The count keeps rising after the retained window stops growing.
    var testKit = EventSourcedTestKit.of("small", context -> new BucketEntity(context, w -> {}));
    testKit.method(BucketEntity::create)
        .invoke(new BucketEntity.Create("test", "probe", "host", 3));
    for (int i = 0; i < 5; i++) {
      testKit.method(BucketEntity::heartbeat).invoke(beat(i * 100, 0, Map.of("n", i), 5));
    }
    var events = testKit.method(BucketEntity::events)
        .invoke(new BucketEntity.Range(null, null, null)).getReply();
    assertThat(events.events()).hasSize(3);
    assertThat(events.complete()).isFalse();
    assertThat(events.count()).isEqualTo(5);
  }

  @Test
  void aHeartbeatCarryingATokenAlreadySeenChangesNothing() {
    // SPEC-001 §3 rule 6a. Delivery to the bucket is at-least-once, and a heartbeat that
    // starts a new event is not idempotent on its own — sent twice it would leave two.
    var testKit = kit();
    var first = testKit.method(BucketEntity::heartbeat).invoke(
        new BucketEntity.Heartbeat(
            Event.of(Corpus.at(0), Corpus.seconds(0), Map.of("a", "x")), 5, "watcher:1:0"));
    assertThat(first.getReply().action()).isEqualTo("insert");

    var again = testKit.method(BucketEntity::heartbeat).invoke(
        new BucketEntity.Heartbeat(
            Event.of(Corpus.at(0), Corpus.seconds(0), Map.of("a", "x")), 5, "watcher:1:0"));
    assertThat(again.getReply().action()).isEqualTo("duplicate");
    assertThat(again.didPersistEvents()).isFalse();

    var events = testKit.method(BucketEntity::events)
        .invoke(new BucketEntity.Range(null, null, null)).getReply();
    assertThat(events.events()).hasSize(1);
  }

  @Test
  void aBucketCannotBeAskedToKeepMoreThanTheCeiling() {
    // A bucket's state has to fit in what the runtime will replicate, so the number of
    // events it keeps is not entirely the caller's to choose.
    var testKit = EventSourcedTestKit.of("greedy", context -> new BucketEntity(context, w -> {}));
    var result = testKit.method(BucketEntity::create)
        .invoke(new BucketEntity.Create("test", "probe", "host", 10_000_000));
    assertThat(result.isError()).isTrue();
  }

  @Test
  void aHeartbeatIntoABucketThatWasNeverCreatedIsRefused() {
    var testKit = EventSourcedTestKit.of("missing", context -> new BucketEntity(context, w -> {}));
    var result = testKit.method(BucketEntity::heartbeat).invoke(beat(0, 0, Map.of("a", "x"), 5));
    assertThat(result.isError()).isTrue();
  }

  @Test
  void everyIngestCaseInTheCorpusIsReproduced() {
    // Rules 6 and 8 over the whole corpus: the bucket the port holds after the same
    // heartbeats the original was given.
    int caseNumber = 0;
    for (Corpus.Ingest ingest : Corpus.load().ingests()) {
      var testKit = EventSourcedTestKit.of("corpus-" + caseNumber++,
          context -> new BucketEntity(context, w -> {}));
      testKit.method(BucketEntity::create)
          .invoke(new BucketEntity.Create("test", "probe", "host", null));

      List<String> actions = new ArrayList<>();
      for (Event heartbeat : ingest.heartbeats()) {
        actions.add(testKit.method(BucketEntity::heartbeat)
            .invoke(new BucketEntity.Heartbeat(heartbeat, ingest.pulsetime(), null))
            .getReply().action());
      }
      assertThat(actions)
          .as("actions for %s", ingest.name())
          .isEqualTo(ingest.decisions().stream().map(Corpus.Decision::action).toList());

      var events = testKit.method(BucketEntity::events)
          .invoke(new BucketEntity.Range(null, null, null)).getReply();
      assertThat(events.events().stream().map(BucketState.Stored::event).toList())
          .as("bucket for %s", ingest.name())
          .isEqualTo(ingest.bucket());
    }
  }
}
