package io.akka.activitywatch.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.testkit.EventSourcedTestKit;
import io.akka.activitywatch.domain.BucketEvent;
import io.akka.activitywatch.domain.BucketState;
import io.akka.activitywatch.domain.Event;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A bucket, and everything storage decides — SPEC-001 §3 R32–R41.
 *
 * <p>The rules that are easy to get subtly wrong are the ones about a *range*: which events it
 * selects, whether they come back whole or cut, and what the range's own edges are rounded to.
 * All three change what a caller reads, and none of them is visible from a route.
 */
class BucketEntityTest {

  private static final Instant T0 = Instant.parse("2020-01-01T00:00:00Z");

  /** What the entity pushed, kept so a test can look at it. */
  private static final List<BucketEntity.Change> published = new java.util.ArrayList<>();

  /**
   * The notification publisher a test supplies.
   *
   * <p>The test kit hands an entity its context and nothing else, so anything else the
   * constructor takes is the test's to provide. Recording rather than discarding, because
   * "a write is pushed to whoever is watching" is a rule and a test that threw the pushes
   * away could not check it.
   */
  private static final akka.javasdk.NotificationPublisher<BucketEntity.Change> RECORDER =
      published::add;

  private static EventSourcedTestKit<BucketState, BucketEvent, BucketEntity> bucket() {
    return bucket(0);
  }

  private static EventSourcedTestKit<BucketState, BucketEvent, BucketEntity> bucket(
      int retained) {
    published.clear();
    var testKit = EventSourcedTestKit.of("b",
        context -> new BucketEntity(context, RECORDER));
    testKit.method(BucketEntity::create).invoke(new BucketEntity.Create(
        null, "currentwindow", "test", "host", "2020-01-01T00:00:00+00:00", Map.of(), retained));
    return testKit;
  }

  private static Event event(double at, double duration, Object value) {
    return Event.of(T0.plus(Duration.ofMillis((long) (at * 1000))), duration,
        Map.of("a", value));
  }

  private static void insert(
      EventSourcedTestKit<BucketState, BucketEvent, BucketEntity> testKit, Event... events) {
    testKit.method(BucketEntity::insert).invoke(List.of(events));
  }

  @Test
  void aBucketThatExistsIsNotCreatedAgain() {
    var testKit = bucket();
    var again = testKit.method(BucketEntity::create).invoke(new BucketEntity.Create(
        null, "other", "other", "other", "2021-01-01T00:00:00+00:00", Map.of(), 0));
    assertEquals(Boolean.FALSE, again.getReply());
    assertEquals("currentwindow", testKit.getState().type(),
        "creating it again changes nothing about it");
  }

  @Test
  void eventIdentitiesStartAtOneAndAreNotReused() {
    var testKit = bucket();
    var written = testKit.method(BucketEntity::insert)
        .invoke(List.of(event(0, 10, 1))).getReply();
    assertEquals(1L, written.inserted().get(0).id());

    insert(testKit, event(20, 5, 2));
    testKit.method(BucketEntity::deleteEvent).invoke(2L);
    var next = testKit.method(BucketEntity::insert)
        .invoke(List.of(event(30, 5, 3))).getReply();
    assertEquals(3L, next.inserted().get(0).id(),
        "an identity that was used is not handed out again");
  }

  @Test
  void oneEventComesBackWithItsIdentityAndABatchComesBackEmpty() {
    var testKit = bucket();
    assertEquals(1, testKit.method(BucketEntity::insert)
        .invoke(List.of(event(0, 1, 1))).getReply().inserted().size());
    assertEquals(0, testKit.method(BucketEntity::insert)
        .invoke(List.of(event(10, 1, 2), event(20, 1, 3))).getReply().inserted().size(),
        "a batch cannot report identities, and the original's answer is an empty list");
  }

  @Test
  void aRangeSelectsEveryOverlapInclusiveAtBothEnds() {
    var testKit = bucket();
    insert(testKit, event(0, 10, 1), event(20, 5, 2), event(30, 5, 3));

    var touchingTheEnd = testKit.method(BucketEntity::overlapping)
        .invoke(new BucketEntity.Range(T0.plusSeconds(10).toEpochMilli(),
            T0.plusSeconds(10).toEpochMilli(), -1)).getReply();
    assertEquals(1, touchingTheEnd.events().size(),
        "a range touching only an event's last instant still selects it");
  }

