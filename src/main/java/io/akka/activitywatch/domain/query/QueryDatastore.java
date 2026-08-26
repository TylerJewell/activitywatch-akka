package io.akka.activitywatch.domain.query;

import io.akka.activitywatch.domain.Event;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What the query language is allowed to know about storage — SPEC-001 §3 R76, R77.
 *
 * <p>The interface is here rather than in the application layer so the language can be tested
 * against a list of events with no runtime around it, and so the rules about which events a
 * range selects live in one place for both the query path and the HTTP path.
 */
public interface QueryDatastore {

  /** Bucket ids in the order the store lists them, which is the order `find_bucket` walks. */
  List<String> buckets();

  /**
   * Whether a bucket exists, asked of the bucket itself.
   *
   * <p>Separate from {@link #buckets()} because the listing is a projection and settles a
   * moment after the write that changed it, while a caller that has just created a bucket and
   * immediately queries it must not be told there is no such bucket.
   */
  boolean exists(String bucketId);

  /** A bucket's metadata, including `hostname`, or null when there is no such bucket. */
  Map<String, Object> metadata(String bucketId);

  /** Events in the range, already selected, clipped and ordered by R33–R36. */
  List<Event> events(String bucketId, Instant start, Instant end);

  long eventCount(String bucketId, Instant start, Instant end);
}
