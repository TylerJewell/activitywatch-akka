package io.akka.activitywatch.domain;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What the notification service was told to do — SPEC-001 §3 R113–R115.
 *
 * <p>A file of its own, `activitywatch/aw-notify/config.toml` under the configuration
 * directory, separate from the one the server and the watchers read. It is written out with
 * the defaults in it the first time the service runs, so the thing a user edits is a file
 * that already says what can be set.
 *
 * @param alerts the categories being watched, in the order they were declared
 * @param hourlyCheckins whether a summary is sent at the top of each hour
 * @param newDayGreetings whether the first activity of a new day is greeted
 * @param serverMonitoring whether the server going up or down is announced
 * @param productivityScore whether yesterday's score follows the greeting
 * @param httpPort where other modules may post their own notifications; 0 is off
 */
public record NotifyConfig(List<Alert> alerts, boolean hourlyCheckins, boolean newDayGreetings,
    boolean serverMonitoring, boolean productivityScore, int httpPort) {

  /**
   * One category being watched.
   *
   * @param category the category's full path, written with ` &gt; ` between its parts
   * @param label what to call it in a notification; the category itself when absent (R115)
   * @param thresholds the times that trigger, in the order they were written
   * @param positive whether reaching one is an achievement rather than a warning
   */
  public record Alert(String category, String label, List<Duration> thresholds,
      boolean positive) {

    public String labelOrCategory() {
      return label == null ? category : label;
    }
  }

  /** R114: what the file says when nobody has written one. */
  public static NotifyConfig defaults() {
    return new NotifyConfig(List.of(
        alert("All", "All", List.of(60L, 120L, 240L, 360L, 480L), false),
        alert("Media > Social Media", "🐦 Social Media", List.of(15L, 30L, 60L), false),
        alert("Media", "📺 Media", List.of(30L, 60L, 120L, 240L), false),
        alert("Work", "💼 Work", List.of(15L, 30L, 60L, 120L, 240L), true)),
        true, true, true, true, 0);
  }

  private static Alert alert(String category, String label, List<Long> minutes,
      boolean positive) {
    List<Duration> thresholds = new ArrayList<>(minutes.size());
    for (Long value : minutes) {
      thresholds.add(Duration.ofMinutes(value));
    }
    return new Alert(category, label, List.copyOf(thresholds), positive);
  }

  /** R113: a file that parses, read over the defaults for the keys it does not set. */
  @SuppressWarnings("unchecked")
  public static NotifyConfig read(String text) {
    Map<String, Object> parsed = Toml.parse(text);
    NotifyConfig defaults = defaults();
    List<Alert> alerts = defaults.alerts();
    Object declared = parsed.get("alerts");
    if (declared instanceof List<?> list) {
      List<Alert> read = new ArrayList<>(list.size());
      for (Object item : list) {
        if (item instanceof Map<?, ?> table) {
          read.add(alertOf((Map<String, Object>) table));
        }
      }
      alerts = List.copyOf(read);
    }
    return new NotifyConfig(alerts,
        flag(parsed, "hourly_checkins", defaults.hourlyCheckins()),
        flag(parsed, "new_day_greetings", defaults.newDayGreetings()),
        flag(parsed, "server_monitoring", defaults.serverMonitoring()),
        flag(parsed, "productivity_score", defaults.productivityScore()),
        (int) number(parsed, "http_port", defaults.httpPort()));
  }

  private static Alert alertOf(Map<String, Object> table) {
    List<Duration> thresholds = new ArrayList<>();
    if (table.get("thresholds_minutes") instanceof List<?> declared) {
      for (Object minutes : declared) {
        if (minutes instanceof Number number) {
          thresholds.add(Duration.ofMinutes(number.longValue()));
        }
      }
    }
    return new Alert(String.valueOf(table.getOrDefault("category", "All")),
        table.get("label") == null ? null : String.valueOf(table.get("label")),
        List.copyOf(thresholds),
        Boolean.TRUE.equals(table.get("positive")));
  }

  private static boolean flag(Map<String, Object> parsed, String key, boolean fallback) {
    Object value = parsed.get(key);
    return value instanceof Boolean bool ? bool : fallback;
  }

  private static long number(Map<String, Object> parsed, String key, long fallback) {
    Object value = parsed.get(key);
    return value instanceof Number number ? number.longValue() : fallback;
  }

  /**
   * R113: the defaults as a file.
   *
   * <p>The comments are here because this is what a user finds when they open the file for
   * the first time, and a configuration file that only lists values does not say what the
   * values are for. The original writes the same settings through a Rust serialiser whose
   * exact layout — key order, spacing, where the blank lines fall — is that crate's rather
   * than ActivityWatch's; §4 OD-13 records that the port writes its own and reads either.
   */
  public String toToml() {
    StringBuilder out = new StringBuilder();
    out.append("# ActivityWatch notification service configuration.\n")
        .append("#\n")
        .append("# Written with these defaults the first time the service runs. Edit it and\n")
        .append("# restart the service; it is read once, at start-up.\n\n")
        .append("# A summary at the top of each hour, when the machine is being used.\n")
        .append("hourly_checkins = ").append(hourlyCheckins).append("\n\n")
        .append("# A greeting the first time the machine is used on a new day.\n")
        .append("new_day_greetings = ").append(newDayGreetings).append("\n\n")
        .append("# Say so when the server goes down, and when it comes back.\n")
        .append("server_monitoring = ").append(serverMonitoring).append("\n\n")
        .append("# Yesterday's productivity score, five seconds after the greeting.\n")
        .append("productivity_score = ").append(productivityScore).append("\n\n")
        .append("# Where other modules may post their own notifications. 0 is off.\n")
        .append("http_port = ").append(httpPort).append("\n");
    for (Alert alert : alerts) {
      out.append("\n[[alerts]]\n")
          .append("category = ").append(quote(alert.category())).append("\n");
      if (alert.label() != null) {
        out.append("label = ").append(quote(alert.label())).append("\n");
      }
      List<String> minutes = new ArrayList<>(alert.thresholds().size());
      for (Duration threshold : alert.thresholds()) {
        minutes.add(String.valueOf(threshold.toMinutes()));
      }
      out.append("thresholds_minutes = [").append(String.join(", ", minutes)).append("]\n")
          .append("positive = ").append(alert.positive()).append("\n");
    }
    return out.toString();
  }

  private static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
