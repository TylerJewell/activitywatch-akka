package io.akka.activitywatch.watcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.activitywatch.client.ActivityWatchClient;
import io.akka.activitywatch.domain.Event;
import io.akka.activitywatch.domain.InputRule;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The three watchers, driven over a scripted set of readings —
 * SPEC-001 §3 R88–R99.
 *
 * <p>What is stood in for is the clock, the operating system's sensor and the network — the
 * three things a rule is never about. The loop, the state it carries between readings and the
 * decision each one produces are the real ones.
 *
 * <p>The readings are reachable: idle time grows by one poll interval each poll and drops to
 * zero when someone touches the machine. A sequence that jumped is a sequence no running
 * machine produces, and a claim written from one would describe a state the system is never
 * in.
 */
class WatcherTest {

  private static final Instant T0 = Instant.parse("2020-01-01T00:00:00Z");

  /** Records what a watcher sent instead of sending it. */
  private static final class Recorder extends ActivityWatchClient {
    private final List<Map<String, Object>> sent = new ArrayList<>();

    Recorder() {
      super("aw-watcher-test", "127.0.0.1", 1, false, null,
          java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")));
    }

    @Override
    public void heartbeat(String bucketId, Event event, double pulsetime, boolean queued) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("bucket", bucketId);
      row.put("timestamp", io.akka.activitywatch.api.Json.instant(event.timestamp()));
      row.put("duration", event.durationSeconds());
      row.put("data", event.data());
      row.put("pulsetime", pulsetime);
      sent.add(row);
    }

    @Override
    public void createBucket(String bucketId, String eventType, boolean queued) {
      // Nothing to create: the network is what is stood in for here.
    }
  }

  /** A clock that moves one poll interval each time it is read. */
  private static final class Ticking implements java.util.function.Supplier<Instant> {
    private final double pollSeconds;
    private int reads;

    Ticking(double pollSeconds) {
      this.pollSeconds = pollSeconds;
    }

    @Override
    public Instant get() {
      return T0.plus(Duration.ofMillis((long) (reads++ * pollSeconds * 1000)));
    }
  }

  // ------------------------------------------------------------------- afk

  private static List<Map<String, Object>> driveAfk(double[] readings, double timeout,
      double poll) {
    Recorder recorder = new Recorder();
    AfkWatcher watcher = new AfkWatcher(recorder, () -> 0, new Ticking(poll), timeout, poll);
    for (double reading : readings) {
      watcher.observe(reading);
    }
    return recorder.sent;
  }

  @Test
  void anActiveMachineSendsOnePingPerPollStampedAtTheLastInput() {
    List<Map<String, Object>> pings = driveAfk(new double[] {0, 0, 0}, 10, 5);
    assertEquals(3, pings.size());
    for (Map<String, Object> ping : pings) {
      assertEquals("not-afk", ((Map<?, ?>) ping.get("data")).get("status"));
      assertEquals(0.0, (Double) ping.get("duration"), 1e-9);
      assertEquals(15.0, (Double) ping.get("pulsetime"), 1e-9,
          "every ping carries timeout + poll, so one missed poll never splits a run");
    }
  }

  @Test
  void goingAwayAndComingBackIsFourPingsAroundTwoTransitions() {
    // Idle grows by one poll each poll, then input arrives: a machine really does this.
    List<Map<String, Object>> pings = driveAfk(new double[] {0, 5, 10, 15, 0, 0}, 10, 5);
    assertEquals(List.of("not-afk", "not-afk", "not-afk", "afk", "afk", "afk", "not-afk",
        "not-afk"), pings.stream().map(p -> ((Map<?, ?>) p.get("data")).get("status")).toList());

    // The transition into away closes the active run at the last input and opens the away
    // one a millisecond later, carrying the whole idle time.
    assertEquals("2020-01-01T00:00:00+00:00", pings.get(2).get("timestamp"));
    assertEquals("2020-01-01T00:00:00.001000+00:00", pings.get(3).get("timestamp"));
    assertEquals(10.0, (Double) pings.get(3).get("duration"), 1e-9);
    assertEquals(15.0, (Double) pings.get(4).get("duration"), 1e-9,
        "while away only the length grows; the stamp stays at the last input");
  }

  @Test
  void theThresholdIsInclusive() {
    List<Map<String, Object>> atTheEdge = driveAfk(new double[] {0, 5, 10}, 10, 5);
    assertEquals("afk", ((Map<?, ?>) atTheEdge.get(atTheEdge.size() - 1).get("data"))
        .get("status"), "idle equal to the timeout counts as away");

    List<Map<String, Object>> justUnder = driveAfk(new double[] {0, 5, 9}, 10, 5);
    assertTrue(justUnder.stream().noneMatch(
        p -> "afk".equals(((Map<?, ?>) p.get("data")).get("status"))),
        "a second under it does not");
  }

  @Test
  void aWatcherStartsAssumingSomeoneIsThere() {
    List<Map<String, Object>> pings = driveAfk(new double[] {30}, 10, 5);
    assertEquals(2, pings.size(), "the first reading is a transition, not a steady state");
    assertEquals("not-afk", ((Map<?, ?>) pings.get(0).get("data")).get("status"));
    assertEquals("afk", ((Map<?, ?>) pings.get(1).get("data")).get("status"));
  }

  @Test
  void aTimeoutShorterThanThePollIsRefused() {
    assertThrows(IllegalArgumentException.class,
        () -> new AfkWatcher(new Recorder(), () -> 0, 1, 5));
  }

  @Test
  void theAfkWatcherWritesToItsOwnBucket() {
    AfkWatcher watcher = new AfkWatcher(new Recorder(), () -> 0, 180, 5);
    assertTrue(watcher.bucketId().startsWith("aw-watcher-afk_"));
  }

