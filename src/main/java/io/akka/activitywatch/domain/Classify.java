package io.akka.activitywatch.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Deciding what an event was — SPEC-001 §3 R29, R30.
 *
 * <p>A rule is a regular expression, optionally restricted to named keys and optionally
 * case-insensitive. A rule with no expression, or with the empty string for one, matches
 * nothing: the original checks for that explicitly so an empty pattern does not match
 * everything.
 */
public final class Classify {

  private Classify() {}

  /** A single classification rule. */
  public record Rule(Pattern regex, List<String> selectKeys, boolean ignoreCase) {

    public static Rule of(Map<String, Object> spec) {
      Object rawKeys = spec.get("select_keys");
      List<String> keys = null;
      if (rawKeys instanceof List<?> list) {
        List<String> parsed = new ArrayList<>();
        for (Object key : list) {
          parsed.add(String.valueOf(key));
        }
        keys = List.copyOf(parsed);
      }
      boolean ignoreCase = Boolean.TRUE.equals(spec.get("ignore_case"));
      Object rawRegex = spec.get("regex");
      Pattern pattern = null;
      if (rawRegex instanceof String text && !text.isEmpty()) {
        pattern = Pattern.compile(text,
            (ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0));
      }
      return new Rule(pattern, keys, ignoreCase);
    }

    public boolean matches(Event event) {
      if (regex == null) {
        return false;
      }
      List<Object> values = new ArrayList<>();
      if (selectKeys != null) {
        for (String key : selectKeys) {
          values.add(event.data().get(key));
        }
      } else {
        values.addAll(event.data().values());
      }
      for (Object value : values) {
        if (value instanceof String text && regex.matcher(text).find()) {
          return true;
        }
      }
      return false;
    }
  }

  /** A category and the rule that selects it. */
  public record CategoryRule(List<String> category, Rule rule) {}

  /**
   * A tag and the rule that selects it.
   *
   * <p>The tag is whatever the caller named the class -- a string from the command line, a
   * list of category names from the query language -- and it reaches `$tags` unchanged.
   */
  public record TagRule(Object tag, Rule rule) {}

  /** R29. */
  public static List<Event> categorize(List<Event> events, List<CategoryRule> classes) {
    List<Event> out = new ArrayList<>(events.size());
    for (Event event : events) {
      List<List<String>> hits = new ArrayList<>();
      for (CategoryRule candidate : classes) {
        if (candidate.rule().matches(event)) {
          hits.add(candidate.category());
        }
      }
      Map<String, Object> data = new LinkedHashMap<>(event.data());
      data.put("$category", pickCategory(hits));
      out.add(event.withData(data));
    }
    return List.copyOf(out);
  }

  /** R30. */
  public static List<Event> tag(List<Event> events, List<TagRule> classes) {
    List<Event> out = new ArrayList<>(events.size());
    for (Event event : events) {
      List<Object> hits = new ArrayList<>();
      for (TagRule candidate : classes) {
        if (candidate.rule().matches(event)) {
          hits.add(candidate.tag());
        }
      }
      Map<String, Object> data = new LinkedHashMap<>(event.data());
      data.put("$tags", List.copyOf(hits));
      out.add(event.withData(data));
    }
    return List.copyOf(out);
  }

  /**
   * The deepest category, and among equal depths the last one listed.
   *
   * <p>The fold starts from {@code ["Uncategorized"]} and replaces the accumulator whenever a
   * candidate is at least as deep, which is what makes a matching depth-one rule beat it.
   */
  private static List<String> pickCategory(List<List<String>> hits) {
    List<String> best = List.of("Uncategorized");
    for (List<String> hit : hits) {
      if (hit.size() >= best.size()) {
        best = hit;
      }
    }
    return best;
  }
}
