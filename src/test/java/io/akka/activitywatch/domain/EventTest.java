package io.akka.activitywatch.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The only record in the system — SPEC-001 §3 R1–R3. */
class EventTest {

  private static final Instant T0 = Instant.parse("2020-01-01T00:00:00Z");

  @Test
  void subMillisecondPrecisionIsCutOffRatherThanRounded() {
    assertEquals(Instant.parse("2020-01-01T00:00:00.123Z"),
        Event.of(Instant.parse("2020-01-01T00:00:00.123456Z"), 1d, Map.of()).timestamp());
    assertEquals(T0,
        Event.of(Instant.parse("2020-01-01T00:00:00.000999Z"), 1d, Map.of()).timestamp(),
        "a value just under a millisecond becomes nothing, not one millisecond");
  }

  @Test
  void twoEventsAreTheSameThingWhateverTheirIdentities() {
    Event one = Event.of(T0, 1d, Map.of("a", 1));
    Event other = Event.of(T0, 1d, Map.of("a", 1)).withId(99L);
    assertEquals(one, other);
    assertEquals(one.hashCode(), other.hashCode());
  }

  @Test
  void dataDecidesWhetherTwoEventsAreTheSameThing() {
    assertNotEquals(Event.of(T0, 1d, Map.of("a", 1)), Event.of(T0, 1d, Map.of("a", 2)));
    assertEquals(Event.of(T0, 1d, ordered("a", 1, "b", 2)),
        Event.of(T0, 1d, ordered("b", 2, "a", 1)),
        "the order the keys were written in is not part of what they say");
  }

  @Test
  void dataMayHoldANullValue() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("title", null);
    Event event = Event.of(T0, 1d, data);
    assertTrue(event.data().containsKey("title"));
    assertNull(event.data().get("title"));
  }

  @Test
  void aMissingDataObjectIsAnEmptyOne() {
    assertEquals(Map.of(), Event.of(T0, 1d, null).data());
  }

  @Test
  void changingAnEventKeepsItsIdentity() {
    Event event = Event.of(T0, 10d, Map.of("a", 1)).withId(7L);
    assertEquals(7L, event.withDuration(Duration.ofSeconds(20)).id());
    assertEquals(7L, event.withPeriod(T0, T0.plusSeconds(5)).id());
    assertEquals(7L, event.withData(Map.of("a", 2)).id());
  }

  @Test
  void anEventsEndIsWhereItStartsPlusHowLongItRan() {
    assertEquals(T0.plusSeconds(10), Event.of(T0, 10d, Map.of()).end());
  }

  @Test
  void durationsAreKeptToTheMicrosecondTheOriginalWorksIn() {
    assertEquals(1_500_000_000L, Event.seconds(1.5).toNanos());
    assertEquals(1_000L, Event.seconds(0.000001).toNanos());
  }

  @Test
  void halfADurationRoundsToTheNearestMicrosecond() {
    assertEquals(Duration.ofNanos(500_000), Event.half(Duration.ofNanos(1_000_000)));
    assertEquals(Duration.ofNanos(2_000), Event.half(Duration.ofNanos(5_000)),
        "a tie goes to the even microsecond, as Python's own division does");
  }

  private static Map<String, Object> ordered(Object... pairs) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      out.put(String.valueOf(pairs[i]), pairs[i + 1]);
    }
    return out;
  }
}
