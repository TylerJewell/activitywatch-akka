package io.akka.activitywatch.domain.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.akka.activitywatch.domain.BucketState;
import io.akka.activitywatch.domain.Corpus;
import io.akka.activitywatch.domain.Corpus.Case;
import io.akka.activitywatch.domain.Event;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The query language, against the answers the original gave — SPEC-001 §3 R65–R78.
 *
 * <p>Seventy-five queries, including every way one can be malformed. The class name of the
 * exception is compared as well as the message, because a caller of `/api/0/query/` reads it
 * as the `type` field of a 400 and branches on it.
 *
 * <p>The store behind these is built from the same bucket contents the oracle used and goes
 * through {@link BucketState}, so the selection, clipping and rounding a query sees are the
 * ones the HTTP routes see.
 */
class QueryConformanceTest {

  @Test
  void everyQueryAnswersWhatTheOriginalAnswered() {
    List<String> failures = new ArrayList<>();
    List<Case> cases = Corpus.of("query");
    for (Case candidate : cases) {
      String expected = candidate.raised()
          ? failure(candidate.error(), candidate.message())
          : Ids.normalise(Corpus.canonical(candidate.answer()));
      String actual;
      try {
        Object answer = QueryEngine.query(
            String.valueOf(candidate.args().get("name")),
            String.valueOf(candidate.args().get("query")),
            Corpus.instant(candidate.args().get("starttime")),
            Corpus.instant(candidate.args().get("endtime")),
            datastore(candidate.args().get("buckets")));
        actual = Ids.normalise(Corpus.canonical(Corpus.render(answer)));
      } catch (QueryException e) {
        actual = failure(e.type(), e.getMessage());
      } catch (RuntimeException e) {
        actual = failure(e.getClass().getSimpleName(), e.getMessage());
      }
      if (!expected.equals(actual)) {
        failures.add(candidate.name() + "\n  expected " + expected + "\n  was      " + actual);
      }
    }
    if (!failures.isEmpty()) {
      fail(failures.size() + " of " + cases.size() + " queries differ from the original:\n"
          + String.join("\n", failures));
    }
    assertEquals(75, cases.size(), "every query case ran");
  }

  @Test
  void theLanguageExposesExactlyTheFunctionsTheOriginalDoes() {
    assertEquals(List.of(
        "find_bucket", "query_bucket", "query_bucket_eventcount", "filter_keyvals",
        "exclude_keyvals", "filter_keyvals_regex", "filter_period_intersect", "period_union",
        "limit_events", "merge_events_by_keys", "merge_subwatcher_fields",
        "chunk_events_by_key", "sort_by_timestamp", "sort_by_duration", "sum_durations",
        "concat", "union_no_overlap", "flood", "split_url_events", "simplify_window_titles",
        "nop", "categorize", "tag"),
        QueryFunctions.names());
  }

  /**
   * How a failure is compared.
   *
   * <p>The four query exceptions are part of the API — their class name is the `type` field
   * of a 400 — so they are compared by name. Anything else escapes the query language
   * entirely and reaches a caller as a 500 whatever it was called, so only the message is
   * compared: `IndexError` and `IndexOutOfBoundsException` are the same failure in two
   * languages.
   */
  private static String failure(String type, String message) {
    boolean named = type.startsWith("Query");
    return (named ? type : "not-a-query-exception") + ": " + message;
  }

  /**
   * Event identities left out of the comparison, and checked separately.
   *
   * <p>The original's default storage numbers events from one sequence shared by every bucket,
   * so the same three events in the second bucket created are numbered 4, 5 and 6. The port
   * numbers each bucket's events from one, because a bucket is its own entity and a
   * server-wide counter would be a single write bottleneck on the busiest path there is. The
   * numbers are opaque handles and every route that takes one is already scoped to a bucket.
   * SPEC-001 §4 OD-7 records the decision; {@link #identitiesFollowTheEventsTheyBelongTo}
   * checks what the port promises about them instead.
   */
  static final class Ids {
    private Ids() {}

