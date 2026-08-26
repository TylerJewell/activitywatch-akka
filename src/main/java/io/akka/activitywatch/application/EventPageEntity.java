package io.akka.activitywatch.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import akka.javasdk.eventsourcedentity.EventSourcedEntityContext;
import io.akka.activitywatch.domain.BucketEvent;
import io.akka.activitywatch.domain.Event;
import io.akka.activitywatch.domain.PageState;
import java.time.Instant;
import java.util.List;

/**
 * One bucket's events for one UTC day — where its history is kept.
 *
 * <p>The bucket itself keeps only what it wrote recently, because its record is copied
 * between regions on every write and a bucket accumulates for years. Everything is written
 * here as well, by {@link EventPageConsumer}, which is durable and at-least-once — so a page
 * can be handed the same write twice and has to make that harmless, which is what
 * {@link PageState} does by keying on the event's identity.
 */
@Component(id = "event-page")
public class EventPageEntity extends EventSourcedEntity<PageState, BucketEvent> {

  private final String pageId;

  public EventPageEntity(EventSourcedEntityContext context) {
    this.pageId = context.entityId();
  }

  /** Milliseconds since the epoch, because a range arrives from a query string. */
  public record Range(Long fromMillis, Long toMillis) {}

  /**
   * One write, flattened.
   *
   * <p>{@link BucketEvent} is a sealed interface and is exactly what this page persists — as
   * an entity's own event type, the runtime's polymorphic handling resolves it. As a
   * *command* parameter it does not: the variant's type name is only read at the top level of
   * a persisted or transmitted type, and a call with one fails at runtime with "could not be
   * decoded" while compiling perfectly. So the wire form is flat and the interface is rebuilt
   * on this side.
   *
   * @param kind one of {@code inserted}, {@code extended}, {@code replaced}, {@code removed},
   *     {@code deleted}
   * @param duration only for {@code extended}
   */
  public record Apply(String kind, long id, Event event, java.time.Duration duration) {

    public static Apply of(BucketEvent change) {
      return switch (change) {
        case BucketEvent.Inserted e -> new Apply("inserted", e.id(), e.event(), null);
        case BucketEvent.Extended e -> new Apply("extended", e.id(), null, e.duration());
        case BucketEvent.Replaced e -> new Apply("replaced", e.id(), e.event(), null);
        case BucketEvent.Removed e -> new Apply("removed", e.id(), null, null);
        case BucketEvent.Deleted e -> new Apply("deleted", 0, null, null);
        default -> throw new IllegalArgumentException(
            "a change no page holds: " + change.getClass().getSimpleName());
      };
    }

    BucketEvent asChange() {
      return switch (kind) {
        case "inserted" -> new BucketEvent.Inserted(id, event);
        case "extended" -> new BucketEvent.Extended(id, duration);
        case "replaced" -> new BucketEvent.Replaced(id, event);
        case "removed" -> new BucketEvent.Removed(id);
        case "deleted" -> new BucketEvent.Deleted();
        default -> throw new IllegalArgumentException("no such change: " + kind);
      };
    }
  }

  public record Events(String page, List<Event> events) {}

  @Override
  public PageState emptyState() {
    return PageState.empty(pageId);
  }

  /** One write, replayed here from the bucket that made it. */
  public Effect<Boolean> apply(Apply command) {
    BucketEvent change = command.asChange();
    PageState before = currentState();
    PageState after = before.with(change);
    if (after.equals(before)) {
      // Already applied, or nothing this page holds. Persisting an event that changes
      // nothing would grow the journal for every redelivery.
      return effects().reply(false);
    }
    return effects().persist(change).thenReply(state -> true);
  }

  /** Every event overlapping the range, uncut and unordered — the caller shapes them. */
  public ReadOnlyEffect<Events> overlapping(Range range) {
    Instant from = range == null || range.fromMillis() == null
        ? null : Instant.ofEpochMilli(range.fromMillis());
    Instant to = range == null || range.toMillis() == null
        ? null : Instant.ofEpochMilli(range.toMillis());
    return effects().reply(new Events(pageId, currentState().overlapping(from, to)));
  }

  public ReadOnlyEffect<Events> all() {
    return effects().reply(new Events(pageId, currentState().events()));
  }

  @Override
  public PageState applyEvent(BucketEvent event) {
    return currentState().with(event);
  }
}