  @Test
  void aBucketAnswersWithWhatItHoldsUncut() {
    // The shaping -- rounding, clipping, ordering, limiting -- happens once, after this and
    // the pages have been put together. EventSelectionTest is where those rules are checked.
    var testKit = bucket();
    insert(testKit, event(0, 10, 1));
    var answered = testKit.method(BucketEntity::overlapping)
        .invoke(new BucketEntity.Range(T0.plusSeconds(2).toEpochMilli(),
            T0.plusSeconds(4).toEpochMilli(), -1)).getReply();
    Event only = answered.events().get(0);
    assertEquals(T0, only.timestamp());
    assertEquals(10.0, only.durationSeconds(), 1e-9);
  }

  @Test
  void aBucketKeepsOnlyItsRecentEventsAndNamesThePagesForTheRest() {
    var testKit = bucket();
    for (int i = 0; i < BucketState.RECENT_WINDOW + 50; i++) {
      insert(testKit, event(i, 1, i));
    }
    var info = testKit.method(BucketEntity::info).invoke().getReply();
    assertEquals(BucketState.RECENT_WINDOW, info.recent().size(),
        "the record the runtime copies between regions stays a fixed size");
    assertEquals(BucketState.RECENT_WINDOW + 50, info.count(),
        "and still knows how many events the bucket has");
    assertTrue(info.complete(),
        "nothing was forgotten: the rest is in the pages, not gone");
    assertEquals(List.of("b_2020-01-01"), info.pages());
  }

  @Test
  void aBucketThatSpansDaysNamesEveryDayItWroteTo() {
    var testKit = bucket();
    insert(testKit, event(0, 1, 1));
    insert(testKit, event(60 * 60 * 24, 1, 2));
    insert(testKit, event(60 * 60 * 24 * 2, 1, 3));
    assertEquals(List.of("b_2020-01-01", "b_2020-01-02", "b_2020-01-03"),
        testKit.method(BucketEntity::info).invoke().getReply().pages());
  }

  @Test
  void insertingAnEventThatAlreadyHasAnIdentityReplacesIt() {
    var testKit = bucket();
    insert(testKit, event(0, 10, 1), event(20, 5, 2));
    testKit.method(BucketEntity::insert)
        .invoke(List.of(event(99, 1, 99).withId(1L), event(50, 1, 50)));

    var all = testKit.method(BucketEntity::overlapping)
        .invoke(new BucketEntity.Range(null, null, -1)).getReply();
    assertEquals(3, all.events().size(), "one was replaced and one added");
    assertEquals(99, testKit.method(BucketEntity::event).invoke(1L).getReply()
        .event().data().get("a"));
  }

  @Test
  void replaceLastRewritesTheEventWithTheGreatestTimestamp() {
    var testKit = bucket();
    // The first starts earlier and ends later; the second starts later and ends earlier.
    // The two readings of "last" the original's backends take part company exactly here.
    insert(testKit, event(0, 100, 1));
    insert(testKit, event(10, 5, 2));
    testKit.method(BucketEntity::replaceLast).invoke(event(50, 1, 99));

    assertEquals(1, testKit.method(BucketEntity::event).invoke(1L).getReply()
        .event().data().get("a"), "the one that ends last is left alone");
    assertEquals(99, testKit.method(BucketEntity::event).invoke(2L).getReply()
        .event().data().get("a"), "the one that starts last is the one rewritten");
  }

  @Test
  void deletingAnEventThatIsNotThereIsNotAnError() {
    var testKit = bucket();
    insert(testKit, event(0, 1, 1));
    assertTrue(testKit.method(BucketEntity::deleteEvent).invoke(1L).getReply());
    assertFalse(testKit.method(BucketEntity::deleteEvent).invoke(999L).getReply());
  }

  @Test
  void deletingABucketLeavesNothingBehind() {
    var testKit = bucket();
    insert(testKit, event(0, 1, 1));
    assertTrue(testKit.method(BucketEntity::delete).invoke().getReply());
    assertFalse(testKit.method(BucketEntity::info).invoke().getReply().exists());
    assertFalse(testKit.method(BucketEntity::delete).invoke().getReply(),
        "deleting it twice says nothing happened the second time");
  }

