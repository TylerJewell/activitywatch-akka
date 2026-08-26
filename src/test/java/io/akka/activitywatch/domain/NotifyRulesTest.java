package io.akka.activitywatch.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What the notification service decides — SPEC-001 §3 R116–R130.
 *
 * <p>The module these came from is Rust and this machine has no toolchain to build it, so
 * unlike every other rule in this port they were established by reading rather than by
 * running (question-log rows 400–430). That makes these tests the only statement of what was
 * read, so each one names the branch it is about rather than checking a value in passing.
 */
class NotifyRulesTest {

  @Test
  void adurationDropsThePartsThatAreZero() {
    assertEquals("1h 30m", NotifyRules.toHms(Duration.ofMinutes(90)));
    assertEquals("2h", NotifyRules.toHms(Duration.ofHours(2)));
    assertEquals("1d 1h", NotifyRules.toHms(Duration.ofHours(25)));
    assertEquals("1d", NotifyRules.toHms(Duration.ofDays(1)));
  }

  @Test
  void secondsAppearOnlyWhenNothingElseDoes() {
    assertEquals("30s", NotifyRules.toHms(Duration.ofSeconds(30)));
    assertEquals("0s", NotifyRules.toHms(Duration.ZERO));
    assertEquals("1m", NotifyRules.toHms(Duration.ofSeconds(90)),
        "ninety seconds is a minute and the thirty are not shown");
  }

  @Test
  void theDayReportedOnStartsFourHoursAfterMidnight() {
    ZoneId utc = ZoneId.of("UTC");
    assertEquals(Instant.parse("2020-03-05T04:00:00Z"),
        NotifyRules.dayStart(Instant.parse("2020-03-05T23:30:00Z"), utc));
    assertEquals(Instant.parse("2020-03-05T04:00:00Z"),
        NotifyRules.dayStart(Instant.parse("2020-03-05T01:00:00Z"), utc),
        "an instant before the offset still names its own calendar day");
  }

  @Test
  void aCategoryIsNamedByItsWholePath() {
    assertEquals("Work > Programming", NotifyRules.categoryNameOf(
        List.of("Work", "Programming")));
    assertEquals("Work", NotifyRules.categoryNameOf("Work"));
    assertEquals("Unknown", NotifyRules.categoryNameOf(List.of()));
    assertEquals("Unknown", NotifyRules.categoryNameOf(7));
  }

  private static Map<String, Double> hierarchy() {
    Map<String, Double> out = new LinkedHashMap<>();
    out.put("Work > Programming > Rust", 1800.0);
    out.put("Work > Programming > Python", 1200.0);
    out.put("Work > Meetings", 900.0);
    out.put("Personal > Reading", 600.0);
    out.put("All", 4500.0);
    return out;
  }

  @Test
  void topLevelAggregationKeepsOnlyTheFirstPartAndLeavesAllAlone() {
    Map<String, Double> out = NotifyRules.aggregateTopLevel(hierarchy());
    assertEquals(3900.0, out.get("Work"));
    assertEquals(600.0, out.get("Personal"));
    assertEquals(4500.0, out.get("All"));
    assertEquals(3, out.size(), "Work, Personal, and All, and nothing below them");
  }

  @Test
  void allLevelsAggregationCountsAnEventOnceForEveryAncestor() {
    Map<String, Double> out = NotifyRules.aggregateAllLevels(hierarchy());
    assertEquals(3900.0, out.get("Work"));
    assertEquals(3000.0, out.get("Work > Programming"));
    assertEquals(1800.0, out.get("Work > Programming > Rust"));
    assertEquals(900.0, out.get("Work > Meetings"));
    assertEquals(600.0, out.get("Personal"));
    assertEquals(600.0, out.get("Personal > Reading"));
    assertEquals(4500.0, out.get("All"));
  }

