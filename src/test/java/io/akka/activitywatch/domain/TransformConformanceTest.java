package io.akka.activitywatch.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.akka.activitywatch.domain.Corpus.Case;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Every transform, against the answers the original gave — SPEC-001 §3 R4–R31.
 *
 * <p>One test per family rather than one per case: a family is a rule, and a failure names the
 * case inside it. Each test asserts the number of cases it covered as well, so a corpus that
 * silently loses a family fails rather than passing on nothing.
 */
class TransformConformanceTest {

  /** Runs every case of a family and reports all the mismatches, not just the first. */
  private static void replay(String family, Function<Case, Object> run) {
    List<String> failures = new ArrayList<>();
    for (Case candidate : Corpus.of(family)) {
      String expected;
      String actual;
      try {
        actual = Corpus.canonical(Corpus.render(run.apply(candidate)));
      } catch (RuntimeException e) {
        actual = "raised " + e.getClass().getSimpleName() + ": " + e.getMessage();
      }
      if (candidate.raised()) {
        expected = "raised " + candidate.error() + ": " + candidate.message();
        if (!actual.startsWith("raised ")) {
          failures.add(candidate.describe() + "\n  the original raised " + candidate.error()
              + "\n  this answered      " + actual);
          continue;
        }
        // The exception's own class name is what a caller of the query API sees; for a
        // transform only the fact of the failure and its message are visible.
        if (!actual.contains(String.valueOf(candidate.message()))) {
          failures.add(candidate.describe() + "\n  expected " + expected + "\n  was      "
              + actual);
        }
        continue;
      }
      expected = Corpus.canonical(candidate.answer());
      if (!expected.equals(actual)) {
        failures.add(candidate.describe() + "\n  expected " + expected + "\n  was      " + actual);
      }
    }
    if (!failures.isEmpty()) {
      fail(failures.size() + " of " + Corpus.of(family).size() + " " + family
          + " cases differ from the original:\n" + String.join("\n", failures));
    }
  }

  @Test
  void mergingAHeartbeatMatchesTheOriginal() {
    replay("heartbeat_merge", c -> {
      Event last = Corpus.event(cast(c.args().get("last")));
      Event heartbeat = Corpus.event(cast(c.args().get("heartbeat")));
      double pulsetime = ((Number) c.args().get("pulsetime")).doubleValue();
      Optional<Event> merged = Heartbeats.merge(last, heartbeat, pulsetime);
      return merged.orElse(null);
    });
  }

  @Test
  void reducingARunOfHeartbeatsMatchesTheOriginal() {
    replay("heartbeat_reduce", c -> Heartbeats.reduce(
        Corpus.events(c.args().get("events")),
        ((Number) c.args().get("pulsetime")).doubleValue()));
  }

  @Test
  void floodingMatchesTheOriginal() {
    replay("flood", c -> Flood.flood(
        Corpus.events(c.args().get("events")),
        ((Number) c.args().get("pulsetime")).doubleValue()));
  }

  @Test
  void clippingToAFilterMatchesTheOriginal() {
    replay("filter_period_intersect", c -> Periods.filterPeriodIntersect(
        Corpus.events(c.args().get("events")), Corpus.events(c.args().get("filters"))));
  }

  @Test
  void unioningPeriodsMatchesTheOriginal() {
    replay("period_union", c -> Periods.periodUnion(
        Corpus.events(c.args().get("events1")), Corpus.events(c.args().get("events2"))));
  }

  @Test
  void unioningEventsMatchesTheOriginal() {
    replay("union", c -> Periods.union(
        Corpus.events(c.args().get("events1")), Corpus.events(c.args().get("events2"))));
  }

  @Test
  void unioningWithoutOverlapMatchesTheOriginal() {
    replay("union_no_overlap", c -> UnionNoOverlap.unionNoOverlap(
        Corpus.events(c.args().get("events1")), Corpus.events(c.args().get("events2"))));
  }

  @Test
  void groupingByKeysMatchesTheOriginal() {
    replay("merge_events_by_keys", c -> Aggregations.mergeEventsByKeys(
        Corpus.events(c.args().get("events")), Corpus.strings(c.args().get("keys"))));
  }

  @Test
  void chunkingByKeyMatchesTheOriginal() {
    replay("chunk_events_by_key", c -> Aggregations.chunkEventsByKey(
        Corpus.events(c.args().get("events")), String.valueOf(c.args().get("key")), 5.0));
  }