  @Test
  void aRetentionCapDropsTheOldestAndSaysTheHistoryIsShort() {
    var testKit = bucket(2);
    insert(testKit, event(0, 1, 1));
    insert(testKit, event(10, 1, 2));
    assertTrue(testKit.method(BucketEntity::info).invoke().getReply().complete());

    insert(testKit, event(20, 1, 3));
    var info = testKit.method(BucketEntity::info).invoke().getReply();
    assertFalse(info.complete(), "a bucket that dropped something says so");
    assertEquals(2, info.count());
  }

  @Test
  void aRunOfHeartbeatsBecomesOneEventAndThenAnother() {
    var testKit = bucket();
    var first = testKit.method(BucketEntity::heartbeat)
        .invoke(new BucketEntity.Heartbeat(event(0, 0, 1), 5)).getReply();
    assertEquals(BucketEntity.INSERTED, first.action());

    var merged = testKit.method(BucketEntity::heartbeat)
        .invoke(new BucketEntity.Heartbeat(event(3, 0, 1), 5)).getReply();
    assertEquals(BucketEntity.MERGED, merged.action());
    assertEquals(first.id(), merged.id());
    assertEquals(3.0, merged.event().durationSeconds(), 1e-9);

    var separate = testKit.method(BucketEntity::heartbeat)
        .invoke(new BucketEntity.Heartbeat(event(30, 0, 1), 5)).getReply();
    assertEquals(BucketEntity.INSERTED, separate.action());
    assertEquals(first.id() + 1, separate.id());
  }

  @Test
  void aHeartbeatIsComparedAgainstTheEventWrittenLastNotTheLatestOne() {
    var testKit = bucket();
    // Written in this order: a late one, then an early one. The event *written* last is the
    // early one, and that is what the next heartbeat is compared against -- which is why a
    // heartbeat two seconds after the early one merges rather than starting a third event.
    testKit.method(BucketEntity::heartbeat)
        .invoke(new BucketEntity.Heartbeat(event(100, 0, 1), 5));
    var early = testKit.method(BucketEntity::heartbeat)
        .invoke(new BucketEntity.Heartbeat(event(0, 0, 1), 5)).getReply();

    var merged = testKit.method(BucketEntity::heartbeat)
        .invoke(new BucketEntity.Heartbeat(event(2, 0, 1), 5)).getReply();
    assertEquals(BucketEntity.MERGED, merged.action());
    assertEquals(early.id(), merged.id());
  }

  @Test
  void aHeartbeatIntoAnEmptyBucketStartsAnEvent() {
    var testKit = bucket();
    var written = testKit.method(BucketEntity::heartbeat)
        .invoke(new BucketEntity.Heartbeat(event(0, 0, 1), 5)).getReply();
    assertEquals(BucketEntity.INSERTED, written.action());
    assertEquals(1L, written.id());
  }

  @Test
  void everyWriteIsPushedToWhoeverIsWatching() {
    var testKit = bucket();
    published.clear();
    testKit.method(BucketEntity::heartbeat)
        .invoke(new BucketEntity.Heartbeat(event(0, 0, 1), 5));
    testKit.method(BucketEntity::heartbeat)
        .invoke(new BucketEntity.Heartbeat(event(3, 0, 1), 5));
    testKit.method(BucketEntity::deleteEvent).invoke(1L);

    assertEquals(List.of("event-inserted", "event-extended", "event-removed"),
        published.stream().map(BucketEntity.Change::kind).toList());
    assertEquals(3.0, published.get(1).event().durationSeconds(), 1e-9,
        "the push carries the event as it now stands, so a watcher need not ask");
    assertEquals(0L, published.get(2).count(),
        "and how many events the bucket now holds");
  }

  @Test
  void theLastUpdateIsTheEndOfTheMostRecentEvent() {
    var testKit = bucket();
    insert(testKit, event(0, 10, 1), event(20, 5, 2));
    assertEquals(T0.plusSeconds(25).toString(),
        testKit.method(BucketEntity::info).invoke().getReply().lastUpdated());
  }
}
