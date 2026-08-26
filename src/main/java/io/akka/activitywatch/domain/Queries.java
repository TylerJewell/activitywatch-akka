package io.akka.activitywatch.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The queries a client sends when it wants "what did I spend today on" —
 * SPEC-001 §3 R85–R87.
 *
 * <p>The text is generated rather than written out because it depends on which buckets exist,
 * and it is reproduced to the byte — including the blank lines the original's join leaves
 * behind and the ten-space indentation inside the browser section — because a caller can send
 * it to either system and a test compares the two answers.
 *
 * <p>Everything is assigned to a variable before being used again. That is not a style
 * choice: an argument that follows a nested call in this language is silently dropped
 * (SPEC-001 §3 R72), and going through a variable is what avoids it.
 */
public final class Queries {

  public static final int DEFAULT_LIMIT = 100;

  /** R86: the browsers the canonical query knows, in the order it tries them. */
  public static final Map<String, List<String>> BROWSER_APPNAMES;

  static {
    Map<String, List<String>> browsers = new LinkedHashMap<>();
    browsers.put("chrome", List.of(
        "Google Chrome", "Google-chrome", "chrome.exe", "google-chrome-stable",
        "Chromium", "Chromium-browser", "Chromium-browser-chromium", "chromium.exe",
        "Google-chrome-beta", "Google-chrome-unstable", "Brave-browser"));
    browsers.put("firefox", List.of(
        "Firefox", "Firefox.exe", "firefox", "firefox.exe", "Firefox Developer Edition",
        "firefoxdeveloperedition", "Firefox-esr", "Firefox Beta", "Nightly",
        "org.mozilla.firefox"));
    browsers.put("opera", List.of("opera.exe", "Opera"));
    browsers.put("brave", List.of("brave.exe"));
    browsers.put("edge", List.of("msedge.exe", "Microsoft Edge"));
    browsers.put("vivaldi", List.of("Vivaldi-stable", "Vivaldi-snapshot", "vivaldi.exe"));
    // Kept in order: the order the table declares its browsers is the order
    // `browsersWithBuckets` walks them, and therefore the order the browser sections of a
    // canonical query come out in.
    BROWSER_APPNAMES = java.util.Collections.unmodifiableMap(browsers);
  }

  private Queries() {}

  /**
   * What a caller asks for.
   *
   * @param bidWindow the window bucket, or the android bucket when {@code android} is set
   * @param bidAfk the idle bucket, ignored for android
   * @param android whether this is an android query, which merges by app and skips the idle
   *     filtering entirely
   */
  public record Params(String bidWindow, String bidAfk, boolean android,
      List<String> bidBrowsers, List<Object> classes, List<Object> filterClasses,
      boolean filterAfk, boolean includeAudible, String alwaysActivePattern) {

    public static Params desktop(String bidWindow, String bidAfk, List<Object> classes) {
      return new Params(bidWindow, bidAfk, false, List.of(), classes, List.of(), true, true,
          null);
    }

    public static Params android(String bidAndroid, List<Object> classes) {
      return new Params(bidAndroid, null, true, List.of(), classes, List.of(), true, true, null);
    }

    public Params withBrowsers(List<String> buckets) {
      return new Params(bidWindow, bidAfk, android, buckets, classes, filterClasses, filterAfk,
          includeAudible, alwaysActivePattern);
    }

    public Params withFilterClasses(List<Object> filter) {
      return new Params(bidWindow, bidAfk, android, bidBrowsers, classes, filter, filterAfk,
          includeAudible, alwaysActivePattern);
    }

    public Params withFilterAfk(boolean filter) {
      return new Params(bidWindow, bidAfk, android, bidBrowsers, classes, filterClasses, filter,
          includeAudible, alwaysActivePattern);
    }

    public Params withAlwaysActivePattern(String pattern) {
      return new Params(bidWindow, bidAfk, android, bidBrowsers, classes, filterClasses,
          filterAfk, includeAudible, pattern);
    }
  }

