package io.akka.activitywatch.application;

import akka.javasdk.NotificationPublisher;
import akka.javasdk.NotificationPublisher.NotificationStream;
import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import akka.javasdk.eventsourcedentity.EventSourcedEntityContext;
import io.akka.activitywatch.domain.BucketEvent;
import io.akka.activitywatch.domain.BucketState;
import io.akka.activitywatch.domain.Event;
import io.akka.activitywatch.domain.Heartbeats;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A bucket, and the decision about where one stretch of activity ends — SPEC-001 §3 rules 6,
 * 8, 9, 11, 11a.
 *
 * <p>A watcher sends a ping every few seconds saying what is happening and holds no state of
 * its own. This is where it is decided whether that ping lengthens the stretch before it or
 * starts a new one, and the answer depends on exactly one earlier event: the one written
 * last. That event is state here, so it survives a restart — in the original it is a cache in
 * the server process whose fallback after a restart is a different event (question-log row
 * 12).
 */
@Component(id = "bucket")
public class BucketEntity extends EventSourcedEntity<BucketState, BucketEvent> {

  public static final String MERGED = "merge";
  public static final String INSERTED = "insert";
  public static final String DUPLICATE = "duplicate";

  private final String bucketId;
  private final NotificationPublisher<Written> live;

  public BucketEntity(EventSourcedEntityContext context, NotificationPublisher<Written> live) {
    this.bucketId = context.entityId();
    this.live = live;
  }

  /**
   * @param retained how many of the most recent events stay readable, or null for the default
   */
  public record Create(String type, String client, String hostname, Integer retained) {}

  /**
   * @param pulsetime how long after an event ends a ping may still be part of it, in seconds
   * @param commandId what the writer calls this heartbeat, so a redelivery of it can be
   *     recognised, or null where the writer has no name for it — SPEC-001 §3 rule 6a
   */
  public record Heartbeat(Event event, double pulsetime, String commandId) {}

  /**
   * What a heartbeat did.
   *
   * @param action {@code merge} when it lengthened the event named by {@code id}, {@code
   *     insert} when it started that event, {@code duplicate} when this heartbeat had already
   *     been applied and {@code event} is how it stands
   */
  public record Written(String bucket, String action, long id, Event event) {}

  /** Milliseconds since the epoch, because a range arrives from a query string. */
  public record Range(Long fromMillis, Long toMillis, Integer limit) {}

  /**
   * @param complete false when older events have been dropped, so the list is not the whole
   *     history even where the range asks for it
   */
  public record Events(String bucket, List<BucketState.Stored> events, boolean complete,
      long count) {}

  public record Info(String bucket, boolean exists, String type, String client, String hostname,
      long count, int retained, boolean complete) {}

  @Override
  public BucketState emptyState() {
    return BucketState.empty(bucketId);
  }

  public Effect<Info> create(Create command) {
    if (command.type() == null || command.type().isBlank()) {
      return effects().error("a bucket must have a type");
    }
    if (currentState().exists()) {
      return effects().reply(info(currentState()));
    }
    int retained = command.retained() == null
        ? BucketState.DEFAULT_RETAINED
        : command.retained();
    if (retained < 1) {
      return effects().error("a bucket must keep at least one event");
    }
    if (retained > BucketState.MAX_RETAINED) {
      return effects().error(
          "a bucket cannot keep more than " + BucketState.MAX_RETAINED + " events");
    }
    // Empty rather than null: these travel into a view row, and a view has no answer for a
    // null in a query or a projection.
    return effects()
        .persist(new BucketEvent.Created(command.type(),
            command.client() == null ? "" : command.client(),
            command.hostname() == null ? "" : command.hostname(),
            retained))
        .thenReply(state -> info(state));
  }

  /** Rules 1–6, 8. */
  public Effect<Written> heartbeat(Heartbeat command) {
    if (!currentState().exists()) {
      // The name is left out: a bucket is named for the machine it watches, and an error
      // message travels into logs and back to whoever asked.
      return effects().error("there is no such bucket");
    }
    if (command.event() == null) {
      return effects().error("a heartbeat must carry an event");
    }

    if (currentState().hasApplied(command.commandId())) {
      // Rule 6a. Delivery is at-least-once and a heartbeat that starts an event is not
      // idempotent on its own, so a redelivery would leave a second one.
      BucketState.Stored stood = currentState().lastWritten().orElseThrow();
      return effects().reply(new Written(bucketId, DUPLICATE, stood.id(), stood.event()));
    }

    Optional<BucketState.Stored> last = currentState().lastWritten();
    Optional<Event> merged = last.flatMap(
        stored -> Heartbeats.merge(stored.event(), command.event(), command.pulsetime()));

    if (merged.isPresent()) {
      long id = last.get().id();
      Written written = new Written(bucketId, MERGED, id, merged.get());
      return effects()
          .persist(new BucketEvent.Extended(id, merged.get().duration(), command.commandId()))
          .thenReply(state -> {
            live.publish(written);
            return written;
          });
    }

    long id = currentState().nextId();
    Written written = new Written(bucketId, INSERTED, id, command.event());
    return effects()
        .persist(new BucketEvent.Inserted(id, command.event(), command.commandId()))
        .thenReply(state -> {
          live.publish(written);
          return written;
        });
  }

  /** Rules 11 and 11a. */
  public ReadOnlyEffect<Events> events(Range range) {
    BucketState state = currentState();
    Instant from = range == null || range.fromMillis() == null
        ? null : Instant.ofEpochMilli(range.fromMillis());
    Instant to = range == null || range.toMillis() == null
        ? null : Instant.ofEpochMilli(range.toMillis());
    Integer limit = range == null ? null : range.limit();
    return effects().reply(
        new Events(bucketId, state.inRange(from, to, limit), state.complete(), state.count()));
  }

  public ReadOnlyEffect<Info> info() {
    return effects().reply(info(currentState()));
  }

  /**
   * Every event as it is written, for anyone who would otherwise ask again on a timer.
   *
   * <p>A subscriber sees what is written while it is attached, and reads {@link #events} over
   * the span it was away to cover a break — SPEC-001 §4 OD-6.
   */
  public NotificationStream<Written> updates() {
    return live.stream();
  }

  @Override
  public BucketState applyEvent(BucketEvent event) {
    return currentState().with(event);
  }

  private static Info info(BucketState state) {
    return new Info(state.id(), state.exists(), state.type(), state.client(), state.hostname(),
        state.count(), state.retained(), state.complete());
  }
}
