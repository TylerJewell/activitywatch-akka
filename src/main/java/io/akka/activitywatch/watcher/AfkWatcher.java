package io.akka.activitywatch.watcher;

import io.akka.activitywatch.client.ActivityWatchClient;
import io.akka.activitywatch.domain.IdleRule;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * The watcher that says whether anyone is there — SPEC-001 §3 R88–R93, R99.
 *
 * <p>It holds one bit — whether the machine counts as untouched — and everything else it
 * needs arrives with each reading. The rule that turns a reading into heartbeats is
 * {@link IdleRule}, which knows nothing about clients or clocks; this is the loop around it.
 */
public class AfkWatcher implements Runnable {

  public static final String CLIENT_NAME = "aw-watcher-afk";
  public static final String EVENT_TYPE = "afkstatus";

  private final ActivityWatchClient client;
  private final Sensors.Idle sensor;
  private final Supplier<Instant> clock;
  private final double timeoutSeconds;
  private final double pollSeconds;
  private final String bucketId;
  private volatile boolean running = true;
  private boolean idle;

  public AfkWatcher(ActivityWatchClient client, Sensors.Idle sensor, double timeoutSeconds,
      double pollSeconds) {
    this(client, sensor, Instant::now, timeoutSeconds, pollSeconds);
  }

  public AfkWatcher(ActivityWatchClient client, Sensors.Idle sensor, Supplier<Instant> clock,
      double timeoutSeconds, double pollSeconds) {
    if (timeoutSeconds < pollSeconds) {
      // R93. A timeout shorter than the poll can never be reached in the state the watcher
      // is in when it is asked, so a configuration that says so is refused rather than
      // silently never reporting anyone as away.
      throw new IllegalArgumentException(
          "the timeout must not be shorter than the poll interval");
    }
    this.client = client;
    this.sensor = sensor;
    this.clock = clock;
    this.timeoutSeconds = timeoutSeconds;
    this.pollSeconds = pollSeconds;
    this.bucketId = CLIENT_NAME + "_" + client.hostname();
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
    client.createBucket(bucketId, EVENT_TYPE, true);
    client.connect();
    while (running) {
      observe(sensor.secondsSinceLastInput());
      try {
        Thread.sleep((long) (pollSeconds * 1000));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  /** One reading, turned into the heartbeats it produces and sent. Visible for testing. */
  public void observe(double idleSeconds) {
    IdleRule.Outcome outcome =
        IdleRule.observe(idle, clock.get(), idleSeconds, timeoutSeconds, pollSeconds);
    idle = outcome.idle();
    for (IdleRule.Ping ping : outcome.pings()) {
      client.heartbeat(bucketId, ping.asEvent(), ping.pulsetime(), true);
    }
  }

  public boolean away() {
    return idle;
  }
}
