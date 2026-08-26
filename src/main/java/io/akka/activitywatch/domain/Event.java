package io.akka.activitywatch.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A stretch of time during which one thing was happening.
 *
 * <p>Two events are the same thing exactly when their timestamp, duration and {@code data} are
 * equal — SPEC-001 §3 R3, R4. The identity storage gave the event is deliberately **not** part
 * of that: the original's own equality ignores it, and a heartbeat is compared against a
 * stored event by data alone.
 *
 * <p>The identity rides along on the event because a transform that copies an event keeps it
 * and a transform that builds a new one does not, and a caller reading a query's answer can
 * see the difference. Keeping it in a wrapper instead would mean deciding, at every transform,
 * whether the wrapper survived.
 *
 * <p>The end is not stored. Storing both a duration and an end gives two places for the same
 * fact, and the original stores the duration.
 */
public record Event(Long id, Instant timestamp, Duration duration, Map<String, Object> data) {

  /**
   * A new event with no identity, with both time fields normalised.
   *
   * <p>Timestamps carry milliseconds — R1, truncated rather than rounded — and durations carry
   * microseconds, which is the resolution the original's arithmetic is done at. Normalising on
   * the way in means two events built from the same instant by different routes compare equal.
   */
  public static Event of(Instant timestamp, Duration duration, Map<String, Object> data) {
    return new Event(null, truncateToMillis(timestamp), toMicros(duration), freeze(data));
  }

  public static Event of(Instant timestamp, double durationSeconds, Map<String, Object> data) {
    return of(timestamp, seconds(durationSeconds), data);
  }

  public static Event of(Long id, Instant timestamp, double durationSeconds,
      Map<String, Object> data) {
    return new Event(id, truncateToMillis(timestamp), seconds(durationSeconds), freeze(data));
  }

  public Instant end() {
    return timestamp.plus(duration);
  }

  public Event withId(Long newId) {
    return new Event(newId, timestamp, duration, data);
  }

  public Event withDuration(Duration newDuration) {
    return new Event(id, timestamp, toMicros(newDuration), data);
  }

  public Event withPeriod(Instant start, Instant end) {
    Instant from = truncateToMillis(start);
    return new Event(id, from, toMicros(Duration.between(from, truncateToMillis(end))), data);
  }

  public Event withData(Map<String, Object> newData) {
    return new Event(id, timestamp, duration, freeze(newData));
  }

  public double durationSeconds() {
    return duration.toNanos() / 1_000_000_000d;
  }

  /** R3: the identity is not part of what makes two events the same thing. */
  @Override
  public boolean equals(Object other) {
    return other instanceof Event event
        && Objects.equals(timestamp, event.timestamp)
        && Objects.equals(duration, event.duration)
        && Objects.equals(data, event.data);
  }

  @Override
  public int hashCode() {
    return Objects.hash(timestamp, duration, data);
  }

  /** Seconds as a duration, at the microsecond resolution the original works in. */
  public static Duration seconds(double value) {
    return Duration.ofNanos((long) Math.rint(value * 1_000_000d) * 1_000L);
  }

  /** Half of a duration, rounded to the nearest microsecond, ties to even. */
  public static Duration half(Duration value) {
    return Duration.ofNanos((long) Math.rint(value.toNanos() / 2_000d) * 1_000L);
  }

  /**
   * Truncation towards the epoch, which is what the original's
   * {@code int(microsecond / 1000) * 1000} does for the instants it sees.
   */
  public static Instant truncateToMillis(Instant timestamp) {
    return timestamp.truncatedTo(ChronoUnit.MILLIS);
  }

  /**
   * A copy that keeps insertion order and tolerates a null value.
   *
   * <p>{@code Map.copyOf} refuses a null value, and the original stores whatever JSON it was
   * given — a watcher that sends {@code {"title": null}} is not an error there.
   */
  private static Map<String, Object> freeze(Map<String, Object> data) {
    if (data == null) {
      return Collections.emptyMap();
    }
    return Collections.unmodifiableMap(new LinkedHashMap<>(data));
  }

  private static Duration toMicros(Duration value) {
    return Duration.ofNanos((long) Math.rint(value.toNanos() / 1_000d) * 1_000L);
  }
}
