package io.akka.activitywatch.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * How the notification service turns a day's events into something to say —
 * SPEC-001 §3 R116–R130.
 *
 * <p>Everything here is a function of its arguments. What the notifier does with the answers —
 * when it asks, how often, and what it shows them on — is {@code application/NotifyService},
 * and this is what can be checked without a clock or a server.
 */
public final class NotifyRules {

  private NotifyRules() {}

  /** R117: the day the service reports on begins four hours after local midnight. */
  public static final Duration DAY_OFFSET = Duration.ofHours(4);

  /** The category holding the day's total, which is not a category anything was in. */
  public static final String ALL = "All";

  /**
   * How much of a category's path a summary keeps — SPEC-001 §3 R122, R123.
   *
   * <p>The three are not three views of one answer: an alert reads {@link #ALL_LEVELS} so
   * that a threshold on `Work` counts everything under it, an hourly summary reads
   * {@link #TOP_LEVEL} so it fits in four lines, and the productivity score reads
   * {@link #NONE} so that each leaf is scored once rather than once per ancestor.
   */
  public enum Aggregation {
    NONE, TOP_LEVEL, ALL_LEVELS
  }

  /**
   * R116: a duration as `1d 2h 3m`, dropping the parts that are zero.
   *
   * <p>Seconds appear only when nothing else does, so 90 seconds reads `1m` and 30 reads
   * `30s`. Each part is the remainder within the one above it, so 25 hours is `1d 1h`.
   */
  public static String toHms(Duration duration) {
    long days = duration.toDays();
    long hours = duration.toHours() % 24;
    long minutes = duration.toMinutes() % 60;
    long seconds = duration.getSeconds() % 60;
    List<String> parts = new ArrayList<>(3);
    if (days > 0) {
      parts.add(days + "d");
    }
    if (hours > 0) {
      parts.add(hours + "h");
    }
    if (minutes > 0) {
      parts.add(minutes + "m");
    }
    if (parts.isEmpty()) {
      parts.add(seconds + "s");
    }
    return String.join(" ", parts);
  }

  /** R117: the window a day's query covers, in order. */
  public static Instant dayStart(Instant when, ZoneId zone) {
    LocalDate day = when.atZone(zone).toLocalDate();
    return day.atStartOfDay(zone).toInstant().plus(DAY_OFFSET);
  }

  /**
   * R119: what a `$category` value is called.
   *
   * <p>A list is the category's path and is joined with ` > `; a string is the name itself;
   * an empty list and anything else are `Unknown`, which is a category name a caller can then
   * see in a summary.
   */
  public static String categoryNameOf(Object value) {
    if (value instanceof List<?> list) {
      List<String> parts = new ArrayList<>(list.size());
      for (Object item : list) {
        if (item instanceof String text) {
          parts.add(text);
        }
      }
      return parts.isEmpty() ? "Unknown" : String.join(" > ", parts);
    }
    if (value instanceof String text) {
      return text;
    }
    return "Unknown";
  }

  /** R122: everything before the first ` > `, with `All` left where it is. */
  public static Map<String, Double> aggregateTopLevel(Map<String, Double> categoryTime) {
    Map<String, Double> out = new TreeMap<>();
    for (Map.Entry<String, Double> entry : categoryTime.entrySet()) {
      if (ALL.equals(entry.getKey())) {
        out.put(ALL, entry.getValue());
        continue;
      }
      int separator = entry.getKey().indexOf(" > ");
      String top = separator < 0 ? entry.getKey() : entry.getKey().substring(0, separator);
      out.merge(top, entry.getValue(), Double::sum);
    }
    return out;
  }

  /** R123: the category itself and every prefix of its path, with `All` left where it is. */
  public static Map<String, Double> aggregateAllLevels(Map<String, Double> categoryTime) {
    Map<String, Double> out = new TreeMap<>();
    for (Map.Entry<String, Double> entry : categoryTime.entrySet()) {
      if (ALL.equals(entry.getKey())) {
        out.put(ALL, entry.getValue());
        continue;
      }
      out.merge(entry.getKey(), entry.getValue(), Double::sum);
      String[] parts = entry.getKey().split(" > ");
      for (int i = 1; i < parts.length; i++) {
        out.merge(String.join(" > ", java.util.Arrays.copyOfRange(parts, 0, i)),
            entry.getValue(), Double::sum);
      }
    }
    return out;
  }

  /** A category and how long was spent in it, already rendered. */
  public record Line(String category, String time) {}

