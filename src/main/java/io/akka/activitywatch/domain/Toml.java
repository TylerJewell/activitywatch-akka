package io.akka.activitywatch.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The subset of TOML the configuration files use, read and written —
 * SPEC-001 §3 R107, R108.
 *
 * <p>The files a user edits are hand-written and small: tables, dotted table headers, arrays
 * of tables, and values that are strings, numbers, booleans, arrays or inline tables. Nothing
 * here handles dates or multi-line strings, because no configuration file in the system has
 * one and a parser that accepts more than the format in use accepts more than it can be
 * checked against.
 *
 * <p>An array of tables — `[[alerts]]`, repeated — is what the notification service's alerts
 * are written as (R113), and it is the one shape whose second occurrence means something
 * different from its first.
 */
public final class Toml {

  private Toml() {}

  /** A parse that fails names the line, because a user wrote the file by hand. */
  public static class TomlException extends RuntimeException {
    public TomlException(String message) {
      super(message);
    }
  }

  public static Map<String, Object> parse(String text) {
    Map<String, Object> root = new LinkedHashMap<>();
    Map<String, Object> table = root;
    int lineNumber = 0;
    for (String rawLine : text.split("\r?\n", -1)) {
      lineNumber++;
      String line = stripComment(rawLine).strip();
      if (line.isEmpty()) {
        continue;
      }
      if (line.startsWith("[[")) {
        if (!line.endsWith("]]")) {
          throw new TomlException("unterminated array-of-tables header on line " + lineNumber);
        }
        table = append(root, splitKey(line.substring(2, line.length() - 2).strip()));
        continue;
      }
      if (line.startsWith("[")) {
        if (!line.endsWith("]")) {
          throw new TomlException("unterminated table header on line " + lineNumber);
        }
        table = descend(root, splitKey(line.substring(1, line.length() - 1).strip()));
        continue;
      }
      int equals = indexOfTopLevel(line, '=');
      if (equals < 0) {
        throw new TomlException("expected a key and a value on line " + lineNumber);
      }
      String key = line.substring(0, equals).strip();
      String value = line.substring(equals + 1).strip();
      table.put(unquote(key), parseValue(value, lineNumber));
    }
    return root;
  }

  /**
   * R107: the defaults written out with every key commented, headers and blank lines left
   * alone, so a user opening the file for the first time sees what they may set.
   */
  public static String commentOutKeys(String text) {
    List<String> lines = new ArrayList<>();
    for (String line : text.split("\r?\n", -1)) {
      String stripped = line.strip();
      if (stripped.isEmpty() || stripped.startsWith("[")) {
        lines.add(line);
      } else {
        lines.add("#" + line);
      }
    }
    return String.join("\n", lines);
  }

