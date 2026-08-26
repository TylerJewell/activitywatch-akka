package io.akka.activitywatch.watcher;

import io.akka.activitywatch.client.ActivityWatchClient;
import io.akka.activitywatch.domain.Event;
import io.akka.activitywatch.domain.WindowRule;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The watcher that says what is in front — SPEC-001 §3 R94–R97, R99.
 *
 * <p>A poll that cannot read a window sends nothing at all. Sending "unknown" would be a
 * record of an application called unknown, which is a different claim from "we did not look
 * successfully" and would show up in a day's totals as time spent on it.
 */
public class WindowWatcher implements Runnable {

  public static final String CLIENT_NAME = "aw-watcher-window";
  public static final String EVENT_TYPE = "currentwindow";

  private final ActivityWatchClient client;
  private final Sensors.Window sensor;
  private final Supplier<Instant> clock;
  private final double pollSeconds;
  private final boolean excludeTitle;
  private final List<Pattern> excludeTitles;
  private final Map<String, String> researchCategoryMap;
  private final Map<String, String> researchAppCategoryMap;
  private final String bucketId;
  private volatile boolean running = true;

  public WindowWatcher(ActivityWatchClient client, Sensors.Window sensor, double pollSeconds,
      boolean excludeTitle, List<Pattern> excludeTitles,
      Map<String, String> researchCategoryMap, Map<String, String> researchAppCategoryMap) {
    this(client, sensor, Instant::now, pollSeconds, excludeTitle, excludeTitles,
        researchCategoryMap, researchAppCategoryMap);
  }

  public WindowWatcher(ActivityWatchClient client, Sensors.Window sensor,
      Supplier<Instant> clock, double pollSeconds, boolean excludeTitle,
      List<Pattern> excludeTitles, Map<String, String> researchCategoryMap,
      Map<String, String> researchAppCategoryMap) {
    this.client = client;
    this.sensor = sensor;
    this.clock = clock;
    this.pollSeconds = pollSeconds;
    this.excludeTitle = excludeTitle;
    this.excludeTitles = excludeTitles == null ? List.of() : List.copyOf(excludeTitles);
    this.researchCategoryMap = researchCategoryMap;
    this.researchAppCategoryMap = researchAppCategoryMap;
    this.bucketId = CLIENT_NAME + "_" + client.hostname();
  }

  public String bucketId() {
    return bucketId;
  }

  public double pulsetime() {
    return WindowRule.computePulsetime(pollSeconds);
  }

  public void stop() {
    running = false;
  }

  @Override
  public void run() {
    client.createBucket(bucketId, EVENT_TYPE, true);
    client.waitForStart(java.time.Duration.ofSeconds(30));
    client.connect();
    while (running) {
      poll();
      try {
        Thread.sleep((long) (pollSeconds * 1000));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  /** One poll. Visible for testing, which scripts the sensor and keeps the rule real. */
  public void poll() {
    Map<String, Object> window;
    try {
      window = sensor.currentWindow();
    } catch (RuntimeException e) {
      // R97: anything that is not fatal is logged and the loop carries on, because a window
      // that cannot be read this second is usually readable the next.
      return;
    }
    if (window == null || window.isEmpty()) {
      return;
    }
    Map<String, Object> data = WindowRule.transformWindow(window, excludeTitle, excludeTitles,
        researchCategoryMap, researchAppCategoryMap);
    client.heartbeat(bucketId, Event.of(clock.get(), 0d, data), pulsetime(), true);
  }
}