  /**
   * R124: the categories worth showing, longest first.
   *
   * <p>The share is measured against `All` rather than against the sum, so a category that
   * overlaps another is judged against the day rather than against the total of the parts.
   *
   * <p>Two categories with the same time are ordered by name. The original reads its
   * categories out of a hash map and sorts only on the time, so which of two equal ones comes
   * first is whatever the map's iteration gave — a value it does not decide and a caller
   * cannot rely on. §4 OD-12.
   */
  public static List<Line> topCategories(Map<String, Double> categoryTime, double minPercent,
      int maxCount) {
    double total = categoryTime.getOrDefault(ALL, 0.0);
    if (total <= 0.0) {
      return List.of();
    }
    List<Map.Entry<String, Double>> kept = new ArrayList<>();
    for (Map.Entry<String, Double> entry : categoryTime.entrySet()) {
      if (!ALL.equals(entry.getKey()) && entry.getValue() > total * minPercent) {
        kept.add(entry);
      }
    }
    kept.sort(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue)
        .reversed().thenComparing(Map.Entry::getKey));
    List<Line> out = new ArrayList<>(Math.min(maxCount, kept.size()));
    for (Map.Entry<String, Double> entry : kept.subList(0, Math.min(maxCount, kept.size()))) {
      out.add(new Line(entry.getKey(), toHms(Duration.ofSeconds((long) (double) entry.getValue()))));
    }
    return List.copyOf(out);
  }

  /** R125: the icons, by the whole category name lower-cased. */
  private static final Map<String, String> ICONS = icons();

  private static Map<String, String> icons() {
    Map<String, String> out = new LinkedHashMap<>();
    out.put("work", "💼");
    for (String name : List.of("programming", "development", "coding")) {
      out.put(name, "💻");
    }
    for (String name : List.of("media", "entertainment")) {
      out.put(name, "📱");
    }
    for (String name : List.of("games", "gaming")) {
      out.put(name, "🎮");
    }
    for (String name : List.of("video", "youtube", "netflix")) {
      out.put(name, "📺");
    }
    for (String name : List.of("music", "spotify", "audio")) {
      out.put(name, "🎵");
    }
    for (String name : List.of("social", "twitter", "facebook", "instagram")) {
      out.put(name, "💬");
    }
    for (String name : List.of("communication", "email", "slack", "discord")) {
      out.put(name, "📧");
    }
    for (String name : List.of("browsing", "web")) {
      out.put(name, "🌐");
    }
    out.put("reading", "📖");
    out.put("writing", "✍️");
    for (String name : List.of("design", "graphics")) {
      out.put(name, "🎨");
    }
    for (String name : List.of("learning", "education")) {
      out.put(name, "📚");
    }
    return Map.copyOf(out);
  }

  /**
   * R125: the icon for a category.
   *
   * <p>Matched on the whole name, so `Work` has one and `Work &gt; Programming` does not —
   * the table is keyed by the name a top-level aggregation produces, and a path never equals
   * one of its parts.
   */
  public static String categoryIcon(String category) {
    return ICONS.getOrDefault(category.toLowerCase(java.util.Locale.ROOT), "📊");
  }

  public static String formatCategory(String category) {
    return categoryIcon(category) + " " + category;
  }

  /** R126, R127: the body of a summary, one line per category. */
  public static String summaryMessage(List<Line> lines) {
    List<String> rendered = new ArrayList<>(lines.size());
    for (Line line : lines) {
      rendered.add("- " + formatCategory(line.category()) + ": " + line.time());
    }
    return String.join("\n", rendered);
  }

  /**
   * R128: a category's score, inherited from its parent where it has none of its own.
   *
   * @param name the category's path
   * @param classes the `classes` setting, each entry `[name, rule, data?]`
   */
  @SuppressWarnings("unchecked")
  public static double categoryScore(List<String> name, List<Object> classes) {
    for (Object entry : classes) {
      if (!(entry instanceof Map<?, ?> map)) {
        continue;
      }
      Object candidate = map.get("name");
      if (!(candidate instanceof List<?> path) || !path.equals(name)) {
        continue;
      }
      Object data = map.get("data");
      if (data instanceof Map<?, ?> values) {
        Object score = values.get("score");
        if (score instanceof Number number) {
          return number.doubleValue();
        }
        if (score instanceof String text) {
          try {
            return Double.parseDouble(text);
          } catch (NumberFormatException ignored) {
            // A score that is not a number is a score the original also fails to read, and
            // it falls through to the parent rather than refusing the whole calculation.
          }
        }
      }
      break;
    }
    return name.size() > 1
        ? categoryScore(name.subList(0, name.size() - 1), classes)
        : 0.0;
  }

  /** @param score the day's total, @param productivePercent the share of it that scored above zero */
  public record Productivity(double score, double productivePercent) {}

  /**
   * R129: the day's productivity, or nothing.
   *
   * <p>Nothing when there are no classes to score against, and nothing when no time was
   * recorded — in both cases a number would be an answer about the absence rather than about
   * the day.
   */
  public static Productivity productivity(Map<String, Double> rawCategoryTime,
      List<Object> classes) {
    if (classes.isEmpty()) {
      return null;
    }
    double total = 0;
    double productive = 0;
    double score = 0;
    for (Map.Entry<String, Double> entry : rawCategoryTime.entrySet()) {
      if (ALL.equals(entry.getKey())) {
        continue;
      }
      double seconds = entry.getValue();
      total += seconds;
      double categoryScore = (seconds / 3600.0)
          * categoryScore(List.of(entry.getKey().split(" > ")), classes);
      score += categoryScore;
      if (categoryScore > 0.0) {
        productive += seconds;
      }
    }
    if (total == 0.0) {
      return null;
    }
    return new Productivity(score, (productive / total) * 100.0);
  }

  /** R130. */
  public static String productivityMessage(Productivity productivity) {
    return String.format(java.util.Locale.ROOT, "%+.1f (%.1f%% productive)",
        productivity.score(), productivity.productivePercent());
  }
}
