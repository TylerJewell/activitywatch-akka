package io.akka.activitywatch.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Deciding when the machine counts as untouched — SPEC-001 §3 rules 12–19.
 *
 * <p>The rule holds one bit: whether the user is currently away. Everything else it needs
 * arrives with each observation, so it can be applied to a live poll or to a recording of
 * one and give the same answer either way.
 *
 * <p>An observation is a reading of how long the machine has gone untouched, taken at a known
 * instant. The reading, not the instant, is what a heartbeat is stamped with: the user was
 * last there {@code idleSeconds} ago, so that is when their activity actually ends.
 */
public final class IdleRule {

  /** The gap between the two heartbeats of a transition, so the later one sorts after. */
  private static final Duration NUDGE = Duration.ofMillis(1);

  public static final String AWAY = "afk";
  public static final String PRESENT = "not-afk";

  private IdleRule() {}

  /** One heartbeat the watcher would send. */
  public record Ping(Instant timestamp, Duration duration, String status, double pulsetime) {
    public Ping {
      timestamp = timestamp.truncatedTo(ChronoUnit.MILLIS);
    }

    public Event asEvent() {
      return Event.of(timestamp, duration, java.util.Map.of("status", status));
    }
  }

  /**
   * What one observation changed.
   *
   * @param idle whether the user is away after this observation — the bit to carry forward
   * @param pings the heartbeats to send, in the order they must arrive
   */
  public record Outcome(boolean idle, List<Ping> pings) {}

  /**
   * Apply the rule to one reading.
   *
   * @param idle whether the user was away before this reading
   * @param observedAt when the reading was taken
   * @param idleSeconds how long the machine had gone untouched
   * @param timeoutSeconds how long untouched counts as away
   * @param pollSeconds how often readings are taken
   */
  public static Outcome observe(boolean idle, Instant observedAt, double idleSeconds,
      double timeoutSeconds, double pollSeconds) {
    double pulsetime = timeoutSeconds + pollSeconds;
    Instant lastInput = observedAt.minus(Event.seconds(idleSeconds));
    Duration idleFor = Event.seconds(idleSeconds);

    if (idle && idleSeconds < timeoutSeconds) {
      // Back. The away stretch is stretched up to the moment input resumed, and the present
      // one opens just after it, so the two never claim the same instant.
      return new Outcome(false, List.of(
          new Ping(lastInput, Duration.ZERO, AWAY, pulsetime),
          new Ping(lastInput.plus(NUDGE), Duration.ZERO, PRESENT, pulsetime)));
    }

    if (!idle && idleSeconds >= timeoutSeconds) {
      // Away. The present stretch is closed at the last input, and the away one covers
      // everything since.
      return new Outcome(true, List.of(
          new Ping(lastInput, Duration.ZERO, PRESENT, pulsetime),
          new Ping(lastInput.plus(NUDGE), idleFor, AWAY, pulsetime)));
    }

    if (idle) {
      return new Outcome(true, List.of(
          new Ping(lastInput.plus(NUDGE), idleFor, AWAY, pulsetime)));
    }
    return new Outcome(false, List.of(
        new Ping(lastInput, Duration.ZERO, PRESENT, pulsetime)));
  }
}
