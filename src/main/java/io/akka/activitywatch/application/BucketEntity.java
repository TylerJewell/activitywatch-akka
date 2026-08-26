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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A bucket, and the decision about where one stretch of activity ends —
 * SPEC-001 §3 R4–R9, R32–R41.
 *
 * <p>A watcher sends a ping every few seconds saying what is happening and holds no state of
 * its own. This is where it is decided whether that ping lengthens the stretch before it or
 * starts a new one, and the answer depends on exactly one earlier event: the one written last.
 * That event is state here, so it survives a restart — in the original it is a cache in the
 * server process whose fallback after a restart is a different event (§4 OD-5).
 *
 * <p>What this holds is the metadata and the last two hundred events written. The rest of the
 * history is in a page per day, written from here by {@link EventPageConsumer} and read back
 * beside it — see {@link BucketState} for why.
 */
@Component(id = "bucket")
public class BucketEntity extends EventSourcedEntity<BucketState, BucketEvent> {

  public static final String MERGED = "merge";
  public static final String INSERTED = "insert";

  private final String bucketId;
  private final NotificationPublisher<Change> live;

  public BucketEntity(EventSourcedEntityContext context, NotificationPublisher<Change> live) {
    this.bucketId = context.entityId();
    this.live = live;
  }

  /** @param retained 0 keeps every event; a positive number caps the bucket — §4 OD-4 */
  public record Create(String name, String type, String client, String hostname, String created,
      Map<String, Object> data, int retained) {}

  /** Only the fields that are given are changed. */
  public record Update(String name, String type, String client, String hostname,
      Map<String, Object> data) {}

  /** @param pulsetime how long after an event ends a ping may still be part of it, in seconds */
  public record Heartbeat(Event event, double pulsetime) {}

  /** One event, or the news that there is none by that identity. */
  public record Found(boolean found, Event event) {}

  /** @param inserted the events written, with ids; empty for a bulk write, as the original */
  public record Written(List<Event> inserted) {}

  /**
   * What a heartbeat did.
   *
   * @param action {@code merge} when it lengthened the event named by {@code id}, {@code
   *     insert} when it started that event
   */
  public record HeartbeatResult(String bucket, String action, long id, Event event) {}

  /** Milliseconds since the epoch, because a range arrives from a query string. */
  public record Range(Long fromMillis, Long toMillis, int limit) {}

  /** @param complete false when older events have been dropped by a retention cap */
  public record Events(String bucket, List<Event> events, boolean complete, long count) {}

  /**
   * One change to one bucket, pushed as it happens.
   *
   * <p>Separate from the view of every bucket, and for one reason: this arrives in about a
   * millisecond and the view's does not. A projection is read by polling a table and each
   * poll deliberately looks a fixed interval behind the present to tolerate clocks that
   * disagree, so the view stream is complete and resumable but a few seconds behind. A view
   * showing one bucket live follows this; everything else follows the view.
   */
  public record Change(String bucket, String kind, Long id, Event event, long count) {}

  /**
   * @param pages the days this bucket has written to, which is what a range query asks
   * @param recent the events this bucket still holds itself
   */
  public record Info(String bucket, boolean exists, Map<String, Object> metadata,
      String lastUpdated, long count, int retained, boolean complete, List<String> pages,
      List<Event> recent) {}

  @Override
  public BucketState emptyState() {
    return BucketState.empty(bucketId);
  }

  /** R44: creating a bucket that exists is not an error and changes nothing. */
  public Effect<Boolean> create(Create command) {
    if (command.type() == null || command.type().isBlank()) {
      return effects().error("a bucket must have a type");
    }
    if (currentState().exists()) {
      return effects().reply(false);
    }
    if (command.retained() < 0) {
      return effects().error("a bucket cannot keep a negative number of events");
    }
    var created = new BucketEvent.Created(command.name(), command.type(),
        command.client() == null ? "" : command.client(),
        command.hostname() == null ? "" : command.hostname(),
        command.created(), command.data() == null ? Map.of() : command.data(),
        command.retained());
    return effects().persist(created).thenReply(state -> {
      live.publish(new Change(bucketId, "bucket-created", null, null, state.count()));
      return true;
    });
  }

  public Effect<Info> update(Update command) {
    if (!currentState().exists()) {
      return effects().error("there is no such bucket");
    }
    return effects()
        .persist(new BucketEvent.Updated(command.name(), command.type(), command.client(),
            command.hostname(), command.data()))
        .thenReply(state -> {
          live.publish(new Change(bucketId, "bucket-updated", null, null, state.count()));
          return info(state);
        });
  }

  public Effect<Boolean> delete() {
    if (!currentState().exists()) {
      return effects().reply(false);
    }
    return effects().persist(new BucketEvent.Deleted()).thenReply(state -> {
      live.publish(new Change(bucketId, "bucket-deleted", null, null, 0));
      return true;
    });
  }

