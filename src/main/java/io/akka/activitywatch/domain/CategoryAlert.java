package io.akka.activitywatch.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * One category being watched against a list of times — SPEC-001 §3 R131–R134.
 *
 * <p>This is the only thing in the notification service that carries state between one
 * look and the next, and all of it is here: how much time the category has had, the largest
 * threshold already announced, and when the time was last read. It is deliberately not a
 * component — there is one of these per configured alert inside one running service, the
 * same shape as the process it is a port of.
 *
 * <p>Reading the time is expensive, so an alert works out how long it could possibly be
 * before the next threshold could be reached and does not look again until then. With every
 * threshold already announced that is the small hours of tomorrow, which is when the day the
 * service reports on rolls over.
 */
public final class CategoryAlert {

  private final String category;
  private final String label;
  private final List<Duration> thresholds;
  private final boolean positive;
  private final ZoneId zone;

  private Duration maxTriggered = Duration.ZERO;
  private Duration timeSpent = Duration.ZERO;
  private Instant lastCheck = Instant.EPOCH;

  public CategoryAlert(NotifyConfig.Alert alert, ZoneId zone) {
    this.category = alert.category();
    this.label = alert.labelOrCategory();
    this.thresholds = List.copyOf(alert.thresholds());
    this.positive = alert.positive();
    this.zone = zone;
  }

  public String category() {
    return category;
  }

  public Duration timeSpent() {
    return timeSpent;
  }

  public Duration maxTriggered() {
    return maxTriggered;
  }

  /** R131. */
  public List<Duration> untriggered() {
    List<Duration> out = new ArrayList<>(thresholds.size());
    for (Duration threshold : thresholds) {
      if (threshold.compareTo(maxTriggered) > 0) {
        out.add(threshold);
      }
    }
    return out;
  }

  /** R132. */
  public Duration timeToNextThreshold(Instant now) {
    List<Duration> untriggered = untriggered();
    if (untriggered.isEmpty()) {
      LocalDate today = now.atZone(zone).toLocalDate();
      Instant midnight = today.atStartOfDay(zone).toInstant();
      if (midnight.isBefore(now)) {
        midnight = today.plusDays(1).atStartOfDay(zone).toInstant();
      }
      Duration smallest = thresholds.stream().min(Comparator.naturalOrder())
          .orElse(Duration.ZERO);
      return Duration.between(now, midnight).plus(NotifyRules.DAY_OFFSET).plus(smallest);
    }
    Duration next = untriggered.stream().min(Comparator.naturalOrder()).orElseThrow()
        .minus(timeSpent);
    return next.isNegative() ? Duration.ZERO : next;
  }

  /**
   * R133: read the time again, but only if enough of it could have passed.
   *
   * @param categoryTime asked for the day's time by category, all levels aggregated
   */
  public void update(Instant now, Supplier<Map<String, Double>> categoryTime) {
    if (!now.isAfter(lastCheck.plus(timeToNextThreshold(now)))) {
      return;
    }
    Double seconds = categoryTime.get().get(category);
    if (seconds != null) {
      timeSpent = Duration.ofSeconds((long) (double) seconds);
    }
    lastCheck = now;
  }

  /** What a triggered threshold says, or null when none was. */
  public record Announcement(String title, String message) {}

  /**
   * R134: the largest threshold the time has passed, announced once.
   *
   * <p>Largest first, so a service that was not running while several were passed announces
   * the highest rather than working up through them — and the ones below it are marked
   * triggered by the same move, because untriggered means greater than the largest triggered.
   */
  public Announcement check() {
    List<Duration> untriggered = untriggered();
    untriggered.sort(Comparator.reverseOrder());
    for (Duration threshold : untriggered) {
      if (threshold.compareTo(timeSpent) <= 0) {
        maxTriggered = threshold;
        String reached = NotifyRules.toHms(threshold);
        String spent = NotifyRules.toHms(timeSpent);
        return new Announcement(positive ? "Goal reached!" : "Time spent",
            reached.equals(spent)
                ? label + ": " + reached
                : label + ": " + reached + "  (" + spent + ")");
      }
    }
    return null;
  }

  /** What the service logs when an alert's reading changes. */
  public String status() {
    return label + ": " + NotifyRules.toHms(timeSpent);
  }
}
