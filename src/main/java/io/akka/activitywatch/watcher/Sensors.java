package io.akka.activitywatch.watcher;

import io.akka.activitywatch.domain.InputRule;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the watchers ask the operating system, and nothing else.
 *
 * <p>These three interfaces are the line between the port and the machine. Everything above
 * them is a rule this port reproduces; everything below is a call the original also makes to
 * the same operating system, and the original has a different implementation of each one per
 * platform in exactly the same way.
 *
 * <p>Splitting them out is also what lets the rules be checked: a test scripts a sensor and
 * the watcher under it is the real one.
 */
public final class Sensors {

  private Sensors() {}

  /** How long the machine has gone untouched. */
  public interface Idle {
    double secondsSinceLastInput();
  }

  /** What is in front, or empty when nothing can be read — R97. */
  public interface Window {
    Map<String, Object> currentWindow();
  }

  /** How much was typed, clicked and moved since the last time it was asked. */
  public interface Input {
    InputRule.Counts drain();
  }

  /** A sensor for a platform this build has no implementation for. */
  public static Idle noIdle() {
    return () -> {
      throw new UnsupportedOperationException(
          "no idle sensor on " + System.getProperty("os.name"));
    };
  }

  public static Window noWindow() {
    return Map::of;
  }

  public static Input noInput() {
    return InputRule.Counts::none;
  }

  /** A window reading with the two keys the original's watchers always send. */
  public static Map<String, Object> window(String app, String title) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("app", app == null ? "unknown" : app);
    out.put("title", title == null ? "unknown" : title);
    return out;
  }
}
