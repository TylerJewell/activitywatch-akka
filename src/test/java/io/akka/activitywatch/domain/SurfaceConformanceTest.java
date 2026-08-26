package io.akka.activitywatch.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import io.akka.activitywatch.domain.Corpus.Case;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The surfaces around the transforms, against the answers the original gave —
 * SPEC-001 §3 R85–R111.
 *
 * <p>The canonical queries are compared as text, byte for byte including the blank lines the
 * original's join leaves behind, because a client sends that text to a server and either
 * system has to parse it.
 */
class SurfaceConformanceTest {

  private static void replay(String family, Function<Case, Object> run) {
    List<String> failures = new ArrayList<>();
    List<Case> cases = Corpus.of(family);
    for (Case candidate : cases) {
      String expected = candidate.raised()
          ? "raised " + candidate.message()
          : Corpus.canonical(candidate.answer());
      String actual;
      try {
        actual = Corpus.canonical(Corpus.render(run.apply(candidate)));
      } catch (RuntimeException e) {
        actual = "raised " + e.getMessage();
      }
      if (!expected.equals(actual)) {
        failures.add(candidate.describe() + "\n  expected " + expected + "\n  was      " + actual);
      }
    }
    if (!failures.isEmpty()) {
      fail(failures.size() + " of " + cases.size() + " " + family
          + " cases differ from the original:\n" + String.join("\n", failures));
    }
  }

  // --------------------------------------------------------- the AFK rule

  @Test
  void theIdleRuleSendsTheSamePingsAsTheOriginal() {
    replay("afk_rule", c -> {
      double timeout = ((Number) c.args().get("timeout")).doubleValue();
      double pollTime = ((Number) c.args().get("poll_time")).doubleValue();
      Instant startedAt = Corpus.instant(c.args().get("started_at"));
      List<?> readings = (List<?>) c.args().get("readings");

      boolean idle = false;
      List<Object> pings = new ArrayList<>();
      for (int i = 0; i < readings.size(); i++) {
        Instant observedAt = startedAt.plus(
            Duration.ofNanos((long) (i * pollTime * 1_000_000_000L)));
        double idleSeconds = ((Number) readings.get(i)).doubleValue();
        IdleRule.Outcome outcome =
            IdleRule.observe(idle, observedAt, idleSeconds, timeout, pollTime);
        idle = outcome.idle();
        for (IdleRule.Ping ping : outcome.pings()) {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("timestamp", io.akka.activitywatch.api.Json.instant(ping.timestamp()));
          row.put("duration", io.akka.activitywatch.api.Json.seconds(ping.duration()));
          row.put("status", ping.status());
          row.put("pulsetime", ping.pulsetime());
          pings.add(row);
        }
      }
      return pings;
    });
  }

  // ------------------------------------------------------- window watcher

  @Test
  void thePulsetimeScalesWithThePollTheSameWay() {
    replay("compute_pulsetime", c ->
        WindowRule.computePulsetime(((Number) c.args().get("poll_time")).doubleValue()));
  }

  @Test
  void excludingTitlesMatchesTheOriginal() {
    replay("transform_window", c -> {
      List<Pattern> patterns = new ArrayList<>();
      for (String raw : Corpus.strings(c.args().get("exclude_titles"))) {
        patterns.add(WindowRule.compileTitlePattern(raw));
      }
      return WindowRule.transformWindow(window(c.args().get("window")),
          Boolean.TRUE.equals(c.args().get("exclude_title")), patterns, null, null);
    });
  }

  // ------------------------------------------------------ research filter

  @Test
  void theBrowserListIsTheOriginals() {
    replay("research_browser_apps", c -> ResearchFilter.browserApps());
  }

  @Test
  void recognisingABrowserMatchesTheOriginal() {
    replay("is_browser", c -> ResearchFilter.isBrowser(String.valueOf(c.args().get("app"))));
  }

  @Test
  void classifyingATitleMatchesTheOriginal() {
    replay("classify_title", c -> ResearchFilter.classifyTitle(
        String.valueOf(c.args().get("title")), strings(c.args().get("category_map")),
        String.valueOf(c.args().get("url"))));
  }

  @Test
  void classifyingAnApplicationMatchesTheOriginal() {
    replay("classify_app", c -> ResearchFilter.classifyApp(
        String.valueOf(c.args().get("app")), strings(c.args().get("app_category_map"))));
  }

  @Test
  void theResearchTransformMatchesTheOriginal() {
    replay("research_transform", c -> ResearchFilter.transform(
        window(c.args().get("window")),
        c.args().get("category_map") == null ? null : strings(c.args().get("category_map")),
        c.args().get("app_category_map") == null
            ? null : strings(c.args().get("app_category_map"))));
  }

  // ---------------------------------------------------- canonical queries

  @Test
  void theCanonicalQueryIsTheOriginalsTextExactly() {
    replay("canonicalEvents", c -> Queries.canonicalEvents(params(c.args().get("params"))));
  }

  @Test
  void theDashboardQueryIsTheOriginalsTextExactly() {
    replay("fullDesktopQuery", c -> Queries.fullDesktopQuery(params(c.args().get("params"))));
  }

