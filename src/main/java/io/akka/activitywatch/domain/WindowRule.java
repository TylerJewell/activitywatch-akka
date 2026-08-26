package io.akka.activitywatch.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * What the window watcher does with a reading before it sends it —
 * SPEC-001 §3 R94, R95, R97.
 */
public final class WindowRule {

  public static final String EXCLUDED = "excluded";

  private WindowRule() {}

  /**
   * R94: the window a heartbeat is allowed to merge across, scaled with the poll interval.
   *
   * <p>Below a two-second poll the margin is a flat second; above it, half the interval, which
   * is what stops scheduling jitter from cutting a run in two on a slow poll.
   */
  public static double computePulsetime(double pollTimeSeconds) {
    return Math.max(pollTimeSeconds * 1.5, pollTimeSeconds + 1.0);
  }

  /** R95: the patterns are case-insensitive, and an unparseable one stops the watcher. */
  public static Pattern compileTitlePattern(String pattern) {
    try {
      return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
    } catch (PatternSyntaxException e) {
      throw new IllegalArgumentException("Invalid regex pattern: " + pattern, e);
    }
  }

  /**
   * R95, R96: research mode replaces both title rules; otherwise a matching pattern or the
   * blanket flag replaces the title with the literal word.
   */
  public static Map<String, Object> transformWindow(Map<String, Object> window,
      boolean excludeTitle, List<Pattern> excludeTitles,
      Map<String, String> researchCategoryMap, Map<String, String> researchAppCategoryMap) {
    if (researchCategoryMap != null) {
      return ResearchFilter.transform(window, researchCategoryMap, researchAppCategoryMap);
    }

    Map<String, Object> out = new LinkedHashMap<>(window);
    String title = String.valueOf(out.getOrDefault("title", ""));
    if (excludeTitles != null) {
      for (Pattern pattern : excludeTitles) {
        if (pattern.matcher(title).find()) {
          out.put("title", EXCLUDED);
          break;
        }
      }
    }
    if (excludeTitle) {
      out.put("title", EXCLUDED);
    }
    return out;
  }
}