  @Test
  void aCategoryIsShownOnlyIfItIsMoreThanTheGivenShareOfTheDay() {
    Map<String, Double> time = new LinkedHashMap<>();
    time.put("All", 1000.0);
    time.put("Work", 500.0);
    time.put("Media", 20.0);
    time.put("Comms", 21.0);
    List<NotifyRules.Line> lines = NotifyRules.topCategories(time, 0.02, 4);
    assertEquals(List.of("Work", "Comms"), lines.stream().map(NotifyRules.Line::category)
        .toList(), "twenty of a thousand is exactly two percent, and the test is strict");
  }

  @Test
  void nothingIsShownForADayWithNoTimeInIt() {
    assertTrue(NotifyRules.topCategories(Map.of("All", 0.0), 0.02, 4).isEmpty());
    assertTrue(NotifyRules.topCategories(Map.of(), 0.02, 4).isEmpty(),
        "and none at all is the same as none");
  }

  @Test
  void theLongestComeFirstAndOnlyAsManyAsAsked() {
    Map<String, Double> time = new LinkedHashMap<>();
    time.put("All", 1000.0);
    time.put("A", 100.0);
    time.put("B", 300.0);
    time.put("C", 200.0);
    List<NotifyRules.Line> lines = NotifyRules.topCategories(time, 0.0, 2);
    assertEquals(List.of("B", "C"), lines.stream().map(NotifyRules.Line::category).toList());
  }

  @Test
  void aCategoryCarriesAnIconChosenByItsWholeName() {
    assertEquals("💼", NotifyRules.categoryIcon("Work"));
    assertEquals("💼", NotifyRules.categoryIcon("work"));
    assertEquals("📊", NotifyRules.categoryIcon("Work > Programming"),
        "a path is not one of the names in the table, so it gets the default");
    assertEquals("📊", NotifyRules.categoryIcon("Nothing In Particular"));
    assertEquals("💼 Work", NotifyRules.formatCategory("Work"));
  }

  @Test
  void aSummaryIsOneLinePerCategory() {
    assertEquals("- 💼 Work: 1h\n- 📺 Video: 30m",
        NotifyRules.summaryMessage(List.of(new NotifyRules.Line("Work", "1h"),
            new NotifyRules.Line("Video", "30m"))));
  }

  private static List<Object> classes() {
    return List.of(
        Map.of("name", List.of("Work"), "data", Map.of("score", 1.0)),
        Map.of("name", List.of("Work", "Meetings"), "data", Map.of("score", "-0.5")),
        Map.of("name", List.of("Media"), "data", Map.of("score", -1.0)),
        Map.of("name", List.of("Media", "Music")));
  }

  @Test
  void aCategoryWithNoScoreOfItsOwnTakesItsParents() {
    assertEquals(1.0, NotifyRules.categoryScore(List.of("Work"), classes()));
    assertEquals(1.0, NotifyRules.categoryScore(List.of("Work", "Programming"), classes()),
        "no entry at all: the parent's");
    assertEquals(-1.0, NotifyRules.categoryScore(List.of("Media", "Music"), classes()),
        "an entry with no data: also the parent's");
    assertEquals(-0.5, NotifyRules.categoryScore(List.of("Work", "Meetings"), classes()),
        "a score written as a string is read as a number");
    assertEquals(0.0, NotifyRules.categoryScore(List.of("Nothing"), classes()));
  }

  @Test
  void theProductivityScoreIsHoursTimesScoreAndTheShareThatScoredAboveZero() {
    Map<String, Double> time = new LinkedHashMap<>();
    time.put("Work", 3600.0);
    time.put("Media", 1800.0);
    time.put("All", 5400.0);
    NotifyRules.Productivity productivity = NotifyRules.productivity(time, classes());
    assertEquals(1.0 + (0.5 * -1.0), productivity.score(), 1e-9);
    assertEquals(66.66666, productivity.productivePercent(), 1e-4);
    assertEquals("+0.5 (66.7% productive)", NotifyRules.productivityMessage(productivity));
  }

  @Test
  void thereIsNoProductivityScoreWithoutCategoriesOrWithoutTime() {
    assertNull(NotifyRules.productivity(Map.of("Work", 3600.0, "All", 3600.0), List.of()),
        "nothing to score against");
    assertNull(NotifyRules.productivity(Map.of("All", 0.0), classes()),
        "and nothing to score");
  }
}
