package io.akka.activitywatch.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What a range asks for and what shape the answer takes — SPEC-001 §3 R33–R37.
 *
 * <p>These rules used to live inside the bucket, and moved out when a bucket's history moved
 * into pages: a range that spans both is answered from both, and rounding, clipping, ordering
 * and limiting have to happen once over the two put together. Applying them per source would
 * clip an already-clipped event and limit an already-limited list.
 */
class EventSelectionTest {

  private static final Instant T0 = Instant.parse("2020-01-01T00:00:00Z");

  private static Event event(long id, double at, double duration, Object value) {
    return Event.of(T0.plusSeconds((long) at), duration, Map.of("a", value)).withId(id);
  }

  private static final List<Event> THREE = List.of(
      event(1, 0, 10, 1), event(2, 20, 5, 2), event(3, 30, 5, 3));

  @Test
  void aRangeSelectsEveryOverlapInclusiveAtBothEnds() {
    assertEquals(1, EventSelection.answer(THREE, T0.plusSeconds(10), T0.plusSeconds(10), -1)
        .size(), "a range touching only an event's last instant still selects it");
    assertEquals(3, EventSelection.answer(THREE, null, null, -1).size());
  }

  @Test
  void aSelectedEventIsCutToTheRange() {
    Event only = EventSelection.answer(List.of(event(1, 0, 10, 1)),
        T0.plusSeconds(2), T0.plusSeconds(4), -1).get(0);
    assertEquals(T0.plusSeconds(2), only.timestamp());
    assertEquals(2.001, only.durationSeconds(), 1e-9,
        "the range's end is rounded up to the next millisecond before the cut");
  }

  @Test
  void aRangesEdgesAreRoundedOutwards() {
    Event only = EventSelection.answer(List.of(event(1, 0, 10, 1)),
        T0.plusNanos(1_500_000), T0.plusSeconds(5), -1).get(0);
    assertEquals(T0.plusMillis(1), only.timestamp(), "the start is rounded down");
    assertEquals(5.0, only.durationSeconds(), 1e-6);
  }

  @Test
  void aSecondThatOverflowsRoundsIntoTheNextOne() {
    assertEquals(Instant.parse("2020-01-01T00:00:01Z"),
        EventSelection.ceilToMillis(Instant.parse("2020-01-01T00:00:00.999500Z")));
  }

  @Test
  void eventsComeBackNewestFirstAndALimitTakesTheNewest() {
    List<Event> limited = EventSelection.answer(THREE, null, null, 1);
    assertEquals(1, limited.size());
    assertEquals(3, limited.get(0).data().get("a"));
    assertEquals(0, EventSelection.answer(THREE, null, null, 0).size(),
        "a limit of zero is nothing, not everything");
    assertEquals(3, EventSelection.answer(THREE, null, null, -1).size(),
        "a negative limit is no limit");
  }

  @Test
  void countingUsesTheSameSelectionWithoutTheLimitOrTheRounding() {
    assertEquals(3L, EventSelection.count(THREE, null, null));
    assertEquals(3L, EventSelection.count(THREE, T0.plusSeconds(5), null),
        "counting from halfway through an event includes it");
    assertEquals(1L, EventSelection.count(THREE, null, T0.plusSeconds(5)));
  }

  @Test
  void anEventInBothSourcesIsAnsweredOnce() {
    List<Event> both = new ArrayList<>(THREE);
    both.add(event(2, 20, 5, 2));
    assertEquals(3, EventSelection.answer(both, null, null, -1).size());
    assertEquals(3L, EventSelection.count(both, null, null));
  }

  @Test
  void theBucketsOwnCopyOfAnEventIsTheOneKept() {
    // The bucket is asked first, so where a page has not caught up with a lengthened event
    // the bucket's longer copy is what a caller reads.
    List<Event> both = List.of(event(1, 0, 30, 1), event(1, 0, 10, 1));
    assertEquals(30.0, EventSelection.answer(both, null, null, -1).get(0).durationSeconds(),
        1e-9);
  }

  @Test
  void aPageIsNamedForItsBucketAndTheDayTheEventStartsIn() {
    assertEquals("b_2020-01-01", EventSelection.pageOf("b", T0));
    assertEquals("b_2020-01-01",
        EventSelection.pageOf("b", Instant.parse("2020-01-01T23:59:59.999Z")));
    assertEquals("b_2020-01-02", EventSelection.pageOf("b", T0.plusSeconds(86_400)));
  }

  @Test
  void arangeAsksTheDayBeforeItAsWell() {
    List<String> known = List.of("b_2019-12-31", "b_2020-01-01", "b_2020-01-02",
        "b_2020-01-03");
    List<String> wanted = EventSelection.pagesFor("b", T0.plusSeconds(3600),
        T0.plusSeconds(3600 * 2), known);
    assertTrue(wanted.contains("b_2019-12-31"),
        "an event that started yesterday can still reach into today");
    assertTrue(wanted.contains("b_2020-01-01"));
    assertTrue(!wanted.contains("b_2020-01-02"), "and nothing after the range is asked");
  }

  @Test
  void aPageIsNameableAsAnEntity() {
    // An entity id is part of an address, so a page name may not contain what the runtime
    // reserves for its own: the failure is at run time, on the first write, not at compile
    // time.
    String page = EventSelection.pageOf("aw-watcher-window_host.example.com", T0);
    for (char reserved : "|,/?#[]@!$&'()*+;=%".toCharArray()) {
      assertTrue(page.indexOf(reserved) < 0, "a page name holds no " + reserved);
    }
  }

  @Test
  void aRangeWithNoEndsAsksEveryPage() {
    List<String> known = List.of("b_2019-12-31", "b_2020-01-01");
    assertEquals(known, EventSelection.pagesFor("b", null, null, known));
  }
}
