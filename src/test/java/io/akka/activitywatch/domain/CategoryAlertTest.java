package io.akka.activitywatch.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** One category watched against a list of times — SPEC-001 §3 R131–R134. */
class CategoryAlertTest {

  private static final ZoneId UTC = ZoneId.of("UTC");
  private static final Instant NOON = Instant.parse("2020-03-05T12:00:00Z");

  private static CategoryAlert alert(boolean positive, long... minutes) {
    List<Duration> thresholds = new java.util.ArrayList<>();
    for (long value : minutes) {
      thresholds.add(Duration.ofMinutes(value));
    }
    return new CategoryAlert(
        new NotifyConfig.Alert("Work", "💼 Work", List.copyOf(thresholds), positive), UTC);
  }

  private static java.util.function.Supplier<Map<String, Double>> spent(double seconds) {
    return () -> Map.of("Work", seconds);
  }

  @Test
  void everyThresholdIsUntriggeredUntilOneIsReached() {
    CategoryAlert alert = alert(false, 15, 30, 60);
    assertEquals(3, alert.untriggered().size());
    alert.update(NOON, spent(1900));
    assertNotNull(alert.check());
    assertEquals(List.of(Duration.ofMinutes(60)), alert.untriggered(),
        "reaching thirty marks fifteen as reached too, because untriggered means above it");
  }

  @Test
  void theLargestThresholdReachedIsTheOneAnnounced() {
    CategoryAlert alert = alert(false, 15, 30, 60);
    alert.update(NOON, spent(3600));
    CategoryAlert.Announcement announcement = alert.check();
    assertEquals("Time spent", announcement.title());
    assertEquals("💼 Work: 1h", announcement.message(),
        "an hour spent against an hour's threshold reads once, not twice");
  }

  @Test
  void aThresholdPassedBySomeMarginNamesBoth() {
    CategoryAlert alert = alert(false, 60);
    alert.update(NOON, spent(4500));
    assertEquals("💼 Work: 1h  (1h 15m)", alert.check().message());
  }

  @Test
  void anAlertThatIsAnAchievementSaysSo() {
    CategoryAlert alert = alert(true, 15);
    alert.update(NOON, spent(900));
    assertEquals("Goal reached!", alert.check().title());
  }

  @Test
  void nothingIsAnnouncedTwice() {
    CategoryAlert alert = alert(false, 15);
    alert.update(NOON, spent(900));
    assertNotNull(alert.check());
    assertNull(alert.check(), "the same reading again says nothing");
  }

  @Test
  void theTimeIsNotReReadUntilAThresholdCouldHaveBeenReached() {
    CategoryAlert alert = alert(false, 60);
    alert.update(NOON, spent(600));
    assertEquals(Duration.ofSeconds(600), alert.timeSpent());
    // Fifty minutes short of the threshold, so nothing before then can change the answer.
    alert.update(NOON.plusSeconds(60), spent(9999));
    assertEquals(Duration.ofSeconds(600), alert.timeSpent());
    alert.update(NOON.plus(Duration.ofMinutes(51)), spent(9999));
    assertEquals(Duration.ofSeconds(9999), alert.timeSpent());
  }

  @Test
  void withEveryThresholdReachedTheNextLookIsTomorrowMorning() {
    CategoryAlert alert = alert(false, 15);
    alert.update(NOON, spent(900));
    assertNotNull(alert.check());
    Duration wait = alert.timeToNextThreshold(NOON);
    assertEquals(Duration.ofHours(16).plus(Duration.ofMinutes(15)), wait,
        "midnight is twelve hours off, the day the service reports on turns over four hours "
            + "after that, and the smallest threshold is fifteen minutes past it");
  }

  @Test
  void anAlertReportsWhatItHasSeen() {
    CategoryAlert alert = alert(false, 60);
    alert.update(NOON, spent(1800));
    assertEquals("💼 Work: 30m", alert.status());
  }

  @Test
  void anAlertWithNoLabelIsCalledByItsCategory() {
    CategoryAlert alert = new CategoryAlert(
        new NotifyConfig.Alert("Media", null, List.of(Duration.ofMinutes(1)), false), UTC);
    alert.update(NOON, () -> Map.of("Media", 120.0));
    assertTrue(alert.check().message().startsWith("Media: "));
  }
}
