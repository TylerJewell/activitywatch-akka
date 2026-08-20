package io.akka.activitywatch.api;

import akka.NotUsed;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpException;
import akka.javasdk.http.HttpResponses;
import akka.stream.javadsl.Source;
import io.akka.activitywatch.application.BucketEntity;
import io.akka.activitywatch.application.BucketsByNameView;
import io.akka.activitywatch.application.IdleWatcherEntity;
import io.akka.activitywatch.domain.Activities;
import io.akka.activitywatch.domain.BucketState;
import io.akka.activitywatch.domain.Event;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The surface — SPEC-001 §3 rules 32 and 33.
 *
 * <p>Six things: make a bucket, send it a heartbeat, read its events back, list the buckets,
 * ask what was spent on what, and watch a bucket as it fills. The last one is a stream rather
 * than something to ask again on a timer, which is a behavioural choice and not a preference:
 * see SPEC-001 §4 OD-6 for what a subscriber does and does not see.
 *
 * <p>Durations travel as seconds, and timestamps as ISO-8601 text, matching the original's own
 * JSON so that a recording taken from one can be replayed into the other.
 */
@HttpEndpoint("/tracking")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class TrackingEndpoint extends AbstractHttpEndpoint {

  private static final List<String> DEFAULT_KEYS = List.of("app");
  private static final double DEFAULT_PULSETIME = 5.0;

  private final ComponentClient componentClient;

  public TrackingEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  /** @param retained how many of the most recent events stay readable, or null for the default */
  public record CreateRequest(String type, String client, String hostname, Integer retained) {}

  /** @param duration seconds; a heartbeat usually carries zero and is lengthened by the next */
  public record HeartbeatRequest(String timestamp, double duration, Map<String, Object> data,
      double pulsetime) {}

  public record EventView(long id, String timestamp, double duration, Map<String, Object> data) {}

  /** @param action {@code merge} when the heartbeat lengthened an event, {@code insert} when it
   *     started one */
  public record WrittenView(String bucket, String action, long id, EventView event) {}

  /** @param complete false when older events have been dropped, so this is not the whole history */
  public record EventsView(String bucket, List<EventView> events, boolean complete, long count) {}

  public record BucketView(String bucket, String type, String client, String hostname,
      long count, int retained, boolean complete) {}

  public record BucketSummary(String bucket, String type, String client, String hostname,
      long events, String lastWritten) {}

  public record BucketsView(List<BucketSummary> buckets) {}

  /**
   * @param from ISO-8601, or null for everything retained
   * @param keys the data keys activities are grouped by, or null for {@code app}
   */
  public record ActivityRequest(String windowBucket, String idleBucket, String from, String to,
      Double pulsetime, List<String> keys) {}

  /** @param total seconds across every activity, which is the time the machine was in use */
  public record ActivitiesView(List<EventView> activities, double total) {}

  public record StartRequest(String bucket, double timeoutSeconds, double pollSeconds) {}

  /** @param idleSeconds how long the machine had gone untouched when the reading was taken */
  public record ObserveRequest(String at, double idleSeconds) {}

  public record WatcherView(String watcher, String bucket, double timeoutSeconds,
      double pollSeconds, boolean idle) {}

  public record ObservedView(boolean idle, List<EventView> pings) {}

  @Post("/buckets/{bucket}")
  public HttpResponse createBucket(String bucket, CreateRequest request) {
    if (request == null) {
      throw HttpException.badRequest("a bucket needs a type");
    }
    var info = componentClient
        .forEventSourcedEntity(bucket)
        .method(BucketEntity::create)
        .invoke(new BucketEntity.Create(request.type(), request.client(), request.hostname(),
            request.retained()));
    return HttpResponses.created(toApi(info));
  }

  @Get("/buckets/{bucket}")
  public BucketView bucket(String bucket) {
    var info = componentClient
        .forEventSourcedEntity(bucket)
        .method(BucketEntity::info)
        .invoke();
    if (!info.exists()) {
      throw HttpException.notFound();
    }
    return toApi(info);
  }

  @Post("/buckets/{bucket}/heartbeat")
  public WrittenView heartbeat(String bucket, HeartbeatRequest request) {
    if (request == null) {
      throw HttpException.badRequest("a heartbeat needs an event");
    }
    Event event = Event.of(parse(request.timestamp()), request.duration(),
        request.data() == null ? Map.of() : request.data());
    var written = componentClient
        .forEventSourcedEntity(bucket)
        .method(BucketEntity::heartbeat)
        .invoke(new BucketEntity.Heartbeat(event, request.pulsetime(), null));
    return toApi(written);
  }

  @Get("/buckets/{bucket}/events")
  public EventsView events(String bucket) {
    var range = new BucketEntity.Range(
        optionalInstant("from").map(Instant::toEpochMilli).orElse(null),
        optionalInstant("to").map(Instant::toEpochMilli).orElse(null),
        requestContext().queryParams().getInteger("limit").orElse(null));
    var events = componentClient
        .forEventSourcedEntity(bucket)
        .method(BucketEntity::events)
        .invoke(range);
    return new EventsView(events.bucket(), events.events().stream().map(TrackingEndpoint::toApi)
        .toList(), events.complete(), events.count());
  }

  @Get("/buckets")
  public BucketsView buckets() {
    var rows = componentClient.forView().method(BucketsByNameView::all).invoke();
    return new BucketsView(rows.buckets().stream()
        .map(row -> new BucketSummary(row.bucket(), row.type(), row.client(), row.hostname(),
            row.events(),
            row.lastWrittenMillis() == 0
                ? null
                : Instant.ofEpochMilli(row.lastWrittenMillis()).toString()))
        .toList());
  }

  /**
   * Every event as it is written — SPEC-001 §3 rule 33.
   *
   * <p>The stream starts where the subscriber attached. A subscriber that reattaches after a
   * break reads {@link #events} over the span it missed; the events carry their own times, so
   * there is nothing to guess at.
   */
  @Get("/buckets/{bucket}/watch")
  public HttpResponse watch(String bucket) {
    Source<WrittenView, NotUsed> source = componentClient
        .forEventSourcedEntity(bucket)
        .notificationStream(BucketEntity::updates)
        .source()
        .map(TrackingEndpoint::toApi);
    return HttpResponses.serverSentEvents(source);
  }

  /** What was spent on what — the canonical query over two buckets. */
  @Post("/activities")
  public ActivitiesView activities(ActivityRequest request) {
    if (request == null || request.windowBucket() == null || request.idleBucket() == null) {
      throw HttpException.badRequest(
          "activities need a bucket of what was on screen and a bucket of when the machine "
              + "was in use");
    }
    Instant from = request.from() == null ? null : parse(request.from());
    Instant to = request.to() == null ? null : parse(request.to());
    double pulsetime = request.pulsetime() == null ? DEFAULT_PULSETIME : request.pulsetime();
    List<String> keys = request.keys() == null || request.keys().isEmpty()
        ? DEFAULT_KEYS
        : request.keys();

    List<Event> window = read(request.windowBucket(), from, to);
    List<Event> idle = read(request.idleBucket(), from, to);
    List<Event> activities = Activities.query(window, idle, pulsetime, keys);

    return new ActivitiesView(
        activities.stream().map(e -> toApi(new BucketState.Stored(0, e))).toList(),
        seconds(Activities.total(activities)));
  }

  @Post("/watchers/{watcher}")
  public HttpResponse startWatcher(String watcher, StartRequest request) {
    if (request == null) {
      throw HttpException.badRequest("a watcher needs a bucket, a timeout and a poll interval");
    }
    var status = componentClient
        .forEventSourcedEntity(watcher)
        .method(IdleWatcherEntity::start)
        .invoke(new IdleWatcherEntity.Start(request.bucket(), request.timeoutSeconds(),
            request.pollSeconds()));
    return HttpResponses.created(toApi(status));
  }

  @Post("/watchers/{watcher}/observe")
  public ObservedView observe(String watcher, ObserveRequest request) {
    if (request == null) {
      throw HttpException.badRequest("an observation needs a time and an idle reading");
    }
    var observed = componentClient
        .forEventSourcedEntity(watcher)
        .method(IdleWatcherEntity::observe)
        .invoke(new IdleWatcherEntity.Observation(parse(request.at()), request.idleSeconds()));
    return new ObservedView(observed.idle(), observed.pings().stream()
        .map(ping -> toApi(new BucketState.Stored(0, ping.asEvent())))
        .toList());
  }

  @Get("/watchers/{watcher}")
  public WatcherView watcher(String watcher) {
    var status = componentClient
        .forEventSourcedEntity(watcher)
        .method(IdleWatcherEntity::status)
        .invoke();
    if (!status.started()) {
      throw HttpException.notFound();
    }
    return toApi(status);
  }

  private List<Event> read(String bucket, Instant from, Instant to) {
    var events = componentClient
        .forEventSourcedEntity(bucket)
        .method(BucketEntity::events)
        .invoke(new BucketEntity.Range(
            from == null ? null : from.toEpochMilli(),
            to == null ? null : to.toEpochMilli(),
            null));
    return events.events().stream().map(BucketState.Stored::event).toList();
  }

  private java.util.Optional<Instant> optionalInstant(String name) {
    return requestContext().queryParams().getString(name)
        .filter(value -> !value.isBlank())
        .map(TrackingEndpoint::parse);
  }

  private static Instant parse(String timestamp) {
    if (timestamp == null || timestamp.isBlank()) {
      throw HttpException.badRequest("a timestamp is required");
    }
    try {
      return Instant.parse(timestamp);
    } catch (DateTimeException e) {
      // The value, not the exception: a caller who sent "2026-13-01" needs to see which of
      // their fields was rejected, and the parser's own wording says nothing about that.
      throw HttpException.badRequest("not an ISO-8601 instant: " + timestamp);
    }
  }

  private static BucketView toApi(BucketEntity.Info info) {
    return new BucketView(info.bucket(), info.type(), info.client(), info.hostname(),
        info.count(), info.retained(), info.complete());
  }

  private static WrittenView toApi(BucketEntity.Written written) {
    return new WrittenView(written.bucket(), written.action(), written.id(),
        toApi(new BucketState.Stored(written.id(), written.event())));
  }

  private static EventView toApi(BucketState.Stored stored) {
    return new EventView(stored.id(), stored.event().timestamp().toString(),
        seconds(stored.event().duration()), stored.event().data());
  }

  private static WatcherView toApi(IdleWatcherEntity.Status status) {
    return new WatcherView(status.watcher(), status.bucket(), status.timeoutSeconds(),
        status.pollSeconds(), status.idle());
  }

  private static double seconds(Duration duration) {
    return duration.toNanos() / 1_000_000_000d;
  }
}