  /**
   * R108: the override wins, tables are merged rather than replaced, and a value replacing a
   * table replaces it outright.
   */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> over) {
    Map<String, Object> out = new LinkedHashMap<>(base);
    for (Map.Entry<String, Object> entry : over.entrySet()) {
      Object mine = out.get(entry.getKey());
      Object theirs = entry.getValue();
      if (mine instanceof Map<?, ?> && theirs instanceof Map<?, ?>) {
        out.put(entry.getKey(),
            merge((Map<String, Object>) mine, (Map<String, Object>) theirs));
      } else {
        out.put(entry.getKey(), theirs);
      }
    }
    return out;
  }

  /** A new table on the end of the named array, which is created if it is not there. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> append(Map<String, Object> root, List<String> path) {
    Map<String, Object> parent = descend(root, path.subList(0, path.size() - 1));
    String name = path.get(path.size() - 1);
    Object existing = parent.get(name);
    List<Object> array;
    if (existing instanceof List<?> list) {
      array = new ArrayList<>(list);
    } else {
      array = new ArrayList<>();
    }
    Map<String, Object> table = new LinkedHashMap<>();
    array.add(table);
    parent.put(name, array);
    return table;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> descend(Map<String, Object> root, List<String> path) {
    Map<String, Object> table = root;
    for (String part : path) {
      Object next = table.get(part);
      if (!(next instanceof Map<?, ?>)) {
        next = new LinkedHashMap<String, Object>();
        table.put(part, next);
      }
      table = (Map<String, Object>) next;
    }
    return table;
  }

  private static List<String> splitKey(String key) {
    List<String> parts = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < key.length(); i++) {
      char c = key.charAt(i);
      if (c == '"') {
        quoted = !quoted;
        current.append(c);
      } else if (c == '.' && !quoted) {
        parts.add(unquote(current.toString().strip()));
        current.setLength(0);
      } else {
        current.append(c);
      }
    }
    parts.add(unquote(current.toString().strip()));
    return parts;
  }

  private static String unquote(String key) {
    if (key.length() >= 2 && key.charAt(0) == '"' && key.charAt(key.length() - 1) == '"') {
      return key.substring(1, key.length() - 1);
    }
    if (key.length() >= 2 && key.charAt(0) == '\'' && key.charAt(key.length() - 1) == '\'') {
      return key.substring(1, key.length() - 1);
    }
    return key;
  }

  private static Object parseValue(String value, int lineNumber) {
    if (value.isEmpty()) {
      throw new TomlException("missing value on line " + lineNumber);
    }
    char first = value.charAt(0);
    if (first == '"' || first == '\'') {
      return unescape(value.substring(1, value.length() - 1), first);
    }
    if (first == '[') {
      List<Object> items = new ArrayList<>();
      for (String part : splitTopLevel(value.substring(1, value.length() - 1), ',')) {
        String trimmed = part.strip();
        if (!trimmed.isEmpty()) {
          items.add(parseValue(trimmed, lineNumber));
        }
      }
      return List.copyOf(items);
    }
    if (first == '{') {
      Map<String, Object> table = new LinkedHashMap<>();
      for (String part : splitTopLevel(value.substring(1, value.length() - 1), ',')) {
        String trimmed = part.strip();
        if (trimmed.isEmpty()) {
          continue;
        }
        int equals = indexOfTopLevel(trimmed, '=');
        if (equals < 0) {
          throw new TomlException("expected a key and a value on line " + lineNumber);
        }
        table.put(unquote(trimmed.substring(0, equals).strip()),
            parseValue(trimmed.substring(equals + 1).strip(), lineNumber));
      }
      return table;
    }
    if ("true".equals(value)) {
      return Boolean.TRUE;
    }
    if ("false".equals(value)) {
      return Boolean.FALSE;
    }
    try {
      if (value.contains(".") || value.contains("e") || value.contains("E")) {
        return Double.parseDouble(value);
      }
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new TomlException("not a value this reader understands on line " + lineNumber
          + ": " + value);
    }
  }

  private static String unescape(String text, char quote) {
    if (quote == '\'') {
      return text;
    }
    StringBuilder out = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c != '\\' || i + 1 >= text.length()) {
        out.append(c);
        continue;
      }
      char next = text.charAt(++i);
      switch (next) {
        case 'n' -> out.append('\n');
        case 't' -> out.append('\t');
        case 'r' -> out.append('\r');
        case '"' -> out.append('"');
        case '\\' -> out.append('\\');
        case 'u' -> {
          out.append((char) Integer.parseInt(text.substring(i + 1, i + 5), 16));
          i += 4;
        }
        default -> out.append(next);
      }
    }
    return out.toString();
  }

  /** A `#` inside a string is not a comment, so the scan tracks quoting. */
  private static String stripComment(String line) {
    boolean single = false;
    boolean doubled = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '\'' && !doubled) {
        single = !single;
      } else if (c == '"' && !single) {
        doubled = !doubled;
      } else if (c == '#' && !single && !doubled) {
        return line.substring(0, i);
      }
    }
    return line;
  }

  private static int indexOfTopLevel(String text, char target) {
    int depth = 0;
    boolean single = false;
    boolean doubled = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\'' && !doubled) {
        single = !single;
      } else if (c == '"' && !single) {
        doubled = !doubled;
      } else if (!single && !doubled) {
        if (c == '[' || c == '{') {
          depth++;
        } else if (c == ']' || c == '}') {
          depth--;
        } else if (c == target && depth == 0) {
          return i;
        }
      }
    }
    return -1;
  }

  private static List<String> splitTopLevel(String text, char separator) {
    List<String> parts = new ArrayList<>();
    int depth = 0;
    boolean single = false;
    boolean doubled = false;
    StringBuilder current = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\'' && !doubled) {
        single = !single;
        current.append(c);
      } else if (c == '"' && !single) {
        doubled = !doubled;
        current.append(c);
      } else if (!single && !doubled && (c == '[' || c == '{')) {
        depth++;
        current.append(c);
      } else if (!single && !doubled && (c == ']' || c == '}')) {
        depth--;
        current.append(c);
      } else if (!single && !doubled && c == separator && depth == 0) {
        parts.add(current.toString());
        current.setLength(0);
      } else {
        current.append(c);
      }
    }
    parts.add(current.toString());
    return parts;
  }
}
