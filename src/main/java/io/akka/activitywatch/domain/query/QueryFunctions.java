package io.akka.activitywatch.domain.query;

import io.akka.activitywatch.domain.Aggregations;
import io.akka.activitywatch.domain.Classify;
import io.akka.activitywatch.domain.Event;
import io.akka.activitywatch.domain.Filters;
import io.akka.activitywatch.domain.Flood;
import io.akka.activitywatch.domain.Periods;
import io.akka.activitywatch.domain.SubwatcherFields;
import io.akka.activitywatch.domain.UnionNoOverlap;
import io.akka.activitywatch.domain.query.QueryException.QueryFunctionException;
import io.akka.activitywatch.domain.query.QueryException.QueryInterpretException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The twenty-three functions a query may call — SPEC-001 §3 R75.
 *
 * <p>Two things about calling one are part of the contract and are reproduced here rather than
 * left to the language: which arguments are type-checked (R74 — only the ones declared as a
 * plain list, string, integer or float with no default), and in which order the two failures
 * happen. A call with too few arguments fails on the type check reading past the end of the
 * argument list, which reaches the caller as a 500; a call with too many fails afterwards with
 * a message about the number of arguments, which reaches the caller as a 400.
 */
public final class QueryFunctions {

  /** What a function does once its arguments have been checked. */
  public interface Body {
    Object apply(QueryDatastore datastore, Map<String, Object> namespace, List<Object> args);
  }

  /** A function's declared parameters and its body. */
  public record Signature(String name, List<PyValues.Kind> params, int required, Body body) {

    Object call(QueryDatastore datastore, Map<String, Object> namespace, List<Object> args) {
      for (int i = 0; i < params.size(); i++) {
        PyValues.Kind kind = params.get(i);
        if (!kind.checked()) {
          continue;
        }
        if (i >= args.size()) {
          // The original's decorator indexes the argument tuple here without a guard, and the
          // IndexError escapes uncaught. A caller sees a 500, which is the behaviour being
          // matched; a friendlier message would be a different answer to the same call.
          throw new IndexOutOfBoundsException("tuple index out of range");
        }
        Object value = args.get(i);
        if (!kind.holds(value)) {
          throw new QueryFunctionException(
              "Variable '" + PyValues.str(value) + "' passed to function call is of invalid "
                  + "type. Expected <class '" + kind.pythonName + "'> but was <class '"
                  + PyValues.typeName(value) + "'>");
        }
      }
      if (args.size() < required || args.size() > params.size()) {
        throw new QueryInterpretException(
            "Tried to call function " + name + " with invalid amount of arguments");
      }
      return body.apply(datastore, namespace, args);
    }
  }

  private static final Map<String, Signature> FUNCTIONS = new LinkedHashMap<>();

  private QueryFunctions() {}

  public static Signature lookup(String name) {
    return FUNCTIONS.get(name);
  }

  /** Every name the language answers to, in declaration order. */
  public static List<String> names() {
    return List.copyOf(FUNCTIONS.keySet());
  }

  private static void register(String name, List<PyValues.Kind> params, int required, Body body) {
    FUNCTIONS.put(name, new Signature(name, params, required, body));
  }

  private static final PyValues.Kind LIST = PyValues.Kind.LIST;
  private static final PyValues.Kind STR = PyValues.Kind.STR;
  private static final PyValues.Kind INT = PyValues.Kind.INT;
  private static final PyValues.Kind ANY = PyValues.Kind.UNCHECKED;

