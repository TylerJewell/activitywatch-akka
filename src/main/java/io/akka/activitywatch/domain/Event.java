package io.akka.activitywatch.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * A stretch of time during which one thing was happening.
 *
 * <p>Two events are the same thing exactly when their {@code data} are equal — SPEC-001 §3
 * rule 1. Nothing else about an event is compared to decide that.
 *
 * <p>The end is not stored. Storing both a duration and an end gives two places for the same
 * fact, and the original stores the duration.
 */
public record Event(Instant timestamp, Duration duration, Map<String, Object> data) {

  /**
   * The only way to build one, because both fields are normalised.
   *
   * <p>Timestamps carry milliseconds — SPEC-001 §3 rule 10 — and durations carry microseconds,
   * which is the resolution the original's arithmetic is done at. Normalising on the way in
   * means two events built from the same instant by different routes compare equal.
   */
  public static Event of(Instant timestamp, Duration duration, Map<String, Object> data) {
    return new Event(
        timestamp.truncatedTo(ChronoUnit.MILLIS),
        toMicros(duration),
        data == null ? Map.of() : Map.copyOf(data));
  }

  public static Event of(Instant timestamp, double durationSeconds, Map<String, Object> data) {
    return of(timestamp, seconds(durationSeconds), data);
  }

  public Instant end() {
    return timestamp.plus(duration);
  }

  public Event withDuration(Duration newDuration) {
    return of(timestamp, newDuration, data);
  }

  public Event withPeriod(Instant start, Instant end) {
    return of(start, Duration.between(start, end), data);
  }

  /** Seconds as a duration, at the microsecond resolution the original works in. */
  public static Duration seconds(double value) {
    return Duration.ofNanos((long) Math.rint(value * 1_000_000d) * 1_000L);
  }

  /** Half of a duration, rounded to the nearest microsecond, ties to even. */
  public static Duration half(Duration value) {
    return Duration.ofNanos((long) Math.rint(value.toNanos() / 2_000d) * 1_000L);
  }

  private static Duration toMicros(Duration value) {
    return Duration.ofNanos((long) Math.rint(value.toNanos() / 1_000d) * 1_000L);
  }
}
