package io.akka.activitywatch.domain;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loading a component's configuration — SPEC-001 §3 R107, R108, R110.
 *
 * <p>The first time a component asks, the defaults are written to disk with every key
 * commented out, so the file is a list of what may be set rather than a list of what is set.
 * After that the file is merged over the defaults, which is why adding a default in a later
 * version reaches an installation that already has a file.
 */
public final class AwConfig {

  private AwConfig() {}

  /** Every component's defaults, exactly as the original writes them. */
  public static final String SERVER_DEFAULTS = """
      [server]
      host = "localhost"
      port = "5600"
      storage = "peewee"
      cors_origins = ""

      [server.custom_static]

      [server-testing]
      host = "localhost"
      port = "5666"
      storage = "peewee"
      cors_origins = ""

      [server-testing.custom_static]""";

  public static final String CLIENT_DEFAULTS = """
      [server]
      hostname = "127.0.0.1"
      port = "5600"

      [client]
      commit_interval = 10

      [server-testing]
      hostname = "127.0.0.1"
      port = "5666"

      [client-testing]
      commit_interval = 5""";

  public static final String AFK_DEFAULTS = """
      [aw-watcher-afk]
      timeout = 180
      poll_time = 5

      [aw-watcher-afk-testing]
      timeout = 20
      poll_time = 1""";

  public static final String WINDOW_DEFAULTS = """
      [aw-watcher-window]
      exclude_title = false
      exclude_titles = []
      poll_time = 1.0
      strategy_macos = "swift"
      research_enabled = false

      [aw-watcher-window.research_category_map]

      [aw-watcher-window.research_app_category_map]""";

  public static final String QT_DEFAULTS = """
      [aw-qt]
      autostart_modules = ["aw-server", "aw-watcher-afk", "aw-watcher-window"]

      [aw-qt-testing]
      autostart_modules = ["aw-server", "aw-watcher-afk", "aw-watcher-window"]""";

  /**
   * The configuration for an application, written out first if it was never there.
   *
   * @param appName both the directory and the file's stem, as in `aw-server/aw-server.toml`
   */
  public static Map<String, Object> load(String appName, String defaults) {
    Path path = Dirs.configDir(appName).resolve(appName + ".toml");
    Map<String, Object> defaultTable = Toml.parse(defaults);
    if (!Files.isRegularFile(path)) {
      write(path, Toml.commentOutKeys(defaults));
      return defaultTable;
    }
    try {
      return Toml.merge(defaultTable, Toml.parse(Files.readString(path, StandardCharsets.UTF_8)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static void save(String appName, String contents) {
    // Refuse to write something the reader could not read back.
    Toml.parse(contents);
    write(Dirs.configDir(appName).resolve(appName + ".toml"), contents);
  }

  private static void write(Path path, String contents) {
    try {
      Files.writeString(path, contents, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A section, or an empty table where the file has none. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> section(Map<String, Object> config, String name) {
    Object value = config.get(name);
    return value instanceof Map<?, ?> table
        ? (Map<String, Object>) table
        : new LinkedHashMap<>();
  }

  public static String string(Map<String, Object> table, String key, String fallback) {
    Object value = table.get(key);
    return value == null ? fallback : String.valueOf(value);
  }

  public static int integer(Map<String, Object> table, String key, int fallback) {
    Object value = table.get(key);
    if (value == null) {
      return fallback;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    return Integer.parseInt(String.valueOf(value).strip());
  }

  public static double number(Map<String, Object> table, String key, double fallback) {
    Object value = table.get(key);
    if (value == null) {
      return fallback;
    }
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    return Double.parseDouble(String.valueOf(value).strip());
  }

  public static boolean flag(Map<String, Object> table, String key, boolean fallback) {
    Object value = table.get(key);
    if (value == null) {
      return fallback;
    }
    if (value instanceof Boolean bool) {
      return bool;
    }
    return Boolean.parseBoolean(String.valueOf(value));
  }

  @SuppressWarnings("unchecked")
  public static List<String> strings(Map<String, Object> table, String key) {
    Object value = table.get(key);
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    return ((List<Object>) list).stream().map(String::valueOf).toList();
  }

  /** R111: `k=v,k2=v2`, and a pair that is not a pair is refused. */
  public static Map<String, String> parseKeyValuePairs(String value) {
    Map<String, String> out = new LinkedHashMap<>();
    if (value == null || value.isBlank()) {
      return out;
    }
    for (String pair : value.split(",", -1)) {
      String[] halves = pair.split("=", -1);
      if (halves.length != 2) {
        throw new IllegalArgumentException("Cannot parse key value pair: " + pair);
      }
      out.put(halves[0], halves[1]);
    }
    return out;
  }
}
