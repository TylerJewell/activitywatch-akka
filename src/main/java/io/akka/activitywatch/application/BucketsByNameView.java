package io.akka.activitywatch.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.akka.activitywatch.domain.BucketEvent;
import java.util.List;

/**
 * Which buckets exist and how much each holds.
 *
 * <p>A bucket answers about itself, and answering "what buckets are there" by asking every
 * bucket in turn requires already knowing the answer. This is a row per bucket, kept current
 * as each one is written to.
 *
 * <p>The count is of events written, which stops matching the number readable from the bucket
 * once the retained window fills — SPEC-001 §4 OD-8.
 */
@Component(id = "buckets-by-name")
public class BucketsByNameView extends View {

  /**
   * @param events how many events have been written, including any since dropped
   * @param lastWrittenMillis when the most recently written event starts, or 0 for an empty
   *     bucket
   */
  public record BucketEntry(String bucket, String type, String client, String hostname,
      long events, long lastWrittenMillis) {}

  public record Buckets(List<BucketEntry> buckets) {}

  @Consume.FromEventSourcedEntity(BucketEntity.class)
  public static class BucketsUpdater extends TableUpdater<BucketEntry> {

    public Effect<BucketEntry> onEvent(BucketEvent event) {
      String bucket = updateContext().eventSubject().get();
      BucketEntry current = rowState();
      return switch (event) {
        case BucketEvent.Created e -> effects().updateRow(
            new BucketEntry(bucket, e.type(), e.client(), e.hostname(), 0, 0));
        case BucketEvent.Inserted e -> effects().updateRow(new BucketEntry(
            bucket, current.type(), current.client(), current.hostname(),
            current.events() + 1, e.event().timestamp().toEpochMilli()));
        case BucketEvent.Extended e -> effects().updateRow(current);
      };
    }
  }

  @Query("SELECT * AS buckets FROM buckets_by_name ORDER BY bucket")
  public QueryEffect<Buckets> all() {
    return queryResult();
  }
}
