package io.akka.activitywatch.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The notification service's own configuration file — SPEC-001 §3 R113–R115. */
class NotifyConfigTest {

  @Test
  void theDefaultsAreFourAlertsAndEverythingElseOn() {
    NotifyConfig defaults = NotifyConfig.defaults();
    assertEquals(List.of("All", "Media > Social Media", "Media", "Work"),
        defaults.alerts().stream().map(NotifyConfig.Alert::category).toList());
    assertEquals(List.of(60L, 120L, 240L, 360L, 480L),
        defaults.alerts().get(0).thresholds().stream().map(Duration::toMinutes).toList());
    assertTrue(defaults.alerts().get(3).positive(), "work is an achievement");
    assertFalse(defaults.alerts().get(2).positive());
    assertTrue(defaults.hourlyCheckins());
    assertTrue(defaults.newDayGreetings());
    assertTrue(defaults.serverMonitoring());
    assertTrue(defaults.productivityScore());
    assertEquals(0, defaults.httpPort(), "nothing may post a notification until asked");
  }

  @Test
  void theDefaultsRoundTripThroughTheFileTheyAreWrittenTo() {
    NotifyConfig defaults = NotifyConfig.defaults();
    NotifyConfig read = NotifyConfig.read(defaults.toToml());
    assertEquals(defaults, read);
  }

  @Test
  void aFileThatDeclaresAlertsReplacesThemRatherThanAddingToThem() {
    NotifyConfig config = NotifyConfig.read("""
        [[alerts]]
        category = "Reading"
        thresholds_minutes = [10, 20]
        positive = true
        """);
    assertEquals(1, config.alerts().size());
    assertEquals("Reading", config.alerts().get(0).category());
    assertNull(config.alerts().get(0).label());
    assertEquals("Reading", config.alerts().get(0).labelOrCategory(),
        "an alert with no label is called by its category");
    assertEquals(List.of(Duration.ofMinutes(10), Duration.ofMinutes(20)),
        config.alerts().get(0).thresholds());
    assertTrue(config.alerts().get(0).positive());
  }

  @Test
  void aFileThatSetsNothingKeepsEveryDefault() {
    assertEquals(NotifyConfig.defaults(), NotifyConfig.read("# nothing here\n"));
  }

  @Test
  void aFlagIsTakenFromTheFileAndTheRestAreNot() {
    NotifyConfig config = NotifyConfig.read("hourly_checkins = false\nhttp_port = 5667\n");
    assertFalse(config.hourlyCheckins());
    assertTrue(config.newDayGreetings());
    assertEquals(5667, config.httpPort());
    assertEquals(4, config.alerts().size(), "and the alerts it did not mention");
  }

  @Test
  void severalAlertsKeepTheOrderTheyWereWrittenIn() {
    NotifyConfig config = NotifyConfig.read("""
        [[alerts]]
        category = "A"
        thresholds_minutes = [1]

        [[alerts]]
        category = "B"
        label = "second"
        thresholds_minutes = [2]
        """);
    assertEquals(List.of("A", "B"),
        config.alerts().stream().map(NotifyConfig.Alert::category).toList());
    assertEquals("second", config.alerts().get(1).label());
  }
}
