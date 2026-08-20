package io.akka.activitywatch.domain;

import akka.javasdk.annotations.TypeName;
import java.util.List;

/** What has happened to an idle watcher. */
public sealed interface IdleEvent {

  @TypeName("watcher-started")
  record Started(String bucket, double timeoutSeconds, double pollSeconds) implements IdleEvent {}

  /**
   * One reading, and the heartbeats it produced.
   *
   * <p>The heartbeats of a transition are two, and they are recorded together because the pair
   * only means anything in order — SPEC-001 §4 OD-5. A reader of this journal cannot see half
   * of a transition, and cannot see the second half first.
   *
   * <p>The bucket is repeated on every record so that a reader of one record knows where the
   * heartbeats go without having to have read the record that started the watcher.
   */
  @TypeName("input-observed")
  record Observed(String bucket, boolean idle, List<IdleRule.Ping> pings) implements IdleEvent {}
}