  /** R85. */
  public static String canonicalEvents(Params params) {
    String classesStr = PyJson.dumps(params.classes()).replace("\\\\", "\\");
    String catFilterStr = PyJson.dumps(params.filterClasses());

    List<String> parts = new ArrayList<>();
    parts.add("events = flood(query_bucket(find_bucket(\"" + params.bidWindow() + "\")));");
    parts.add(params.android() ? "events = merge_events_by_keys(events, [\"app\"]);" : "");

    if (params.android()) {
      parts.add("");
    } else {
      StringBuilder afk = new StringBuilder();
      afk.append("\n            not_afk = flood(query_bucket(find_bucket(\"")
          .append(params.bidAfk()).append("\")));\n")
          .append("            not_afk = filter_keyvals(not_afk, \"status\", [\"not-afk\"]);");
      if (params.alwaysActivePattern() != null) {
        String pattern = params.alwaysActivePattern().replace("\"", "\\\"");
        afk.append("\n            not_treat_as_afk = filter_keyvals_regex(events, \"app\", \"")
            .append(pattern).append("\");\n")
            .append("            not_afk = period_union(not_afk, not_treat_as_afk);\n")
            .append("            not_treat_as_afk = filter_keyvals_regex(events, \"title\", \"")
            .append(pattern).append("\");\n")
            .append("            not_afk = period_union(not_afk, not_treat_as_afk);");
      }
      parts.add(afk.toString());
    }

    if (params.bidBrowsers() != null && !params.bidBrowsers().isEmpty()) {
      String browser = params.android() ? "" : browserEvents(params);
      String audible = params.includeAudible()
          ? "\n            audible_events = filter_keyvals(browser_events, \"audible\", [true]);\n"
              + "            not_afk = period_union(not_afk, audible_events);\n            "
          : "";
      parts.add(browser + audible);
    } else {
      parts.add("");
    }

    parts.add(!params.android() && params.filterAfk()
        ? "events = filter_period_intersect(events, not_afk);" : "");
    parts.add(params.classes() != null && !params.classes().isEmpty()
        ? "events = categorize(events, " + classesStr + ");" : "");
    parts.add(params.filterClasses() != null && !params.filterClasses().isEmpty()
        ? "events = filter_keyvals(events, \"$category\", " + catFilterStr + ");" : "");

    return String.join("\n", parts);
  }

  /** R86: one block per browser whose bucket was found. */
  public static String browserEvents(Params params) {
    StringBuilder code = new StringBuilder("browser_events = [];");
    for (Map.Entry<String, String> found : browsersWithBuckets(params.bidBrowsers()).entrySet()) {
      String name = found.getKey();
      String bucket = found.getValue();
      String appnames = PyJson.dumps(BROWSER_APPNAMES.get(name));
      code.append("\n          events_").append(name)
          .append(" = flood(query_bucket(\"").append(bucket).append("\"));\n")
          .append("          window_").append(name)
          .append(" = filter_keyvals(events, \"app\", ").append(appnames).append(");\n")
          .append("          events_").append(name).append(" = filter_period_intersect(events_")
          .append(name).append(", window_").append(name).append(");\n")
          .append("          events_").append(name).append(" = split_url_events(events_")
          .append(name).append(");\n")
          .append("          browser_events = concat(browser_events, events_")
          .append(name).append(");\n")
          .append("          browser_events = sort_by_timestamp(browser_events);\n        ");
    }
    return code.toString();
  }

  /** R86: only the browsers with a matching bucket, in the table's fixed order. */
  public static Map<String, String> browsersWithBuckets(List<String> browserBuckets) {
    Map<String, String> found = new LinkedHashMap<>();
    for (String browser : BROWSER_APPNAMES.keySet()) {
      for (String bucket : browserBuckets) {
        if (bucket.contains(browser)) {
          found.put(browser, bucket);
          break;
        }
      }
    }
    return found;
  }