  static {
    // ------------------------------------------------------------ buckets
    register("find_bucket", List.of(STR, ANY), 1, (ds, ns, args) -> {
      String filter = (String) args.get(0);
      String hostname = args.size() > 1 && args.get(1) != null ? String.valueOf(args.get(1)) : null;
      for (String bucket : ds.buckets()) {
        if (!bucket.contains(filter)) {
          continue;
        }
        if (hostname == null) {
          return bucket;
        }
        Map<String, Object> metadata = ds.metadata(bucket);
        if (metadata != null && hostname.equals(metadata.get("hostname"))) {
          return bucket;
        }
      }
      throw new QueryFunctionException("Unable to find bucket matching '" + filter
          + "' (hostname filter set to '" + (hostname == null ? "None" : hostname) + "')");
    });

    register("query_bucket", List.of(STR), 1, (ds, ns, args) -> {
      String bucket = (String) args.get(0);
      requireBucket(ds, bucket);
      return new ArrayList<Object>(ds.events(bucket, startTime(ns), endTime(ns)));
    });

    register("query_bucket_eventcount", List.of(STR), 1, (ds, ns, args) -> {
      String bucket = (String) args.get(0);
      requireBucket(ds, bucket);
      return ds.eventCount(bucket, startTime(ns), endTime(ns));
    });

    // ----------------------------------------------------------- filtering
    register("filter_keyvals", List.of(LIST, STR, LIST), 3, (ds, ns, args) ->
        wrap(Filters.filterKeyvals(events(args.get(0)), (String) args.get(1),
            values(args.get(2)), false)));

    register("exclude_keyvals", List.of(LIST, STR, LIST), 3, (ds, ns, args) ->
        wrap(Filters.filterKeyvals(events(args.get(0)), (String) args.get(1),
            values(args.get(2)), true)));

    register("filter_keyvals_regex", List.of(LIST, STR, STR), 3, (ds, ns, args) ->
        wrap(Filters.filterKeyvalsRegex(events(args.get(0)), (String) args.get(1),
            (String) args.get(2))));

    register("filter_period_intersect", List.of(LIST, LIST), 2, (ds, ns, args) ->
        wrap(Periods.filterPeriodIntersect(events(args.get(0)), events(args.get(1)))));

    register("period_union", List.of(LIST, LIST), 2, (ds, ns, args) ->
        wrap(Periods.periodUnion(events(args.get(0)), events(args.get(1)))));

    register("limit_events", List.of(LIST, INT), 2, (ds, ns, args) ->
        wrap(Aggregations.limitEvents(events(args.get(0)), ((Number) args.get(1)).intValue())));

    // -------------------------------------------------------------- merging
    register("merge_events_by_keys", List.of(LIST, LIST), 2, (ds, ns, args) ->
        wrap(Aggregations.mergeEventsByKeys(events(args.get(0)), strings(args.get(1)))));

    register("merge_subwatcher_fields", List.of(LIST, LIST, LIST, ANY), 3, (ds, ns, args) -> {
      String conflict = args.size() > 3 ? String.valueOf(args.get(3))
          : SubwatcherFields.BASE_WINS;
      try {
        return wrap(SubwatcherFields.mergeSubwatcherFields(events(args.get(0)),
            events(args.get(1)), strings(args.get(2)), conflict));
      } catch (IllegalArgumentException e) {
        throw new QueryFunctionException(e.getMessage());
      }
    });

    register("chunk_events_by_key", List.of(LIST, STR), 2, (ds, ns, args) ->
        wrap(Aggregations.chunkEventsByKey(events(args.get(0)), (String) args.get(1), 5.0)));

    // -------------------------------------------------------------- sorting
    register("sort_by_timestamp", List.of(LIST), 1, (ds, ns, args) ->
        wrap(Aggregations.sortByTimestamp(events(args.get(0)))));

    register("sort_by_duration", List.of(LIST), 1, (ds, ns, args) ->
        wrap(Aggregations.sortByDuration(events(args.get(0)))));

    // --------------------------------------------------------- summarising
    register("sum_durations", List.of(LIST), 1, (ds, ns, args) ->
        Aggregations.sumDurations(events(args.get(0))));

    register("concat", List.of(LIST, LIST), 2, (ds, ns, args) ->
        wrap(Aggregations.concat(events(args.get(0)), events(args.get(1)))));

    register("union_no_overlap", List.of(LIST, LIST), 2, (ds, ns, args) ->
        wrap(UnionNoOverlap.unionNoOverlap(events(args.get(0)), events(args.get(1)))));

    // ---------------------------------------------------------------- flood
    register("flood", List.of(LIST, ANY), 1, (ds, ns, args) -> {
      double pulsetime = args.size() > 1 ? ((Number) args.get(1)).doubleValue() : 5.0;
      return wrap(Flood.flood(events(args.get(0)), pulsetime));
    });

    // ------------------------------------------------------- watcher-specific
    register("split_url_events", List.of(LIST), 1, (ds, ns, args) ->
        wrap(Filters.splitUrlEvents(events(args.get(0)))));

    register("simplify_window_titles", List.of(LIST, STR), 2, (ds, ns, args) ->
        wrap(Filters.simplifyString(events(args.get(0)), (String) args.get(1))));

    // ----------------------------------------------------------------- test
    register("nop", List.of(), 0, (ds, ns, args) -> 1L);

    // ------------------------------------------------------------- classify
    register("categorize", List.of(LIST, LIST), 2, (ds, ns, args) -> {
      List<Classify.CategoryRule> classes = new ArrayList<>();
      for (Object entry : (List<?>) args.get(1)) {
        List<?> pair = (List<?>) entry;
        classes.add(new Classify.CategoryRule(strings(pair.get(0)),
            Classify.Rule.of(spec(pair.get(1)))));
      }
      return wrap(Classify.categorize(events(args.get(0)), classes));
    });

    register("tag", List.of(LIST, LIST), 2, (ds, ns, args) -> {
      List<Classify.TagRule> classes = new ArrayList<>();
      for (Object entry : (List<?>) args.get(1)) {
        List<?> pair = (List<?>) entry;
        classes.add(new Classify.TagRule(pair.get(0), Classify.Rule.of(spec(pair.get(1)))));
      }
      return wrap(Classify.tag(events(args.get(0)), classes));
    });
  }

