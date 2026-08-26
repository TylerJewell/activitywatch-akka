package io.akka.activitywatch.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A bucket: its metadata, the events it has written recently, and where the rest of them are
 * — SPEC-001 §3 R32–R41.
 *
 * <p>{@code recent} is in the order the events were written, not in time order, because the
 * event a heartbeat is compared against is the one written last and not the one that happens
 * to be latest. When a heartbeat arrives out of order those are different events, and that is
 * exactly the case where the original's own storage backends disagree with each other
 * (§4 OD-1).
 *
 * <p>**Only the recent ones.** A bucket accumulates for years and this record is copied
 * between regions on every write, so keeping every event here would put a week of ordinary
 * use past the size the runtime will replicate. What is kept is the window a heartbeat and a
 * read-your-own-write need; everything else lives in a page per day, written by a consumer and
 * read back beside this. {@code pages} names the days this bucket has written to, so a range
 * query knows which to ask — a decade of them is a few tens of kilobytes.
 */
public record BucketState(String id, String name, String type, String client, String hostname,
    String created, Map<String, Object> data, int retained, long nextId, long count,
    boolean complete, List<Event> recent, Long lastWrittenId, List<String> pages) {

  /**
   * How many recently-written events a bucket keeps beside the pages.
   *
   * <p>Enough that a caller reading back what it has just written finds it whatever order the
   * page consumer got to, and small enough that the record stays a few tens of kilobytes.
   */
  public static final int RECENT_WINDOW = 200;

  public static BucketState empty(String id) {
    return new BucketState(id, null, null, null, null, null, Map.of(), 0, 1, 0, true,
        List.of(), null, List.of());
  }

  public boolean exists() {
    return type != null;
  }

  /** R43: the end of the most recent event this bucket wrote. */
  public Optional<Instant> lastUpdated() {
    return recent.stream().map(Event::end).max(Comparator.naturalOrder());
  }

  /** The metadata shape every route that answers about a bucket uses. */
  public Map<String, Object> metadata() {
    Map<String, Object> out = new LinkedHashMap<>();
    // The order the original's model declares, which is what a caller reading the raw
    // body sees: `created` sits between the identity and the name, not beside the rest of
    // the fields a caller sends.
    out.put("id", id);
    out.put("created", created);
    out.put("name", name);
    out.put("type", type);
    out.put("client", client);
    out.put("hostname", hostname);
    out.put("data", data);
    return out;
  }

  public BucketState with(BucketEvent event) {
    return switch (event) {
      case BucketEvent.Created e -> new BucketState(id, e.name(), e.type(), e.client(),
          e.hostname(), e.created(), e.data() == null ? Map.of() : e.data(), e.retained(),
          1, 0, true, List.of(), null, List.of());
      case BucketEvent.Updated e -> new BucketState(id,
          e.name() == null ? name : e.name(),
          e.type() == null ? type : e.type(),
          e.client() == null ? client : e.client(),
          e.hostname() == null ? hostname : e.hostname(),
          created, e.data() == null ? data : e.data(), retained, nextId, count, complete,
          recent, lastWrittenId, pages);
      case BucketEvent.Deleted e -> empty(id);
      case BucketEvent.Inserted e -> withInserted(e.id(), e.event());
      case BucketEvent.Extended e -> withChanged(e.id(),
          existing -> existing.withDuration(e.duration()), count);
      case BucketEvent.Replaced e -> withChanged(e.id(),
          existing -> e.event().withId(e.id()), count);
      case BucketEvent.Removed e -> withRemoved(e.id());
    };
  }

  private BucketState withInserted(long storedId, Event event) {
    Event stored = event.withId(storedId);
    List<Event> next = new ArrayList<>(recent);
    next.add(stored);
    boolean stillComplete = complete;
    // Two bounds, and they mean different things. The window is how much is kept here; a
    // retention cap is an operator saying the bucket may forget, and only that one makes the
    // history incomplete.
    while (next.size() > RECENT_WINDOW) {
      next.remove(0);
    }
    long kept = count + 1;
    if (retained > 0 && kept > retained) {
      kept = retained;
      stillComplete = false;
    }
    LinkedHashSet<String> nextPages = new LinkedHashSet<>(pages);
    nextPages.add(EventSelection.pageOf(id, stored.timestamp()));
    return new BucketState(id, name, type, client, hostname, created, data, retained,
        Math.max(nextId, storedId + 1), kept, stillComplete, List.copyOf(next), storedId,
        List.copyOf(nextPages));
  }

  private BucketState withChanged(long storedId, java.util.function.UnaryOperator<Event> change,
      long newCount) {
    List<Event> next = new ArrayList<>(recent);
    for (int i = next.size() - 1; i >= 0; i--) {
      if (Long.valueOf(storedId).equals(next.get(i).id())) {
        next.set(i, change.apply(next.get(i)));
        break;
      }
    }
    return new BucketState(id, name, type, client, hostname, created, data, retained, nextId,
        newCount, complete, List.copyOf(next), storedId, pages);
  }

  private BucketState withRemoved(long storedId) {
    List<Event> next = new ArrayList<>();
    for (Event event : recent) {
      if (!Long.valueOf(storedId).equals(event.id())) {
        next.add(event);
      }
    }
    Long stillLast = lastWrittenId != null && lastWrittenId == storedId ? null : lastWrittenId;
    return new BucketState(id, name, type, client, hostname, created, data, retained, nextId,
        Math.max(0, count - 1), complete, List.copyOf(next), stillLast, pages);
  }

  /** R9: the event a heartbeat is compared against — the one written last. */
  public Optional<Event> lastWritten() {
    if (lastWrittenId != null) {
      for (int i = recent.size() - 1; i >= 0; i--) {
        if (lastWrittenId.equals(recent.get(i).id())) {
          return Optional.of(recent.get(i));
        }
      }
    }
    // Nothing has been written since this bucket last held an event, so the comparison falls
    // back to the newest event it still has -- which is what the original does after a
    // restart, when its in-process cache is empty.
    return EventSelection.newestFirst(recent).stream().findFirst();
  }

  /** R39: the event `replace_last` rewrites is the one with the greatest timestamp. */
  public Optional<Event> latestByTimestamp() {
    return recent.stream().max(Comparator.comparing(Event::timestamp));
  }

  public Optional<Event> byId(long storedId) {
    return recent.stream().filter(e -> Long.valueOf(storedId).equals(e.id())).findFirst();
  }

  /** The recently written events overlapping a range, uncut and unordered. */
  public List<Event> overlapping(Instant from, Instant to) {
    return EventSelection.overlapping(recent, from, to);
  }

  /** Which pages a range could touch. */
  public List<String> pagesFor(Instant from, Instant to) {
    return EventSelection.pagesFor(id, from, to, pages);
  }
}
