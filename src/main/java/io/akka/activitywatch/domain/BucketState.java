package io.akka.activitywatch.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * A bucket, and the one event a heartbeat is compared against — SPEC-001 §3 rules 6, 6a, 8, 9.
 *
 * <p>{@code written} is in the order the events were written, not in time order, because the
 * event a heartbeat is compared against is the one written last and not the one that happens
 * to be latest. When a heartbeat arrives out of order those are different events, and that is
 * exactly the case where the original's own storage backends disagree with each other.
 *
 * <p>Two things here are bounded, and both bounds exist because this whole record is copied
 * between regions on every write: the number of events kept — SPEC-001 §4 OD-8 — and the
 * number of heartbeat names remembered for spotting a redelivery.
 */
public record BucketState(String id, String type, String client, String hostname, int retained,
    long nextId, long count, boolean complete, List<Stored> written, List<String> applied) {

  public static final int DEFAULT_RETAINED = 2_000;

  /**
   * The most events a bucket will keep.
   *
   * <p>At a few hundred bytes each this leaves the state comfortably inside the size the
   * runtime will replicate, which is a hard limit rather than a target.
   */
  public static final int MAX_RETAINED = 2_000;

  /**
   * How many heartbeat names are remembered.
   *
   * <p>A redelivery follows the original closely — it is a retry, not an echo from an hour
   * ago — so a short memory catches it, and a long one would only be state to copy.
   */
  private static final int REMEMBERED_COMMANDS = 100;

  /** An event and the identity by which a later heartbeat names it. */
  public record Stored(long id, Event event) {}

  public static BucketState empty(String id) {
    return new BucketState(id, null, null, null, DEFAULT_RETAINED, 0, 0, true, List.of(),
        List.of());
  }

  public boolean exists() {
    return type != null;
  }

  public BucketState withCreated(String type, String client, String hostname, int retained) {
    return new BucketState(id, type, client, hostname, retained, nextId, count, complete, written,
        applied);
  }

  public BucketState with(BucketEvent event) {
    return switch (event) {
      case BucketEvent.Created e -> withCreated(e.type(), e.client(), e.hostname(), e.retained());
      case BucketEvent.Inserted e -> withInserted(e.id(), e.event(), e.commandId());
      case BucketEvent.Extended e -> withExtended(e.id(), e.duration(), e.commandId());
    };
  }

  /** Whether a heartbeat by this name has already been applied — SPEC-001 §3 rule 6a. */
  public boolean hasApplied(String commandId) {
    return commandId != null && applied.contains(commandId);
  }

  private BucketState withInserted(long storedId, Event event, String commandId) {
    List<Stored> next = new ArrayList<>(written);
    next.add(new Stored(storedId, event));
    boolean stillComplete = complete;
    while (next.size() > retained) {
      next.remove(0);
      stillComplete = false;
    }
    return new BucketState(id, type, client, hostname, retained,
        Math.max(nextId, storedId + 1), count + 1, stillComplete, List.copyOf(next),
        remember(commandId));
  }

  private BucketState withExtended(long storedId, Duration duration, String commandId) {
    List<Stored> next = new ArrayList<>(written);
    for (int i = next.size() - 1; i >= 0; i--) {
      if (next.get(i).id() == storedId) {
        next.set(i, new Stored(storedId, next.get(i).event().withDuration(duration)));
        break;
      }
    }
    return new BucketState(id, type, client, hostname, retained, nextId, count, complete,
        List.copyOf(next), remember(commandId));
  }

  private List<String> remember(String commandId) {
    if (commandId == null) {
      return applied;
    }
    List<String> next = new ArrayList<>(applied);
    next.add(commandId);
    while (next.size() > REMEMBERED_COMMANDS) {
      next.remove(0);
    }
    return List.copyOf(next);
  }

  /** The event a heartbeat is compared against: the one written last. */
  public Optional<Stored> lastWritten() {
    return written.isEmpty() ? Optional.empty() : Optional.of(written.get(written.size() - 1));
  }

  /**
   * The retained events that overlap the range, newest first — SPEC-001 §3 rules 11, 11a.
   *
   * <p>Events are returned whole. A range is a question about which events to look at, not an
   * instruction to cut them.
   */
  public List<Stored> inRange(Instant from, Instant to, Integer limit) {
    List<Stored> selected = new ArrayList<>();
    for (Stored stored : written) {
      Event event = stored.event();
      boolean afterStart = from == null || !from.isAfter(event.end());
      boolean beforeEnd = to == null || !event.timestamp().isAfter(to);
      if (afterStart && beforeEnd) {
        selected.add(stored);
      }
    }
    // Ascending and then reversed, not descending: the two differ for events sharing a
    // timestamp, and the original sorts this way round.
    selected.sort(Comparator.comparing((Stored s) -> s.event().timestamp()));
    java.util.Collections.reverse(selected);
    if (limit != null && limit >= 0 && limit < selected.size()) {
      selected = selected.subList(0, limit);
    }
    return List.copyOf(selected);
  }
}