  @Test
  void sortingByTimestampMatchesTheOriginal() {
    replay("sort_by_timestamp",
        c -> Aggregations.sortByTimestamp(Corpus.events(c.args().get("events"))));
  }

  @Test
  void sortingByDurationMatchesTheOriginal() {
    replay("sort_by_duration",
        c -> Aggregations.sortByDuration(Corpus.events(c.args().get("events"))));
  }

  @Test
  void limitingMatchesTheOriginal() {
    replay("limit_events", c -> Aggregations.limitEvents(
        Corpus.events(c.args().get("events")), ((Number) c.args().get("count")).intValue()));
  }

  @Test
  void summingDurationsMatchesTheOriginal() {
    replay("sum_durations",
        c -> Aggregations.sumDurations(Corpus.events(c.args().get("events"))));
  }

  @Test
  void concatenatingMatchesTheOriginal() {
    replay("concat", c -> Aggregations.concat(
        Corpus.events(c.args().get("events1")), Corpus.events(c.args().get("events2"))));
  }

  @Test
  void filteringByValueMatchesTheOriginal() {
    replay("filter_keyvals", c -> Filters.filterKeyvals(
        Corpus.events(c.args().get("events")), String.valueOf(c.args().get("key")),
        new ArrayList<>((List<?>) c.args().get("vals")),
        Boolean.TRUE.equals(c.args().get("exclude"))));
  }

  @Test
  void filteringByPatternMatchesTheOriginal() {
    replay("filter_keyvals_regex", c -> Filters.filterKeyvalsRegex(
        Corpus.events(c.args().get("events")), String.valueOf(c.args().get("key")),
        String.valueOf(c.args().get("regex"))));
  }

  @Test
  void simplifyingTitlesMatchesTheOriginal() {
    replay("simplify_string", c -> Filters.simplifyString(
        Corpus.events(c.args().get("events")), String.valueOf(c.args().get("key"))));
  }

  @Test
  void splittingUrlsMatchesTheOriginal() {
    replay("split_url_events",
        c -> Filters.splitUrlEvents(Corpus.events(c.args().get("events"))));
  }

  @Test
  void categorisingMatchesTheOriginal() {
    replay("categorize", c -> Classify.categorize(
        Corpus.events(c.args().get("events")), categoryRules(c.args().get("classes"))));
  }

  @Test
  void taggingMatchesTheOriginal() {
    replay("tag", c -> Classify.tag(
        Corpus.events(c.args().get("events")), tagRules(c.args().get("classes"))));
  }

  @Test
  void mergingSubwatcherFieldsMatchesTheOriginal() {
    replay("merge_subwatcher_fields", c -> SubwatcherFields.mergeSubwatcherFields(
        Corpus.events(c.args().get("base")), Corpus.events(c.args().get("subs")),
        Corpus.strings(c.args().get("keys")), String.valueOf(c.args().get("conflict"))));
  }

  @Test
  void theCorpusCoversEveryFamilyItSaysItDoes() {
    assertEquals(260, Corpus.size(), "the corpus is the one the oracle wrote");
    assertTrue(Corpus.families().size() >= 40,
        "the corpus covers every area, was " + Corpus.families().size());
  }

  @SuppressWarnings("unchecked")
  private static List<Classify.CategoryRule> categoryRules(Object raw) {
    List<Classify.CategoryRule> out = new ArrayList<>();
    for (Object entry : (List<Object>) raw) {
      List<Object> pair = (List<Object>) entry;
      out.add(new Classify.CategoryRule(Corpus.strings(pair.get(0)),
          Classify.Rule.of(spec(pair.get(1)))));
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static List<Classify.TagRule> tagRules(Object raw) {
    List<Classify.TagRule> out = new ArrayList<>();
    for (Object entry : (List<Object>) raw) {
      List<Object> pair = (List<Object>) entry;
      out.add(new Classify.TagRule(String.valueOf(pair.get(0)),
          Classify.Rule.of(spec(pair.get(1)))));
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> spec(Object raw) {
    return raw instanceof Map<?, ?> map
        ? (Map<String, Object>) map
        : new LinkedHashMap<>();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> cast(Object raw) {
    return (Map<String, Object>) raw;
  }
}