  private static void requireBucket(QueryDatastore datastore, String bucket) {
    if (!datastore.exists(bucket)) {
      throw new QueryFunctionException("There's no bucket named '" + bucket + "'");
    }
  }

  private static Instant startTime(Map<String, Object> namespace) {
    return parse(namespace.get("STARTTIME"));
  }

  private static Instant endTime(Map<String, Object> namespace) {
    return parse(namespace.get("ENDTIME"));
  }

  private static Instant parse(Object value) {
    try {
      return java.time.OffsetDateTime.parse(String.valueOf(value)).toInstant();
    } catch (DateTimeParseException e) {
      throw new QueryFunctionException("Unable to parse starttime/endtime for query_bucket");
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Event> events(Object value) {
    List<Event> out = new ArrayList<>();
    for (Object item : (List<Object>) value) {
      if (item instanceof Event event) {
        out.add(event);
      } else if (item instanceof Map<?, ?> map) {
        out.add(fromMap((Map<String, Object>) map));
      } else {
        throw new QueryFunctionException(
            "Variable '" + PyValues.str(item) + "' passed to function call is of invalid type. "
                + "Expected <class 'dict'> but was <class '" + PyValues.typeName(item) + "'>");
      }
    }
    return out;
  }

  private static Event fromMap(Map<String, Object> map) {
    Object timestamp = map.get("timestamp");
    Object duration = map.get("duration");
    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) map.getOrDefault("data", Map.of());
    return Event.of(java.time.OffsetDateTime.parse(String.valueOf(timestamp)).toInstant(),
        duration == null ? 0d : ((Number) duration).doubleValue(), data);
  }

  private static List<Object> values(Object value) {
    return new ArrayList<>((List<?>) value);
  }

  private static List<String> strings(Object value) {
    List<String> out = new ArrayList<>();
    for (Object item : (List<?>) value) {
      out.add(String.valueOf(item));
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> spec(Object value) {
    return (Map<String, Object>) value;
  }

  private static Object wrap(List<Event> events) {
    return new ArrayList<Object>(events);
  }
}