  // ---------------------------------------------------------------- window

  @Test
  void aWindowPollSendsWhatWasInFront() {
    Recorder recorder = new Recorder();
    WindowWatcher watcher = new WindowWatcher(recorder,
        () -> Sensors.window("code.exe", "main.java"), new Ticking(1), 1, false, List.of(),
        null, null);
    watcher.poll();
    assertEquals(1, recorder.sent.size());
    Map<?, ?> data = (Map<?, ?>) recorder.sent.get(0).get("data");
    assertEquals("code.exe", data.get("app"));
    assertEquals("main.java", data.get("title"));
    assertEquals(2.0, (Double) recorder.sent.get(0).get("pulsetime"), 1e-9);
  }

  @Test
  void aPollThatCannotReadAWindowSendsNothing() {
    Recorder recorder = new Recorder();
    WindowWatcher blind = new WindowWatcher(recorder, Map::of, new Ticking(1), 1, false,
        List.of(), null, null);
    blind.poll();
    assertEquals(0, recorder.sent.size(),
        "no reading is not a reading of an application called unknown");

    WindowWatcher broken = new WindowWatcher(recorder, () -> {
      throw new IllegalStateException("the display went away");
    }, new Ticking(1), 1, false, List.of(), null, null);
    broken.poll();
    assertEquals(0, recorder.sent.size(), "and a sensor that threw is not fatal");
  }

  @Test
  void anExcludedTitleIsReplacedBeforeItIsSent() {
    Recorder recorder = new Recorder();
    new WindowWatcher(recorder, () -> Sensors.window("chrome.exe", "My Bank"),
        new Ticking(1), 1, false, List.of(Pattern.compile("bank", Pattern.CASE_INSENSITIVE)),
        null, null).poll();
    assertEquals("excluded", ((Map<?, ?>) recorder.sent.get(0).get("data")).get("title"));
  }

  @Test
  void researchModeReplacesBothTitleRules() {
    Recorder recorder = new Recorder();
    Map<String, String> categories = new LinkedHashMap<>();
    categories.put("github", "Work");
    new WindowWatcher(recorder, () -> Sensors.window("chrome.exe", "GitHub - repo"),
        new Ticking(1), 1, true, List.of(), categories, null).poll();
    assertEquals("Work", ((Map<?, ?>) recorder.sent.get(0).get("data")).get("title"),
        "research mode wins over exclude_title, which would have said 'excluded'");
  }

  @Test
  void thePulsetimeScalesWithThePoll() {
    Recorder recorder = new Recorder();
    assertEquals(2.0, new WindowWatcher(recorder, Map::of, 1, false, List.of(), null, null)
        .pulsetime(), 1e-9);
    assertEquals(7.5, new WindowWatcher(recorder, Map::of, 5, false, List.of(), null, null)
        .pulsetime(), 1e-9);
  }

  // ----------------------------------------------------------------- input

  @Test
  void anIntervalWithNoInputMergesWithTheNextOne() {
    Recorder recorder = new Recorder();
    InputWatcher watcher = new InputWatcher(recorder, InputRule.Counts::none, new Ticking(5), 5);
    InputRule.Reading reading = watcher.poll();
    assertEquals(5.1, reading.pulsetime(), 1e-9,
        "an empty interval is sent with a window slightly longer than itself");
    assertEquals(Map.of("presses", 0L, "clicks", 0L, "deltaX", 0L, "deltaY", 0L,
        "scrollX", 0L, "scrollY", 0L), reading.event().data());
  }

  @Test
  void anIntervalWithInputStandsOnItsOwn() {
    Recorder recorder = new Recorder();
    InputWatcher watcher = new InputWatcher(recorder,
        () -> new InputRule.Counts(3, 1, 40, 20, 0, 2), new Ticking(5), 5);
    InputRule.Reading reading = watcher.poll();
    assertEquals(0.0, reading.pulsetime(), 1e-9,
        "an interval that recorded something must not be folded into a neighbour");
    assertEquals(3L, reading.event().data().get("presses"));
    assertEquals(2L, reading.event().data().get("scrollY"));
  }

  @Test
  void anIntervalCoversTheTimeSinceTheLastOne() {
    Recorder recorder = new Recorder();
    InputWatcher watcher = new InputWatcher(recorder, InputRule.Counts::none, new Ticking(5), 5);
    watcher.poll();
    InputRule.Reading second = watcher.poll();
    assertEquals(5.0, second.event().durationSeconds(), 1e-9);
  }

  @Test
  void thePollIsAlignedToTheWallClock() {
    assertEquals(3.0, InputRule.secondsUntilNextPoll(12.0, 5), 1e-9);
    assertEquals(5.0, InputRule.secondsUntilNextPoll(10.0, 5), 1e-9);
    assertEquals(5.0, InputRule.secondsUntilNextPoll(-3.0, 5), 1e-9,
        "a clock that moved backwards gives a wait inside the interval, never a negative one");
  }

  @Test
  void eachWatcherWritesToTheBucketNamedAfterItAndTheMachine() {
    Recorder recorder = new Recorder();
    assertTrue(new AfkWatcher(recorder, () -> 0, 180, 5).bucketId()
        .startsWith("aw-watcher-afk_"));
    assertTrue(new WindowWatcher(recorder, Map::of, 1, false, List.of(), null, null).bucketId()
        .startsWith("aw-watcher-window_"));
    assertTrue(new InputWatcher(recorder, InputRule.Counts::none, 5).bucketId()
        .startsWith("aw-watcher-input_"));
  }
}
