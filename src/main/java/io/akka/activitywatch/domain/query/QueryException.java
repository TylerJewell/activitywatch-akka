package io.akka.activitywatch.domain.query;

/**
 * What a query can go wrong with — SPEC-001 §3 R69, R73, R74.
 *
 * <p>The class name reaches the caller as the {@code type} field of a 400, so the four
 * subclasses are part of the API rather than an implementation detail. Anything thrown that is
 * not one of these reaches the caller as a 500, which is what the original does too.
 */
public class QueryException extends RuntimeException {

  public QueryException(String message) {
    super(message);
  }

  /** The name the API answers with, matching the original's Python class names. */
  public String type() {
    return getClass().getSimpleName();
  }

  public static class QueryParseException extends QueryException {
    public QueryParseException(String message) {
      super(message);
    }
  }

  public static class QueryInterpretException extends QueryException {
    public QueryInterpretException(String message) {
      super(message);
    }
  }

  public static class QueryFunctionException extends QueryException {
    public QueryFunctionException(String message) {
      super(message);
    }
  }
}
