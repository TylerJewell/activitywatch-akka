package io.akka.activitywatch.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the input watcher sends for one polling interval — SPEC-001 §3 R98.
 *
 * <p>The counters are what the operating system reported; the rule is what is done with them.
 * An interval in which nothing happened is sent with a pulse window slightly longer than the
 * interval so consecutive empty intervals merge into one; an interval with input is sent with
 * no pulse window at all so it stands on its own and its counts are not folded into a
 * neighbour's.
 */
public final class InputRule {

  /** One interval's counts, in the order the original writes them. */
  public record Counts(long presses, long clicks, long deltaX, long deltaY, long scrollX,
      long scrollY) {

    public static Counts none() {
      return new Counts(0, 0, 0, 0, 0, 0);
    }

    public boolean empty() {
      return presses == 0 && clicks == 0 && deltaX == 0 && deltaY == 0 && scrollX == 0
          && scrollY == 0;
    }

    public Map<String, Object> asData() {
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("presses", presses);
      data.put("clicks", clicks);
      data.put("deltaX", deltaX);
      data.put("deltaY", deltaY);
      data.put("scrollX", scrollX);
      data.put("scrollY", scrollY);
      return data;
    }
  }

  /** The heartbeat for one interval, and the pulse window it must be sent with. */
  public record Reading(Event event, double pulsetime) {}

  private InputRule() {}

  public static Reading reading(Instant from, Instant to, Counts counts, double pollTimeSeconds) {
    double pulsetime = counts.empty() ? pollTimeSeconds + 0.1 : 0.0;
    return new Reading(Event.of(from, Duration.between(from, to), counts.asData()), pulsetime);
  }

  /**
   * How long to wait so the next poll lands on a multiple of the interval.
   *
   * <p>Clamped to the interval because the wall clock can move backwards, and a negative sleep
   * would turn one poll into a spin.
   */
  public static double secondsUntilNextPoll(double nowEpochSeconds, double pollTimeSeconds) {
    double remainder = nowEpochSeconds % pollTimeSeconds;
    double untilNext = pollTimeSeconds - remainder;
    return Math.max(Math.min(untilNext, pollTimeSeconds), 0);
  }
}
