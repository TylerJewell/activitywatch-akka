package io.akka.activitywatch.domain.query;

import io.akka.activitywatch.domain.query.QueryException.QueryParseException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Running a query — SPEC-001 §3 R65–R67.
 *
 * <p>A query is a list of assignments and the answer is whatever ended up in `RETURN`. There
 * are no expressions beyond a function call, no control flow, and no way to define anything:
 * the whole language is "name a value, then name another one made from it".
 */
public final class QueryEngine {

  private QueryEngine() {}

  /** The names a query starts with — R67. */
  public static Map<String, Object> namespace(String name, Instant start, Instant end) {
    Map<String, Object> namespace = new LinkedHashMap<>();
    namespace.put("True", Boolean.TRUE);
    namespace.put("False", Boolean.FALSE);
    namespace.put("true", Boolean.TRUE);
    namespace.put("false", Boolean.FALSE);
    namespace.put("NAME", name == null ? "" : name);
    namespace.put("STARTTIME", isoOffset(start));
    namespace.put("ENDTIME", isoOffset(end));
    return namespace;
  }

  public static Object query(String name, String query, Instant start, Instant end,
      QueryDatastore datastore) {
    Map<String, Object> namespace = namespace(name, start, end);
    for (String statement : QueryParser.splitStatements(query)) {
      String trimmed = statement.strip();
      if (trimmed.isEmpty()) {
        continue;
      }
      QueryParser.Assignment assignment = QueryParser.parseStatement(trimmed, namespace);
      namespace.put(assignment.variable().name(),
          assignment.value().interpret(datastore, namespace));
    }
    if (!namespace.containsKey("RETURN")) {
      throw new QueryParseException(
          "Query doesn't assign the RETURN variable, nothing to respond");
    }
    return namespace.get("RETURN");
  }

  /** R55: the lines a caller sends are joined with nothing between them. */
  public static String join(List<String> lines) {
    return String.join("", lines);
  }

  /**
   * The text form the namespace carries a time in.
   *
   * <p>A query can read `STARTTIME` and hand it straight back, so the text is the one the
   * original writes: a `+00:00` offset rather than a `Z`, and no fractional part when there
   * is none.
   */
  private static String isoOffset(Instant instant) {
    return io.akka.activitywatch.api.Json.instant(instant);
  }
}
