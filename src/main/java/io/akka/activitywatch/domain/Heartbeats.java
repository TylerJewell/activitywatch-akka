package io.akka.activitywatch.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The decision that turns a stream of pings into time — SPEC-001 §3 rules 1–5, 7.
 *
 * <p>A watcher sends a ping saying "still doing this" every few seconds and carries no state
 * of its own. Whether two pings are one stretch of activity or two is decided here, and it is
 * the whole of the write path.
 */
public final class Heartbeats {

  private Heartbeats() {}

  /**
   * The heartbeat folded into the event before it, or empty when it starts a new one.
   *
   * <p>Returns a new event rather than changing {@code last}. The original hands back the very
   * object it was given with its duration altered underneath the caller — SPEC-001 §4 OD-3.
   */
  public static Optional<Event> merge(Event last, Event heartbeat, double pulsetimeSeconds) {
    if (!last.data().equals(heartbeat.data())) {
      return Optional.empty();
    }

    Instant pulseEnd = last.end().plus(Event.seconds(pulsetimeSeconds));
    boolean withinWindow = !heartbeat.timestamp().isBefore(last.timestamp())
        && !heartbeat.timestamp().isAfter(pulseEnd);
    if (!withinWindow) {
      return Optional.empty();
    }

    // An event that already runs backwards is left alone: lengthening it would be guessing
    // at which of its two ends is the wrong one.
    if (last.duration().isNegative()) {
      return Optional.empty();
    }

    Duration reachingTheHeartbeat =
        Duration.between(last.timestamp(), heartbeat.timestamp()).plus(heartbeat.duration());
    Duration longer = reachingTheHeartbeat.compareTo(last.duration()) > 0
        ? reachingTheHeartbeat
        : last.duration();
    return Optional.of(last.withDuration(longer));
  }

  /**
   * A run of heartbeats folded down to the events they describe.
   *
   * <p>The list handed in is not touched. The original removes its first element and mutates
   * the rest — SPEC-001 §4 OD-3.
   */
  public static List<Event> reduce(List<Event> heartbeats, double pulsetimeSeconds) {
    List<Event> reduced = new ArrayList<>();
    for (Event heartbeat : heartbeats) {
      if (reduced.isEmpty()) {
        reduced.add(heartbeat);
        continue;
      }
      int last = reduced.size() - 1;
      Optional<Event> merged = merge(reduced.get(last), heartbeat, pulsetimeSeconds);
      if (merged.isPresent()) {
        reduced.set(last, merged.get());
      } else {
        reduced.add(heartbeat);
      }
    }
    return List.copyOf(reduced);
  }
}