  /**
   * R38, R48: one event comes back with its identity, a batch comes back empty.
   *
   * <p>An event that already carries an identity replaces the stored one rather than being
   * added, which is what makes an import over an existing bucket an upsert.
   */
  public Effect<Written> insert(List<Event> events) {
    if (!currentState().exists()) {
      return effects().error("there is no such bucket");
    }
    List<BucketEvent> persisted = new ArrayList<>();
    List<Event> written = new ArrayList<>();
    long nextId = currentState().nextId();
    for (Event candidate : events) {
      if (candidate.id() != null && currentState().byId(candidate.id()).isPresent()) {
        persisted.add(new BucketEvent.Replaced(candidate.id(), candidate));
        written.add(candidate);
      } else {
        long id = nextId++;
        persisted.add(new BucketEvent.Inserted(id, candidate));
        written.add(candidate.withId(id));
      }
    }
    if (persisted.isEmpty()) {
      return effects().reply(new Written(List.of()));
    }
    List<Event> answer = events.size() == 1 ? List.copyOf(written) : List.of();
    return effects().persistAll(persisted).thenReply(state -> {
      for (Event stored : written) {
        live.publish(new Change(bucketId, "event-inserted", stored.id(), stored,
            state.count()));
      }
      return new Written(answer);
    });
  }

  /** R4–R9. */
  public Effect<HeartbeatResult> heartbeat(Heartbeat command) {
    if (!currentState().exists()) {
      // The bucket's name is left out of the message: it travels into logs and back to
      // whoever asked, and the route already knows which bucket it addressed.
      return effects().error("there is no such bucket");
    }
    if (command.event() == null) {
      return effects().error("a heartbeat must carry an event");
    }

    Optional<Event> last = currentState().lastWritten();
    Optional<Event> merged = last.flatMap(
        stored -> Heartbeats.merge(stored, command.event(), command.pulsetime()));

    if (merged.isPresent()) {
      long id = last.get().id();
      HeartbeatResult result = new HeartbeatResult(bucketId, MERGED, id, merged.get());
      return effects()
          .persist(new BucketEvent.Extended(id, merged.get().duration()))
          .thenReply(state -> {
            live.publish(new Change(bucketId, "event-extended", id, merged.get(),
                state.count()));
            return result;
          });
    }

    long id = currentState().nextId();
    HeartbeatResult result = new HeartbeatResult(bucketId, INSERTED, id, command.event());
    return effects()
        .persist(new BucketEvent.Inserted(id, command.event()))
        .thenReply(state -> {
          live.publish(new Change(bucketId, "event-inserted", id, command.event(),
              state.count()));
          return result;
        });
  }

  /** R39: the event with the greatest timestamp is the one rewritten. */
  public Effect<Boolean> replaceLast(Event event) {
    Optional<Event> target = currentState().latestByTimestamp();
    if (target.isEmpty()) {
      return effects().reply(false);
    }
    long id = target.get().id();
    return effects().persist(new BucketEvent.Replaced(id, event)).thenReply(state -> {
      live.publish(new Change(bucketId, "event-replaced", id, event, state.count()));
      return true;
    });
  }

  public Effect<Boolean> replace(Event command) {
    if (command.id() == null || currentState().byId(command.id()).isEmpty()) {
      return effects().reply(false);
    }
    return effects().persist(new BucketEvent.Replaced(command.id(), command))
        .thenReply(state -> {
          live.publish(new Change(bucketId, "event-replaced", command.id(), command,
              state.count()));
          return true;
        });
  }

  /** R52: deleting an event that is not there reports that nothing happened. */
  public Effect<Boolean> deleteEvent(Long id) {
    if (currentState().byId(id).isEmpty()) {
      return effects().reply(false);
    }
    return effects().persist(new BucketEvent.Removed(id)).thenReply(state -> {
      live.publish(new Change(bucketId, "event-removed", id, null, state.count()));
      return true;
    });
  }

  /**
   * The recently written events overlapping the range, uncut and unordered.
   *
   * <p>Unshaped on purpose: a range that reaches back beyond what this bucket still holds is
   * answered from here and from the pages together, and the rounding, the clipping, the
   * ordering and the limit have to happen once, after the two have been put together.
   */
  public ReadOnlyEffect<Events> overlapping(Range range) {
    BucketState state = currentState();
    Instant from = range == null || range.fromMillis() == null
        ? null : Instant.ofEpochMilli(range.fromMillis());
    Instant to = range == null || range.toMillis() == null
        ? null : Instant.ofEpochMilli(range.toMillis());
    return effects().reply(new Events(bucketId, state.overlapping(from, to), state.complete(),
        state.count()));
  }

  /** R51. An absent event is a `found` of false rather than a null, so the wire has a shape. */
  public ReadOnlyEffect<Found> event(Long id) {
    return effects().reply(currentState().byId(id)
        .map(event -> new Found(true, event))
        .orElse(new Found(false, null)));
  }

  /** Every change to this bucket as it happens — SPEC-001 §3 R112, RENDERING.md R1.2. */
  public NotificationStream<Change> updates() {
    return live.stream();
  }

  public ReadOnlyEffect<Info> info() {
    return effects().reply(info(currentState()));
  }

  @Override
  public BucketState applyEvent(BucketEvent event) {
    return currentState().with(event);
  }

  private static Info info(BucketState state) {
    return new Info(state.id(), state.exists(), state.metadata(),
        state.lastUpdated().map(Instant::toString).orElse(null),
        state.count(), state.retained(), state.complete(), state.pages(), state.recent());
  }
}
