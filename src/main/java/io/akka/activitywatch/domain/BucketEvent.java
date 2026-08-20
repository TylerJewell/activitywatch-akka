package io.akka.activitywatch.domain;

import akka.javasdk.annotations.TypeName;
import java.time.Duration;

/**
 * What has happened to a bucket.
 *
 * <p>{@code commandId} is what the writer called this heartbeat, or null where the writer had
 * no name for it. It is recorded so that replaying the journal rebuilds the set of heartbeats
 * already applied — SPEC-001 §3 rule 6a.
 */
public sealed interface BucketEvent {

  @TypeName("bucket-created")
  record Created(String type, String client, String hostname, int retained)
      implements BucketEvent {}

  /**
   * A heartbeat that started a new stretch.
   *
   * <p>The identity is assigned here and never reused, because a later heartbeat says which
   * event it lengthened by naming it — SPEC-001 §3 rule 8.
   */
  @TypeName("event-inserted")
  record Inserted(long id, Event event, String commandId) implements BucketEvent {}

  /** A heartbeat that lengthened the stretch named by {@code id}. */
  @TypeName("event-extended")
  record Extended(long id, Duration duration, String commandId) implements BucketEvent {}
}
