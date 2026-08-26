package io.akka.activitywatch.domain.query;

import io.akka.activitywatch.domain.query.QueryException.QueryParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turning a query's text into tokens — SPEC-001 §3 R65–R71.
 */
public final class QueryParser {

  private QueryParser() {}

  /** One token claimed from the front of a string, and what is left. */
  public record Taken(QueryToken.Kind kind, String token, String rest) {}

  /** An assignment: which variable, and the expression to put in it. */
  public record Assignment(QueryToken.QVariable variable, QueryToken value) {}

  /**
   * The next token, or null when there is nothing left.
   *
   * <p>The kinds are tried in a fixed order and the first that claims anything wins. A kind
   * that claims nothing may still have moved the cursor, which is why the remainder is taken
   * from the claim rather than from the input.
   */
  public static Taken takeToken(String text) {
    if (text.isEmpty()) {
      return null;
    }
    String rest = text.strip();
    for (QueryToken.Kind kind : QueryToken.Kind.values()) {
      QueryToken.Claim claim = kind.check(rest);
      rest = claim.rest();
      if (claim.matched()) {
        return new Taken(kind, claim.token(), rest);
      }
    }
    throw new QueryParseException("Syntax error: " + rest);
  }

  /**
   * R65: one statement, which must be an assignment.
   *
   * <p>The split is on the **first** `=`, and a statement with none is split one character
   * from its end — which is what the original's `line[:find("=")]` does with a −1 — so a
   * statement like `RETURN 1` fails on its value rather than on its variable.
   */
  public static Assignment parseStatement(String line, Map<String, Object> namespace) {
    int separator = line.indexOf('=');
    String varStr = separator < 0
        ? line.substring(0, Math.max(0, line.length() - 1))
        : line.substring(0, separator);
    String valStr = line.substring(separator + 1);
    if (valStr.isEmpty()) {
      throw new QueryParseException("Nothing to assign");
    }
    Taken variable = takeToken(varStr);
    String afterVariable = variable == null ? "" : variable.rest();
    if (!afterVariable.strip().isEmpty()) {
      throw new QueryParseException("Invalid syntax for assignment variable");
    }
    if (variable == null || variable.kind() != QueryToken.Kind.VARIABLE) {
      throw new QueryParseException("Cannot assign to a non-variable");
    }
    Taken value = takeToken(valStr);
    String afterValue = value == null ? "" : value.rest();
    if (!afterValue.isEmpty()) {
      throw new QueryParseException("Invalid syntax for value to assign");
    }
    QueryToken parsedVariable = variable.kind().parse(variable.token(), namespace);
    QueryToken parsedValue = value.kind().parse(value.token(), namespace);
    return new Assignment((QueryToken.QVariable) parsedVariable, parsedValue);
  }

  /**
   * R65: statements are separated by semicolons, and a semicolon inside a string literal does
   * not separate anything.
   */
  public static List<String> splitStatements(String query) {
    List<String> statements = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean single = false;
    boolean doubled = false;
    Character previous = null;
    for (int i = 0; i < query.length(); i++) {
      char c = query.charAt(i);
      if (c == '\'' && (previous == null || previous != '\\') && !doubled) {
        single = !single;
        current.append(c);
      } else if (c == '"' && (previous == null || previous != '\\') && !single) {
        doubled = !doubled;
        current.append(c);
      } else if (c == ';' && !single && !doubled) {
        statements.add(current.toString());
        current.setLength(0);
      } else {
        current.append(c);
      }
      previous = c;
    }
    if (current.length() > 0) {
      statements.add(current.toString());
    }
    return statements;
  }

  /**
   * How far a balanced bracket run reaches, ignoring brackets inside string literals.
   *
   * <p>An unbalanced run reaches the end of the text rather than being refused, which is what
   * the original's loop leaves behind.
   */
  static int balanced(String text, char open, char close) {
    int i = 1;
    int toConsume = 1;
    boolean single = false;
    boolean doubled = false;
    Character previous = null;
    for (int j = 1; j < text.length(); j++) {
      char c = text.charAt(j);
      i = j + 1;
      if (c == '\'' && (previous == null || previous != '\\') && !doubled) {
        single = !single;
      } else if (c == '"' && (previous == null || previous != '\\') && !single) {
        doubled = !doubled;
      } else if (single || doubled) {
        // inside a literal
      } else if (c == close) {
        toConsume--;
      } else if (c == open) {
        toConsume++;
      }
      if (toConsume == 0) {
        break;
      }
      previous = c;
    }
    return i;
  }

  /** The remainder a bracketed kind leaves: one character past its own closing bracket. */
  static String tail(String text, int i) {
    return i + 1 >= text.length() ? "" : text.substring(i + 1);
  }

  /**
   * The guard that stands where the original indexes a string without one.
   *
   * <p>An empty string there is an `IndexError` that reaches the caller as a 500, and this
   * keeps that outcome rather than turning it into a parse error the original never raises.
   */
  static void requireNonEmpty(String text) {
    if (text.isEmpty()) {
      throw new IllegalStateException("string index out of range");
    }
  }
}
