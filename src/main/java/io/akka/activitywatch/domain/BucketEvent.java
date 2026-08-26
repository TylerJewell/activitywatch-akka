package io.akka.activitywatch.domain;

import akka.javasdk.annotations.TypeName;
import java.time.Duration;
import java.util.Map;

/**
 * What has happened to a bucket.
 *
 * <p>These are replayed twice: once by the bucket, which keeps the metadata and the events it
 * wrote recently, and once by the page for the day the event belongs to, which keeps the
 * history. One description of what a write means rather than two to keep in step.
 */
public sealed interface BucketEvent {

  @TypeName("bucket-created")
  record Created(String name, String type, String client, String hostname, String created,
      Map<String, Object> data, int retained) implements BucketEvent {}

  /** Only the fields that were given are changed; the rest keep their values. */
  @TypeName("bucket-updated")
  record Updated(String name, String type, String client, String hostname,
      Map<String, Object> data) implements BucketEvent {}

  /**
   * The bucket is gone.
   *
   * <p>The journal keeps the record of it having existed, which is what lets a later
   * re-creation start from a clean state rather than from whatever was there before.
   */
  @TypeName("bucket-deleted")
  record Deleted() implements BucketEvent {}

  /**
   * An event written into the bucket.
   *
   * <p>The identity is assigned here and never reused while the bucket lives, because a later
   * heartbeat says which event it lengthened by naming it — SPEC-001 §4 OD-1.
   */
  @TypeName("event-inserted")
  record Inserted(long id, Event event) implements BucketEvent {}

  /** A heartbeat that lengthened the event named by {@code id}. */
  @TypeName("event-extended")
  record Extended(long id, Duration duration) implements BucketEvent {}

  /** An event overwritten in place, keeping its identity. */
  @TypeName("event-replaced")
  record Replaced(long id, Event event) implements BucketEvent {}

  /** An event removed. */
  @TypeName("event-removed")
  record Removed(long id) implements BucketEvent {}
}
