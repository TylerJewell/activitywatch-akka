package io.akka.activitywatch.domain;

import java.util.List;
import java.util.Map;

/**
 * JSON written the way Python's `json.dumps` writes it by default.
 *
 * <p>The canonical queries embed JSON in their text and SPEC-001 §3 R85 says that text must
 * match the original byte for byte, so three of Python's defaults have to be reproduced rather
 * than left to a JSON library: a space after every comma and every colon, non-ASCII escaped as
 * `\\uXXXX`, and `true`/`false`/`null` for the three constants.
 */
public final class PyJson {

  private PyJson() {}

  public static String dumps(Object value) {
    StringBuilder out = new StringBuilder();
    write(out, value);
    return out.toString();
  }

  /** A value as Python prints it: single-quoted strings, `True`, `False`, `None`. */
  public static String repr(Object value) {
    if (value == null) {
      return "None";
    }
    if (value instanceof Boolean flag) {
      return flag ? "True" : "False";
    }
    if (value instanceof String text) {
      return "'" + text.replace("\\", "\\\\").replace("'", "\'") + "'";
    }
    if (value instanceof List<?> list) {
      List<String> parts = new java.util.ArrayList<>(list.size());
      for (Object item : list) {
        parts.add(repr(item));
      }
      return "[" + String.join(", ", parts) + "]";
    }
    if (value instanceof Map<?, ?> map) {
      List<String> parts = new java.util.ArrayList<>(map.size());
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        parts.add(repr(String.valueOf(entry.getKey())) + ": " + repr(entry.getValue()));
      }
      return "{" + String.join(", ", parts) + "}";
    }
    StringBuilder number = new StringBuilder();
    write(number, value);
    return number.toString();
  }

  private static void write(StringBuilder out, Object value) {
    if (value == null) {
      out.append("null");
    } else if (value instanceof Boolean flag) {
      out.append(flag ? "true" : "false");
    } else if (value instanceof String text) {
      writeString(out, text);
    } else if (value instanceof Number number) {
      writeNumber(out, number);
    } else if (value instanceof List<?> list) {
      out.append('[');
      for (int i = 0; i < list.size(); i++) {
        if (i > 0) {
          out.append(", ");
        }
        write(out, list.get(i));
      }
      out.append(']');
    } else if (value instanceof Map<?, ?> map) {
      out.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!first) {
          out.append(", ");
        }
        first = false;
        writeString(out, String.valueOf(entry.getKey()));
        out.append(": ");
        write(out, entry.getValue());
      }
      out.append('}');
    } else {
      writeString(out, String.valueOf(value));
    }
  }

  private static void writeNumber(StringBuilder out, Number number) {
    if (number instanceof Integer || number instanceof Long || number instanceof Short
        || number instanceof Byte) {
      out.append(number.longValue());
      return;
    }
    double d = number.doubleValue();
    if (d == Math.rint(d) && !Double.isInfinite(d)) {
      // Python prints a float that happens to be whole as `1.0`, not `1`.
      out.append(d);
    } else {
      out.append(d);
    }
  }

  private static void writeString(StringBuilder out, String text) {
    out.append('"');
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        default -> {
          if (c < 0x20 || c > 0x7e) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }
}