  @Test
  void theBrowsersWithBucketsAreFoundInTheSameOrder() {
    replay("browsersWithBuckets", c -> {
      List<Object> out = new ArrayList<>();
      Queries.browsersWithBuckets(Corpus.strings(c.args().get("buckets")))
          .forEach((browser, bucket) -> out.add(List.of(browser, bucket)));
      return out;
    });
  }

  @Test
  void theBrowserTableIsTheOriginals() {
    replay("browser_appnames", c -> {
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<String, List<String>> entry : Queries.BROWSER_APPNAMES.entrySet()) {
        out.put(entry.getKey(), entry.getValue());
      }
      return out;
    });
  }

  @Test
  void theDefaultCategoriesAreTheOriginals() {
    replay("default_classes", c -> DefaultClasses.defaults());
  }

  // -------------------------------------------------------- configuration

  @Test
  void commentingOutTheDefaultsMatchesTheOriginal() {
    replay("comment_out_toml",
        c -> Toml.commentOutKeys(String.valueOf(c.args().get("text"))));
  }

  @Test
  void mergingConfigurationMatchesTheOriginal() {
    replay("merge_config", c -> Toml.merge(table(c.args().get("base")),
        table(c.args().get("over"))));
  }

  // ------------------------------------------------------- module manager

  @Test
  void theIgnoredModuleNamesAreTheOriginals() {
    replay("module_ignored", c -> ModuleRules.IGNORED);
  }

  @Test
  void strippingAFilenameMatchesTheOriginal() {
    replay("filename_to_name", c -> {
      Map<String, Object> out = new LinkedHashMap<>();
      for (String filename : List.of("aw-server.exe", "aw-server.bat", "aw-server.cmd",
          "aw-server", "aw-server.desktop")) {
        out.put(filename, ModuleRules.filenameToName(filename, true));
      }
      return out;
    });
  }

  @Test
  void theAutostartOrderMatchesTheOriginal() {
    replay("autostart", c -> ModuleRules.autostartOrder(
        Corpus.strings(c.args().get("requested")), Corpus.strings(c.args().get("discovered"))));
  }

  // --------------------------------------------------------------- helpers

  @SuppressWarnings("unchecked")
  private static Map<String, Object> window(Object raw) {
    return new LinkedHashMap<>((Map<String, Object>) raw);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> table(Object raw) {
    return new LinkedHashMap<>((Map<String, Object>) raw);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> strings(Object raw) {
    Map<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : ((Map<String, Object>) raw).entrySet()) {
      out.put(entry.getKey(), String.valueOf(entry.getValue()));
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static Queries.Params params(Object raw) {
    Map<String, Object> spec = (Map<String, Object>) raw;
    List<Object> classes = spec.get("classes") == null
        ? List.of() : new ArrayList<>((List<Object>) spec.get("classes"));
    if (spec.containsKey("bid_android")) {
      return Queries.Params.android(String.valueOf(spec.get("bid_android")), classes);
    }
    Queries.Params params = Queries.Params.desktop(
        String.valueOf(spec.get("bid_window")), String.valueOf(spec.get("bid_afk")), classes);
    if (spec.get("bid_browsers") != null) {
      params = params.withBrowsers(Corpus.strings(spec.get("bid_browsers")));
    }
    if (spec.get("filter_classes") != null) {
      params = params.withFilterClasses(new ArrayList<>((List<Object>) spec.get("filter_classes")));
    }
    if (spec.get("filter_afk") != null) {
      params = params.withFilterAfk(Boolean.TRUE.equals(spec.get("filter_afk")));
    }
    if (spec.get("always_active_pattern") != null) {
      params = params.withAlwaysActivePattern(String.valueOf(spec.get("always_active_pattern")));
    }
    if (Boolean.FALSE.equals(spec.get("include_audible"))) {
      params = new Queries.Params(params.bidWindow(), params.bidAfk(), params.android(),
          params.bidBrowsers(), params.classes(), params.filterClasses(), params.filterAfk(),
          false, params.alwaysActivePattern());
    }
    return params;
  }

  @Test
  void everyFamilyInTheCorpusIsCoveredBySomeTest() {
    List<String> covered = List.of(
        "heartbeat_merge", "heartbeat_reduce", "flood", "filter_period_intersect",
        "period_union", "union", "union_no_overlap", "merge_events_by_keys",
        "chunk_events_by_key", "sort_by_timestamp", "sort_by_duration", "limit_events",
        "sum_durations", "concat", "filter_keyvals", "filter_keyvals_regex",
        "simplify_string", "split_url_events", "categorize", "tag",
        "merge_subwatcher_fields", "query", "canonicalEvents", "fullDesktopQuery",
        "browsersWithBuckets", "browser_appnames", "default_classes", "afk_rule",
        "compute_pulsetime", "transform_window", "research_browser_apps", "is_browser",
        "classify_title", "classify_app", "research_transform", "comment_out_toml",
        "merge_config", "module_ignored", "filename_to_name", "autostart");
    List<String> uncovered = new ArrayList<>(Corpus.families());
    uncovered.removeAll(covered);
    assertEquals(List.of(), uncovered,
        "every family the oracle recorded is replayed by some test");
  }
}
