package io.akka.activitywatch.domain;

/**
 * An idle watcher: where its heartbeats go, when it decides the machine is untouched, and the
 * one bit it carries between readings — SPEC-001 §3 rules 19, 20.
 *
 * <p>The bit is state rather than a field in a running process, so a watcher that goes away
 * mid-idle comes back mid-idle. The original's watcher starts every run as "present"
 * whatever the machine was doing.
 */
public record IdleState(String bucket, double timeoutSeconds, double pollSeconds, boolean idle) {

  public static IdleState empty() {
    return new IdleState(null, 0, 0, false);
  }

  public boolean started() {
    return bucket != null;
  }

  public IdleState with(IdleEvent event) {
    return switch (event) {
      case IdleEvent.Started e -> new IdleState(e.bucket(), e.timeoutSeconds(), e.pollSeconds(),
          idle);
      case IdleEvent.Observed e -> new IdleState(bucket, timeoutSeconds, pollSeconds, e.idle());
    };
  }
}
