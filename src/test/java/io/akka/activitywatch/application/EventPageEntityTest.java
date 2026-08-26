package io.akka.activitywatch.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.testkit.EventSourcedTestKit;
import io.akka.activitywatch.domain.BucketEvent;
import io.akka.activitywatch.domain.Event;
import io.akka.activitywatch.domain.PageState;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A day of one bucket's history.
 *
 * <p>The rule worth checking twice is that a write can be handed to a page more than once. The
 * consumer that feeds these is at-least-once by design — that is what makes a write survive a
 * crash between the bucket and the page — so applying the same event twice has to leave the
 * page exactly as one application would.
 */
class EventPageEntityTest {

  private static final Instant T0 = Instant.parse("2020-01-01T00:00:00Z");

  private static EventSourcedTestKit<PageState, BucketEvent, EventPageEntity> page() {
    return EventSourcedTestKit.of("b_2020-01-01", EventPageEntity::new);
  }

  private static Event event(double at, double duration, Object value) {
    return Event.of(T0.plusSeconds((long) at), duration, Map.of("a", value));
  }

  @Test
  void anEventIsKept() {
    var testKit = page();
    assertTrue(testKit.method(EventPageEntity::apply)
        .invoke(EventPageEntity.Apply.of(new BucketEvent.Inserted(1, event(0, 10, 1)))).getReply());
    var events = testKit.method(EventPageEntity::all).invoke().getReply().events();
    assertEquals(1, events.size());
    assertEquals(1L, events.get(0).id());
  }

  @Test
  void theSameWriteTwiceLeavesOneEvent() {
    var testKit = page();
    testKit.method(EventPageEntity::apply).invoke(EventPageEntity.Apply.of(new BucketEvent.Inserted(1, event(0, 10, 1))));
    assertFalse(testKit.method(EventPageEntity::apply)
        .invoke(EventPageEntity.Apply.of(new BucketEvent.Inserted(1, event(0, 10, 1)))).getReply(),
        "the second delivery changes nothing and persists nothing");
    assertEquals(1, testKit.method(EventPageEntity::all).invoke().getReply().events().size());
    assertEquals(1, testKit.getAllEvents().size(),
        "and the journal did not grow for a delivery that changed nothing");
  }

  @Test
  void lengtheningAnEventReachesTheEventItNames() {
    var testKit = page();
    testKit.method(EventPageEntity::apply).invoke(EventPageEntity.Apply.of(new BucketEvent.Inserted(1, event(0, 10, 1))));
    testKit.method(EventPageEntity::apply).invoke(EventPageEntity.Apply.of(new BucketEvent.Inserted(2, event(20, 5, 2))));
    assertTrue(testKit.method(EventPageEntity::apply)
        .invoke(EventPageEntity.Apply.of(new BucketEvent.Extended(1, java.time.Duration.ofSeconds(15)))).getReply());

    var events = testKit.method(EventPageEntity::all).invoke().getReply().events();
    assertEquals(15.0, events.get(0).durationSeconds(), 1e-9);
    assertEquals(5.0, events.get(1).durationSeconds(), 1e-9);
  }

  @Test
  void aChangeNamingAnEventThisPageDoesNotHoldIsIgnored() {
    var testKit = page();
    testKit.method(EventPageEntity::apply).invoke(EventPageEntity.Apply.of(new BucketEvent.Inserted(1, event(0, 10, 1))));
    assertFalse(testKit.method(EventPageEntity::apply)
        .invoke(EventPageEntity.Apply.of(new BucketEvent.Extended(99, java.time.Duration.ofSeconds(15)))).getReply(),
        "a change is offered to every page and only the one holding the event takes it");
  }

  @Test
  void removingAnEventTakesItOut() {
    var testKit = page();
    testKit.method(EventPageEntity::apply).invoke(EventPageEntity.Apply.of(new BucketEvent.Inserted(1, event(0, 10, 1))));
    testKit.method(EventPageEntity::apply).invoke(EventPageEntity.Apply.of(new BucketEvent.Removed(1)));
    assertEquals(0, testKit.method(EventPageEntity::all).invoke().getReply().events().size());
  }

  @Test
  void deletingTheBucketEmptiesThePage() {
    var testKit = page();
    testKit.method(EventPageEntity::apply).invoke(EventPageEntity.Apply.of(new BucketEvent.Inserted(1, event(0, 10, 1))));
    testKit.method(EventPageEntity::apply).invoke(EventPageEntity.Apply.of(new BucketEvent.Deleted()));
    assertEquals(0, testKit.method(EventPageEntity::all).invoke().getReply().events().size());
  }

  @Test
  void aRangeSelectsTheOverlapsUncut() {
    var testKit = page();
    testKit.method(EventPageEntity::apply).invoke(EventPageEntity.Apply.of(new BucketEvent.Inserted(1, event(0, 10, 1))));
    testKit.method(EventPageEntity::apply).invoke(EventPageEntity.Apply.of(new BucketEvent.Inserted(2, event(60, 10, 2))));
    var selected = testKit.method(EventPageEntity::overlapping)
        .invoke(new EventPageEntity.Range(T0.plusSeconds(5).toEpochMilli(),
            T0.plusSeconds(6).toEpochMilli())).getReply().events();
    assertEquals(1, selected.size());
    assertEquals(10.0, selected.get(0).durationSeconds(), 1e-9,
        "uncut: the caller shapes the answer once, over every source together");
  }

  @Test
  void aPageKnowsWhichBucketAndDayItIs() {
    assertEquals("b", PageState.empty("b_2020-01-01").bucket());
    assertEquals("2020-01-01", PageState.empty("b_2020-01-01").day());
    assertEquals("aw-watcher-window_host",
        PageState.empty("aw-watcher-window_host_2020-01-01").bucket());
  }
}
