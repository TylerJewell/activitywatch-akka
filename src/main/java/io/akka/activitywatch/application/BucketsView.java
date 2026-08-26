package io.akka.activitywatch.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.akka.activitywatch.domain.BucketEvent;
import io.akka.activitywatch.domain.PyJson;
import java.util.List;
import java.util.Optional;

/**
 * Which buckets exist, and what has just changed in each — SPEC-001 §3 R43, R112.
 *
 * <p>A bucket answers about itself, and answering "what buckets are there" by asking every
 * bucket in turn requires already knowing the answer. This is a row per bucket, kept current
 * as each one is written to, and it is also what the interface subscribes to: a row carries
 * the change that produced it, so a subscriber can apply it without asking for anything.
 *
 * <p>{@code changes} counts every change to the bucket, not every event. A subscriber that
 * sees it jump by more than one knows a burst was coalesced into a single row and re-reads the
 * span it is showing — which is the half of RENDERING.md R1.3 that says nothing is missing.
 *
 * <p>Three fields are {@link Optional} because they are genuinely absent on some rows: a
 * bucket usually has no name, a bucket with no events has no last update, and the first row a
 * bucket has carries no change. A view row's field that is sometimes null has to say so in its
 * type, or the view's own update stream fails on the first row that leaves it out and every
 * query against the view then answers empty.
 */
@Component(id = "buckets")
public class BucketsView extends View {

  /**
   * @param createdMillis when the bucket was made, which is the order the original lists them
   *     in and therefore the order `find_bucket` walks
   * @param events how many events the bucket holds
   * @param lastUpdatedMillis the end of the most recent event, absent for an empty bucket
   * @param changes how many changes this bucket has had, of any kind
   * @param lastChangeKind what the most recent change was, absent on a bucket just created
   * @param lastChangeJson the event that change was about, as JSON, absent for a change that
   *     was not about an event
   */
  public record BucketRow(String bucket, Optional<String> name, String type, String client,
      String hostname, String created, long createdMillis, String dataJson, long events,
      Optional<Long> lastUpdatedMillis, long changes, Optional<String> lastChangeKind,
      Optional<String> lastChangeJson) {}

  public record Buckets(List<BucketRow> buckets) {}

  @Consume.FromEventSourcedEntity(BucketEntity.class)
  public static class BucketsUpdater extends TableUpdater<BucketRow> {

    public Effect<BucketRow> onEvent(BucketEvent event) {
      String bucket = updateContext().eventSubject().get();
      BucketRow current = rowState();
      return switch (event) {
        case BucketEvent.Created e -> effects().updateRow(new BucketRow(
            bucket, Optional.ofNullable(e.name()), e.type(), e.client(), e.hostname(),
            e.created(), millis(e.created()), PyJson.dumps(e.data()), 0, Optional.empty(),
            1, Optional.of("bucket-created"), Optional.empty()));
        case BucketEvent.Updated e -> effects().updateRow(new BucketRow(
            bucket,
            e.name() == null ? current.name() : Optional.of(e.name()),
            e.type() == null ? current.type() : e.type(),
            e.client() == null ? current.client() : e.client(),
            e.hostname() == null ? current.hostname() : e.hostname(),
            current.created(), current.createdMillis(),
            e.data() == null ? current.dataJson() : PyJson.dumps(e.data()),
            current.events(), current.lastUpdatedMillis(), current.changes() + 1,
            Optional.of("bucket-updated"), Optional.empty()));
        case BucketEvent.Deleted e -> effects().deleteRow();
        case BucketEvent.Inserted e -> effects().updateRow(changed(current, "event-inserted",
            current.events() + 1, e.event().withId(e.id())));
        case BucketEvent.Extended e -> effects().updateRow(changed(current, "event-extended",
            current.events(), null));
        case BucketEvent.Replaced e -> effects().updateRow(changed(current, "event-replaced",
            current.events(), e.event().withId(e.id())));
        case BucketEvent.Removed e -> effects().updateRow(changed(current, "event-removed",
            Math.max(0, current.events() - 1), null));
      };
    }

    private static BucketRow changed(BucketRow current, String kind, long events,
        io.akka.activitywatch.domain.Event event) {
      long lastUpdated = event == null
          ? current.lastUpdatedMillis().orElse(0L)
          : Math.max(current.lastUpdatedMillis().orElse(Long.MIN_VALUE),
              event.end().toEpochMilli());
      return new BucketRow(current.bucket(), current.name(), current.type(), current.client(),
          current.hostname(), current.created(), current.createdMillis(), current.dataJson(),
          events, events == 0 ? Optional.empty() : Optional.of(lastUpdated),
          current.changes() + 1, Optional.of(kind),
          event == null ? Optional.empty()
              : Optional.of(PyJson.dumps(io.akka.activitywatch.api.Json.event(event))));
    }

    private static long millis(String created) {
      if (created == null) {
        return 0;
      }
      try {
        return java.time.OffsetDateTime.parse(created).toInstant().toEpochMilli();
      } catch (RuntimeException e) {
        return 0;
      }
    }
  }

  /** Creation order, which is the order the original's own listing comes back in. */
  @Query("SELECT * AS buckets FROM buckets ORDER BY createdMillis, bucket")
  public QueryEffect<Buckets> all() {
    return queryResult();
  }

  /**
   * The same rows, and then every change to them as it happens — RENDERING.md R1.
   *
   * <p>The current rows arrive first, which is what lets the first render show server state
   * without a second round trip (R1.4).
   *
   * <p>No ordering: a streaming query cannot have one, and there is nothing for it to mean
   * here anyway — after the current rows have been delivered the order is the order changes
   * happen in, and a subscriber applies each row by its own bucket id rather than by position.
   */
  @Query(value = "SELECT * FROM buckets", streamUpdates = true)
  public QueryStreamEffect<BucketRow> live() {
    return queryStreamResult();
  }
}
