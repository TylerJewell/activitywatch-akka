package io.akka.activitywatch.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One bucket's events for one UTC day.
 *
 * <p>This is where a bucket's history lives. A day is the unit because it bounds the record
 * at what one machine can produce between two midnights — a watcher that changes what it is
 * looking at every few seconds writes a few thousand events in a day, which is a few hundred
 * kilobytes — and because a person reads their own history a day at a time, so a range query
 * usually wants whole pages.
 *
 * <p>The same {@link BucketEvent} values the bucket persists are replayed here, so there is
 * one description of what a write means rather than two that have to be kept in step.
 */
public record PageState(String bucket, String day, List<Event> events) {

  /** Split from the end: the day is a ten-character date and a bucket id may hold the
   * separator itself. */
  public static PageState empty(String id) {
    int separator = id.length() - 11;
    return separator < 0 || id.charAt(separator) != '_'
        ? new PageState(id, "", List.of())
        : new PageState(id.substring(0, separator), id.substring(separator + 1), List.of());
  }

  public PageState with(BucketEvent event) {
    return switch (event) {
      case BucketEvent.Inserted e -> withInserted(e.id(), e.event());
      case BucketEvent.Extended e -> withChanged(e.id(),
          existing -> existing.withDuration(e.duration()));
      case BucketEvent.Replaced e -> withChanged(e.id(), existing -> e.event().withId(e.id()));
      case BucketEvent.Removed e -> withRemoved(e.id());
      // A page holds events; what a bucket is called and whether it exists is the bucket's.
      case BucketEvent.Created e -> this;
      case BucketEvent.Updated e -> this;
      case BucketEvent.Deleted e -> new PageState(bucket, day, List.of());
    };
  }

  private PageState withInserted(long id, Event event) {
    // Applied at least once, so an event that is already here is left as it is rather than
    // added twice: the consumer that feeds this page can be re-run over the same event.
    for (Event existing : events) {
      if (Long.valueOf(id).equals(existing.id())) {
        return this;
      }
    }
    List<Event> next = new ArrayList<>(events);
    next.add(event.withId(id));
    return new PageState(bucket, day, List.copyOf(next));
  }

  private PageState withChanged(long id, java.util.function.UnaryOperator<Event> change) {
    List<Event> next = new ArrayList<>(events);
    for (int i = next.size() - 1; i >= 0; i--) {
      if (Long.valueOf(id).equals(next.get(i).id())) {
        next.set(i, change.apply(next.get(i)));
        return new PageState(bucket, day, List.copyOf(next));
      }
    }
    return this;
  }

  private PageState withRemoved(long id) {
    List<Event> next = new ArrayList<>();
    for (Event event : events) {
      if (!Long.valueOf(id).equals(event.id())) {
        next.add(event);
      }
    }
    return new PageState(bucket, day, List.copyOf(next));
  }

  public List<Event> overlapping(Instant from, Instant to) {
    return EventSelection.overlapping(events, from, to);
  }
}
