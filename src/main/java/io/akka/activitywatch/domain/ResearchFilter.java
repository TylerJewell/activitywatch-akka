package io.akka.activitywatch.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The privacy transform a study build applies before anything is recorded —
 * SPEC-001 §3 R96, and the two spellings of "excluded" in R96/R250 of the question log.
 *
 * <p>A browser window keeps its application name and loses its title, which is replaced by a
 * study category. Anything else loses its title outright, and loses its application name too
 * where a map of applications to categories is supplied — for a non-browser the application is
 * the identifying thing, so keeping it would defeat the transform.
 */
public final class ResearchFilter {

  /** R96: matched case-insensitively after stripping. */
  public static final Set<String> BROWSER_APPS = Set.of(
      "chrome", "google chrome", "google chrome canary", "google-chrome",
      "google-chrome-beta", "google-chrome-unstable", "chromium", "chromium-browser",
      "brave browser", "brave", "brave-browser", "firefox", "firefox developer edition",
      "firefox-esr", "safari", "edge", "microsoft edge", "microsoft-edge",
      "microsoft-edge-beta", "microsoft-edge-dev", "opera", "chrome.exe", "brave.exe",
      "firefox.exe", "msedge.exe", "opera.exe");

  /** An unmatched browser title. Lowercase, and deliberately not the other one. */
  public static final String EXCLUDED_TITLE = "excluded";

  /** An unmapped non-browser application. Capitalised, matching the classifier it came from. */
  public static final String EXCLUDED_APP = "Excluded";

  private ResearchFilter() {}

  public static boolean isBrowser(String app) {
    return BROWSER_APPS.contains(app.strip().toLowerCase(Locale.ROOT));
  }

  /** The URL is tried before the title, because a page title changes while it loads. */
  public static String classifyTitle(String title, Map<String, String> categoryMap, String url) {
    if (url != null && !url.isEmpty()) {
      String lower = url.toLowerCase(Locale.ROOT);
      for (Map.Entry<String, String> entry : categoryMap.entrySet()) {
        if (lower.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
          return entry.getValue();
        }
      }
    }
    String lower = title == null ? "" : title.toLowerCase(Locale.ROOT);
    for (Map.Entry<String, String> entry : categoryMap.entrySet()) {
      if (lower.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
        return entry.getValue();
      }
    }
    return EXCLUDED_TITLE;
  }

  public static String classifyApp(String app, Map<String, String> appCategoryMap) {
    String lower = app.strip().toLowerCase(Locale.ROOT);
    for (Map.Entry<String, String> entry : appCategoryMap.entrySet()) {
      if (entry.getKey().strip().toLowerCase(Locale.ROOT).equals(lower)) {
        return entry.getValue();
      }
    }
    return EXCLUDED_APP;
  }

  /**
   * @param categoryMap null when research mode is off, in which case nothing happens
   * @param appCategoryMap null or empty falls back to keeping the application name
   */
  public static Map<String, Object> transform(Map<String, Object> window,
      Map<String, String> categoryMap, Map<String, String> appCategoryMap) {
    if (categoryMap == null) {
      return window;
    }
    String app = String.valueOf(window.getOrDefault("app", ""));
    Map<String, Object> result = new LinkedHashMap<>();

    if (isBrowser(app)) {
      String title = String.valueOf(window.getOrDefault("title", ""));
      Object url = window.get("url");
      result.put("app", app);
      result.put("title",
          classifyTitle(title, categoryMap, url == null ? "" : String.valueOf(url)));
    } else if (appCategoryMap != null && !appCategoryMap.isEmpty()) {
      result.put("app", classifyApp(app, appCategoryMap));
    } else {
      result.put("app", app);
    }

    // The incognito flag is metadata about the window, not about what was on it.
    if (window.containsKey("incognito")) {
      result.put("incognito", window.get("incognito"));
    }
    return result;
  }

  /** The list, sorted, for anything that wants to show what counts as a browser. */
  public static List<String> browserApps() {
    return BROWSER_APPS.stream().sorted().toList();
  }
}
