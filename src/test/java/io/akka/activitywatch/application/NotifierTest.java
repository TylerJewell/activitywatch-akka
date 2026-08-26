package io.akka.activitywatch.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The queue in front of the screen — SPEC-001 §3 R141–R143. */
class NotifierTest {

  /** A clock the test moves, so the spacing can be checked without waiting for it. */
  private static final class Clock implements java.util.function.Supplier<Instant> {
    private Instant now = Instant.parse("2020-03-05T12:00:00Z");

    @Override
    public Instant get() {
      return now;
    }

    void advance(Duration by) {
      now = now.plus(by);
    }
  }

  @Test
  void whatIsQueuedIsShownInOrder() {
    List<String> shown = new ArrayList<>();
    Notifier notifier = new Notifier(n -> shown.add(n.displayTitle() + "|" + n.message()));
    notifier.enqueue("first", "a");
    notifier.enqueue("second", "b");
    Clock clock = new Clock();
    notifier.drain(clock, clock::advance);
    assertEquals(List.of("first|a", "second|b"), shown);
  }

  @Test
  void twoNotificationsAreAtLeastASecondApart() {
    List<Instant> at = new ArrayList<>();
    Clock clock = new Clock();
    Notifier notifier = new Notifier(n -> at.add(clock.get()));
    notifier.enqueue("first", "a");
    notifier.enqueue("second", "b");
    notifier.enqueue("third", "c");
    notifier.drain(clock, clock::advance);
    assertEquals(3, at.size());
    assertTrue(Duration.between(at.get(0), at.get(1)).compareTo(Duration.ofSeconds(1)) >= 0,
        "a desktop handed three at once shows one, so they are spaced here instead");
    assertTrue(Duration.between(at.get(1), at.get(2)).compareTo(Duration.ofSeconds(1)) >= 0);
  }

  @Test
  void aCallerOverHttpIsRefusedOnceTenAreWaiting() {
    Notifier notifier = new Notifier(n -> { });
    for (int i = 0; i < Notifier.HTTP_QUEUE_LIMIT; i++) {
      assertTrue(notifier.offer(new Notifier.Notification("t" + i, "m", "a-watcher")));
    }
    assertFalse(notifier.offer(new Notifier.Notification("one too many", "m", "a-watcher")));
    assertEquals(Notifier.HTTP_QUEUE_LIMIT, notifier.waiting());
  }

  @Test
  void theServicesOwnNotificationsAreNeverRefused() {
    Notifier notifier = new Notifier(n -> { });
    for (int i = 0; i < 50; i++) {
      notifier.enqueue("t" + i, "m");
    }
    assertEquals(50, notifier.waiting(),
        "an alert that tripped has already recorded that it tripped, so dropping it loses it");
  }

  @Test
  void aNotificationFromAnotherModuleSaysWhichOne() {
    assertEquals("Backup done (my-script)",
        new Notifier.Notification("Backup done", "m", "my-script").displayTitle());
    assertEquals("Hourly summary",
        new Notifier.Notification("Hourly summary", "m", null).displayTitle());
  }

  @Test
  void outputOnlyPrintsOneObjectPerLine() {
    Instant at = Instant.parse("2020-03-05T12:00:00Z");
    assertEquals(
        "{\"timestamp\":\"2020-03-05T12:00:00Z\",\"title\":\"Time today\","
            + "\"message\":\"- Work: 1h\",\"app\":\"ActivityWatch\"}",
        Notifier.outputLine(new Notifier.Notification("Time today", "- Work: 1h", null), at));
    assertTrue(Notifier.outputLine(
        new Notifier.Notification("t", "m", "a-watcher"), at).endsWith(
        ",\"sender\":\"a-watcher\"}"), "and names the sender where there was one");
  }

  @Test
  void thePairSentAtStartUpIsOneWrite() {
    Instant at = Instant.parse("2020-03-05T12:00:00Z");
    String written = Notifier.outputLines(List.of(
        new Notifier.Notification("Time yesterday", "a", null),
        new Notifier.Notification("Time today", "b", null)), at);
    assertEquals(2, written.split("\n", -1).length - 1, "two lines and a trailing newline");
    assertEquals("", Notifier.outputLines(List.of(), at));
  }
}
