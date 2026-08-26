package io.akka.activitywatch.domain.query;

import java.util.List;
import java.util.Map;

/**
 * How a value is named and printed in the messages the query language answers with.
 *
 * <p>Those messages reach a caller in the body of a 400 and are part of the contract — a test
 * that compares the two systems compares them — so the names are Python's, not Java's:
 * a list is `<class 'list'>`, a string is `<class 'str'>`, and a list of strings prints with
 * single quotes round each member.
 */
final class PyValues {

  private PyValues() {}

  /** The declared kinds the type check knows about — SPEC-001 §3 R74. */
  enum Kind {
    LIST("list"), STR("str"), INT("int"), FLOAT("float"), UNCHECKED(null);

    final String pythonName;

    Kind(String pythonName) {
      this.pythonName = pythonName;
    }

    boolean checked() {
      return pythonName != null;
    }

    boolean holds(Object value) {
      return switch (this) {
        case LIST -> value instanceof List<?>;
        case STR -> value instanceof String;
        case INT -> value instanceof Long || value instanceof Integer;
        case FLOAT -> value instanceof Double || value instanceof Float;
        case UNCHECKED -> true;
      };
    }
  }

  /** The name Python's `type()` would print for a value. */
  static String typeName(Object value) {
    if (value == null) {
      return "NoneType";
    }
    if (value instanceof String) {
      return "str";
    }
    if (value instanceof Boolean) {
      return "bool";
    }
    if (value instanceof Long || value instanceof Integer) {
      return "int";
    }
    if (value instanceof Double || value instanceof Float) {
      return "float";
    }
    if (value instanceof List<?>) {
      return "list";
    }
    if (value instanceof Map<?, ?>) {
      return "dict";
    }
    if (value instanceof java.time.Duration) {
      return "datetime.timedelta";
    }
    return value.getClass().getSimpleName();
  }

  /** What `str(value)` prints: containers use `repr` on their members, scalars do not. */
  static String str(Object value) {
    if (value instanceof String text) {
      return text;
    }
    return repr(value);
  }

  private static String repr(Object value) {
    if (value == null) {
      return "None";
    }
    if (value instanceof Boolean flag) {
      return flag ? "True" : "False";
    }
    if (value instanceof String text) {
      return "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
    if (value instanceof List<?> list) {
      StringBuilder out = new StringBuilder("[");
      for (int i = 0; i < list.size(); i++) {
        if (i > 0) {
          out.append(", ");
        }
        out.append(repr(list.get(i)));
      }
      return out.append("]").toString();
    }
    if (value instanceof Map<?, ?> map) {
      StringBuilder out = new StringBuilder("{");
      boolean first = true;
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!first) {
          out.append(", ");
        }
        first = false;
        out.append(repr(entry.getKey())).append(": ").append(repr(entry.getValue()));
      }
      return out.append("}").toString();
    }
    return String.valueOf(value);
  }
}