    static String normalise(String rendered) {
      return rendered.replaceAll("\"id\":\\d+", "\"id\":#");
    }
  }

  /**
   * What an identity means on this side — SPEC-001 §4 OD-7.
   *
   * <p>The values differ from the original's; these three properties do not, and they are the
   * ones a caller can read meaning into: an identity is stable through a transform that copies
   * an event, absent on one a transform built, and shared by every segment a single event was
   * split into.
   */
  @Test
  void identitiesFollowTheEventsTheyBelongTo() {
    QueryDatastore store = datastore(Corpus.of("query").stream()
        .filter(c -> c.args().containsKey("buckets")).findFirst().orElseThrow()
        .args().get("buckets"));
    Instant start = Instant.parse("2019-12-31T00:00:00Z");
    Instant end = Instant.parse("2020-01-02T00:00:00Z");

    @SuppressWarnings("unchecked")
    List<Event> read = (List<Event>) QueryEngine.query(
        "t", "RETURN = query_bucket(\"w\");", start, end, store);
    assertEquals(List.of(3L, 2L, 1L), read.stream().map(Event::id).toList(),
        "a bucket's events are numbered from one, newest first out of the store");

    @SuppressWarnings("unchecked")
    List<Event> grouped = (List<Event>) QueryEngine.query(
        "t", "e = query_bucket(\"w\"); RETURN = merge_events_by_keys(e, [\"app\"]);",
        start, end, store);
    assertTrue(grouped.stream().allMatch(e -> e.id() == null),
        "an event a transform built has no identity");

    @SuppressWarnings("unchecked")
    List<Event> split = (List<Event>) QueryEngine.query("t",
        "e = query_bucket(\"w\"); s = query_bucket(\"afk\");"
            + " RETURN = merge_subwatcher_fields(e, s, [\"status\"]);",
        start, end, store);
    assertEquals(2, split.stream().filter(e -> Long.valueOf(2L).equals(e.id())).count(),
        "an event split into two segments leaves both carrying its identity");
  }

  /** A store over the case's own buckets, going through the port's own selection rules. */
  @SuppressWarnings("unchecked")
  private static QueryDatastore datastore(Object raw) {
    Map<String, Object> buckets = (Map<String, Object>) raw;
    Map<String, BucketState> states = new LinkedHashMap<>();
    Map<String, Map<String, Object>> metadata = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : buckets.entrySet()) {
      Map<String, Object> spec = (Map<String, Object>) entry.getValue();
      BucketState state = BucketState.empty(entry.getKey())
          .with(new io.akka.activitywatch.domain.BucketEvent.Created(
              null, String.valueOf(spec.get("type")), String.valueOf(spec.get("client")),
              String.valueOf(spec.get("hostname")), "2020-01-01T00:00:00+00:00", Map.of(), 0));
      long id = 1;
      for (Event event : Corpus.events(spec.get("events"))) {
        state = state.with(
            new io.akka.activitywatch.domain.BucketEvent.Inserted(id++, event));
      }
      states.put(entry.getKey(), state);
      metadata.put(entry.getKey(), state.metadata());
    }

    return new QueryDatastore() {
      @Override
      public List<String> buckets() {
        return List.copyOf(states.keySet());
      }

      @Override
      public boolean exists(String bucketId) {
        return states.containsKey(bucketId);
      }

      @Override
      public Map<String, Object> metadata(String bucketId) {
        return metadata.get(bucketId);
      }

      @Override
      public List<Event> events(String bucketId, Instant start, Instant end) {
        return io.akka.activitywatch.domain.EventSelection.answer(
            states.get(bucketId).recent(), start, end, -1);
      }

      @Override
      public long eventCount(String bucketId, Instant start, Instant end) {
        return io.akka.activitywatch.domain.EventSelection.count(
            states.get(bucketId).recent(), start, end);
      }
    };
  }
}
