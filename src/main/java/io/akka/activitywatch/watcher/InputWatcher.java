package io.akka.activitywatch.watcher;

import io.akka.activitywatch.client.ActivityWatchClient;
import io.akka.activitywatch.domain.InputRule;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * The watcher that counts what was typed and clicked — SPEC-001 §3 R98, R99.
 *
 * <p>Its poll is aligned to the wall clock rather than to when it started, so two machines
 * recording the same day cut it into the same five-second intervals and a comparison between
 * them lines up.
 */
public class InputWatcher implements Runnable {

  public static final String CLIENT_NAME = "aw-watcher-input";
  public static final String EVENT_TYPE = "os.hid.input";

  private final ActivityWatchClient client;
  private final Sensors.Input sensor;
  private final Supplier<Instant> clock;
  private final double pollSeconds;
  private final String bucketId;
  private volatile boolean running = true;
  private Instant lastRun;

  public InputWatcher(ActivityWatchClient client, Sensors.Input sensor, double pollSeconds) {
    this(client, sensor, Instant::now, pollSeconds);
  }

  public InputWatcher(ActivityWatchClient client, Sensors.Input sensor, Supplier<Instant> clock,
      double pollSeconds) {
    this.client = client;
    this.sensor = sensor;
    this.clock = clock;
    this.pollSeconds = pollSeconds;
    this.bucketId = CLIENT_NAME + "_" + client.hostname();
    this.lastRun = clock.get();
  }

  public String bucketId() {
    return bucketId;
  }

  public void stop() {
    running = false;
  }

  @Override
  public void run() {
    client.waitForStart(java.time.Duration.ofSeconds(30));
    client.connect();
    client.createBucket(bucketId, EVENT_TYPE, false);
    lastRun = clock.get();
    while (running) {
      double wait = InputRule.secondsUntilNextPoll(
          clock.get().toEpochMilli() / 1000d, pollSeconds);
      try {
        Thread.sleep((long) (wait * 1000));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      poll();
    }
  }

  /** One interval, closed at the current instant. Visible for testing. */
  public InputRule.Reading poll() {
    Instant now = clock.get();
    InputRule.Reading reading = InputRule.reading(lastRun, now, sensor.drain(), pollSeconds);
    lastRun = now;
    client.heartbeat(bucketId, reading.event(), reading.pulsetime(), true);
    return reading;
  }
}
