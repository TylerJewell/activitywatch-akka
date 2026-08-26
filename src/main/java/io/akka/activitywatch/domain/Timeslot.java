package io.akka.activitywatch.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * An interval, with the containment and overlap rules the transforms are written against.
 *
 * <p>Two slots that touch at a single instant do not overlap, but a zero-length slot strictly
 * inside another does intersect it and the intersection is that zero-length slot — which is
 * why a zero-duration filter event clips an event to a zero-duration one (SPEC-001 §3 R18)
 * rather than removing it.
 *
 * @param start inclusive
 * @param end inclusive for containment, exclusive for overlap on the left-hand side
 */
public record Timeslot(Instant start, Instant end) {

  public static Timeslot of(Event event) {
    return new Timeslot(event.timestamp(), event.end());
  }

  public Duration duration() {
    return Duration.between(start, end);
  }

  public boolean contains(Timeslot other) {
    return !start.isAfter(other.start) && !other.end.isAfter(end);
  }

  public boolean contains(Instant at) {
    return !start.isAfter(at) && !at.isAfter(end);
  }

  public boolean overlaps(Timeslot other) {
    return !start.isAfter(other.start) && other.start.isBefore(end)
        || start.isBefore(other.end) && !other.end.isAfter(end)
        || other.contains(this);
  }

  /** The slot contained in both, or null where there is none. */
  public Timeslot intersection(Timeslot other) {
    if (contains(other)) {
      return other;
    }
    if (!start.isAfter(other.start) && other.start.isBefore(end)) {
      return new Timeslot(other.start, end);
    }
    if (start.isBefore(other.end) && !other.end.isAfter(end)) {
      return new Timeslot(start, other.end);
    }
    if (other.contains(this)) {
      return this;
    }
    return null;
  }

  /** The space between two slots that do not touch, or null when they do. */
  public Timeslot gap(Timeslot other) {
    if (end.isBefore(other.start)) {
      return new Timeslot(end, other.start);
    }
    if (other.end.isBefore(start)) {
      return new Timeslot(other.end, start);
    }
    return null;
  }

  /** Only defined where there is no gap; the callers check first, as the original does. */
  public Timeslot union(Timeslot other) {
    if (gap(other) != null) {
      throw new IllegalArgumentException(
          "Timeslots must not have a gap if they are to be unioned");
    }
    return new Timeslot(
        start.isBefore(other.start) ? start : other.start,
        end.isAfter(other.end) ? end : other.end);
  }
}
