package io.akka.activitywatch.application;

import com.typesafe.config.Config;
import io.akka.activitywatch.client.ActivityWatchClient;
import io.akka.activitywatch.domain.Dirs;
import io.akka.activitywatch.watcher.AfkWatcher;
import io.akka.activitywatch.watcher.InputWatcher;
import io.akka.activitywatch.watcher.Sensors;
import io.akka.activitywatch.watcher.WindowWatcher;
import io.akka.activitywatch.watcher.WindowsSensors;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The three watchers, started and stopped as a set.
 *
 * <p>They are off unless the configuration asks for them, because a watcher reads what is on
 * somebody's screen and how long they have been away from it, and a service that starts doing
 * that on its own is not a service anybody should run.
 *
 * <p>Each runs in its own thread with the port's own client library in front of it, so what
 * they exercise is the same path an external watcher would take: a heartbeat over HTTP with a
 * pulse window, queued and merged on the way out.
 */
public final class Watchers {

  private static volatile Watchers instance;

  private final Config config;
  private final Map<String, Runnable> running = new ConcurrentHashMap<>();
  private final Map<String, Thread> threads = new ConcurrentHashMap<>();

  private Watchers(Config config) {
    this.config = config;
  }

  public static Watchers instance(Config config) {
    Watchers known = instance;
    if (known == null) {
      synchronized (Watchers.class) {
        if (instance == null) {
          instance = new Watchers(config);
        }
        known = instance;
      }
    }
    return known;
  }

  /** What is running, and what each one writes to. */
  public List<Map<String, Object>> statuses() {
    List<Map<String, Object>> out = new ArrayList<>();
    for (String name : List.of(AfkWatcher.CLIENT_NAME, WindowWatcher.CLIENT_NAME,
        InputWatcher.CLIENT_NAME)) {
      Map<String, Object> row = new LinkedHashMap<>();
      Thread thread = threads.get(name);
      row.put("name", name);
      row.put("running", thread != null && thread.isAlive());
      row.put("bucket", bucketOf(name));
      row.put("sensor", sensorAvailable(name) ? "available" : "unavailable on this platform");
      out.add(row);
    }
    return out;
  }

  public boolean start(String name) {
    if (threads.containsKey(name) && threads.get(name).isAlive()) {
      return true;
    }
    if (!sensorAvailable(name)) {
      throw new IllegalArgumentException(
          name + " has no sensor on " + System.getProperty("os.name"));
    }
    Runnable watcher = build(name);
    running.put(name, watcher);
    Thread thread = new Thread(watcher, name);
    thread.setDaemon(true);
    threads.put(name, thread);
    thread.start();
    return true;
  }

  public boolean stop(String name) {
    Runnable watcher = running.remove(name);
    Thread thread = threads.remove(name);
    if (watcher instanceof AfkWatcher afk) {
      afk.stop();
    } else if (watcher instanceof WindowWatcher window) {
      window.stop();
    } else if (watcher instanceof InputWatcher input) {
      input.stop();
    }
    if (thread != null) {
      thread.interrupt();
      return true;
    }
    return false;
  }

  /** Started at boot for whichever watchers the configuration turned on. */
  public void autostart() {
    for (String name : List.of(AfkWatcher.CLIENT_NAME, WindowWatcher.CLIENT_NAME,
        InputWatcher.CLIENT_NAME)) {
      if (config.getBoolean("activitywatch.watchers." + shortName(name) + ".enabled")
          && sensorAvailable(name)) {
        start(name);
      }
    }
  }

  private Runnable build(String name) {
    ActivityWatchClient client = new ActivityWatchClient(name, "localhost",
        config.getInt("akka.javasdk.dev-mode.http-port"),
        config.getBoolean("activitywatch.testing"), null, Dirs.dataDir(name));
    String section = "activitywatch.watchers." + shortName(name);
    return switch (name) {
      case AfkWatcher.CLIENT_NAME -> new AfkWatcher(client, WindowsSensors.idle(),
          config.getDouble(section + ".timeout"), config.getDouble(section + ".poll-time"));
      case WindowWatcher.CLIENT_NAME -> new WindowWatcher(client, WindowsSensors.window(),
          config.getDouble(section + ".poll-time"),
          config.getBoolean(section + ".exclude-title"), List.of(), null, null);
      case InputWatcher.CLIENT_NAME -> new InputWatcher(client, WindowsSensors.input(),
          config.getDouble(section + ".poll-time"));
      default -> throw new IllegalArgumentException("there is no watcher called " + name);
    };
  }

  private static boolean sensorAvailable(String name) {
    return WindowsSensors.available();
  }

  private static String bucketOf(String name) {
    try {
      return name + "_" + java.net.InetAddress.getLocalHost().getHostName();
    } catch (java.net.UnknownHostException e) {
      return name + "_localhost";
    }
  }

  private static String shortName(String name) {
    return switch (name) {
      case AfkWatcher.CLIENT_NAME -> "afk";
      case WindowWatcher.CLIENT_NAME -> "window";
      case InputWatcher.CLIENT_NAME -> "input";
      default -> throw new IllegalArgumentException("there is no watcher called " + name);
    };
  }

  /** Used by the tests, which build their own with scripted sensors. */
  public static Sensors.Idle scriptedIdle(double... readings) {
    int[] index = {0};
    return () -> readings[Math.min(index[0]++, readings.length - 1)];
  }
}
