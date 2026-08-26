package io.akka.activitywatch.application;

import com.typesafe.config.Config;
import io.akka.activitywatch.domain.NotifyConfig;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * The notification service, run inside the server — SPEC-001 §3 R135–R142.
 *
 * <p>The original runs it as a separate program that the tray starts. The port already runs
 * the watchers inside the service rather than as processes beside it, for the same reason:
 * a module whose whole job is to talk to this server over HTTP does not need a process of its
 * own to prove it, and the module manager can still start the real thing where somebody has
 * one installed.
 *
 * <p>Off unless it is asked for. It reads what a person has been doing all day and says so
 * out loud, which is not something a service should begin doing because it was started.
 */
public final class NotifyDaemon {

  private static volatile NotifyDaemon instance;

  private final Config config;
  private final ConcurrentLinkedDeque<Notifier.Notification> shown =
      new ConcurrentLinkedDeque<>();
  private volatile Thread thread;
  private volatile NotifyService service;

  /** How many of the most recent notifications are kept for `GET /api/0/notify`. */
  public static final int REMEMBERED = 50;

  private NotifyDaemon(Config config) {
    this.config = config;
  }

  public static NotifyDaemon instance(Config config) {
    NotifyDaemon known = instance;
    if (known == null) {
      synchronized (NotifyDaemon.class) {
        if (instance == null) {
          instance = new NotifyDaemon(config);
        }
        known = instance;
      }
    }
    return known;
  }

  public boolean running() {
    Thread current = thread;
    return current != null && current.isAlive();
  }

  /** R142: where a notification posted by another module goes. */
  public Notifier notifier() {
    NotifyService current = service;
    return current == null ? null : current.notifier();
  }

  /** What it has said, most recent first. */
  public List<Map<String, Object>> recent() {
    List<Map<String, Object>> out = new java.util.ArrayList<>();
    for (Notifier.Notification notification : shown) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("title", notification.displayTitle());
      row.put("message", notification.message());
      row.put("sender", notification.sender());
      out.add(row);
    }
    return out;
  }

  /**
   * @param api the server this runs inside, asked directly rather than over its own port —
   *     which the runtime may have chosen and this would have to guess
   */
  public synchronized boolean start(ServerApi api) {
    if (running()) {
      return true;
    }
    service = new NotifyService(
        NotifySource.of(api, io.akka.activitywatch.api.ServiceWiring.hostname(config)),
        settings(), new Notifier(this::remember), ZoneId.systemDefault(), Instant::now);
    Thread started = new Thread(this::loop, "aw-notify");
    started.setDaemon(true);
    thread = started;
    started.start();
    return true;
  }

  public synchronized boolean stop() {
    Thread current = thread;
    if (current == null) {
      return false;
    }
    current.interrupt();
    thread = null;
    return true;
  }

  private void remember(Notifier.Notification notification) {
    shown.addFirst(notification);
    while (shown.size() > REMEMBERED) {
      shown.removeLast();
    }
  }

  private void loop() {
    service.run(() -> Thread.currentThread().isInterrupted(), NotifyDaemon::sleep);
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(Math.max(0, duration.toMillis()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** The service's own configuration file, read the way the command-line tool reads it. */
  public NotifyConfig settings() {
    return io.akka.activitywatch.cli.AwNotifyCli.loadConfig(null);
  }

}