  /** R85: the whole dashboard query, whose answer is the shape the web interface reads. */
  public static String fullDesktopQuery(Params params) {
    Params escaped = new Params(
        escapeDoubleQuote(params.bidWindow()),
        params.bidAfk() == null ? null : escapeDoubleQuote(params.bidAfk()),
        params.android(),
        params.bidBrowsers().stream().map(Queries::escapeDoubleQuote).toList(),
        params.classes(), params.filterClasses(), params.filterAfk(), params.includeAudible(),
        params.alwaysActivePattern());

    StringBuilder query = new StringBuilder();
    query.append("\n    ").append(canonicalEvents(escaped)).append("\n")
        .append("    title_events = sort_by_duration(merge_events_by_keys(events,"
            + " [\"app\", \"title\"]));\n")
        .append("    app_events   = sort_by_duration(merge_events_by_keys(title_events,"
            + " [\"app\"]));\n")
        .append("    cat_events   = sort_by_duration(merge_events_by_keys(events,"
            + " [\"$category\"]));\n")
        .append("    app_events  = limit_events(app_events, ").append(DEFAULT_LIMIT).append(");\n")
        .append("    title_events  = limit_events(title_events, ").append(DEFAULT_LIMIT)
        .append(");\n")
        .append("    duration = sum_durations(events);\n    ");

    if (!escaped.bidBrowsers().isEmpty()) {
      query.append("\n        browser_events = split_url_events(browser_events);\n")
          .append("        browser_urls = merge_events_by_keys(browser_events, [\"url\"]);\n")
          .append("        browser_urls = sort_by_duration(browser_urls);\n")
          .append("        browser_urls = limit_events(browser_urls, ").append(DEFAULT_LIMIT)
          .append(");\n")
          .append("        browser_domains = merge_events_by_keys(browser_events,"
              + " [\"$domain\"]);\n")
          .append("        browser_domains = sort_by_duration(browser_domains);\n")
          .append("        browser_domains = limit_events(browser_domains, ")
          .append(DEFAULT_LIMIT).append(");\n")
          .append("        browser_duration = sum_durations(browser_events);\n        ");
    } else {
      query.append("\n        browser_events = [];\n")
          .append("        browser_urls = [];\n")
          .append("        browser_domains = [];\n")
          .append("        browser_duration = 0;\n        ");
    }

    query.append("\n        RETURN = {\n")
        .append("            \"events\": events,\n")
        .append("            \"window\": {\n")
        .append("                \"app_events\": app_events,\n")
        .append("                \"title_events\": title_events,\n")
        .append("                \"cat_events\": cat_events,\n")
        .append("                \"active_events\": not_afk,\n")
        .append("                \"duration\": duration\n")
        .append("            },\n")
        .append("            \"browser\": {\n")
        .append("                \"domains\": browser_domains,\n")
        .append("                \"urls\": browser_urls,\n")
        .append("                \"duration\": browser_duration\n")
        .append("            }\n")
        .append("        };\n    ");
    return query.toString();
  }

  public static String escapeDoubleQuote(String value) {
    return value.replace("\"", "\\\"");
  }

  /** Each statement on its own line, blank lines dropped — the form the CLI logs. */
  public static String prettyQuery(String query) {
    List<String> lines = new ArrayList<>();
    for (String line : query.split("\n", -1)) {
      if (!line.strip().isEmpty()) {
        lines.add(line.strip());
      }
    }
    return String.join("\n", lines);
  }

  /** The form the HTTP API wants: one statement per element, each keeping its semicolon. */
  public static List<String> querystrToArray(String query) {
    List<String> out = new ArrayList<>();
    for (String part : query.split(";", -1)) {
      if (!part.isEmpty()) {
        out.add(part + ";");
      }
    }
    return out;
  }
}
