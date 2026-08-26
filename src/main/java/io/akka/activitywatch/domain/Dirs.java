package io.akka.activitywatch.domain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Where the system keeps its files — SPEC-001 §3 R109.
 *
 * <p>The application name is `activitywatch` on every platform and each component gets a
 * subdirectory of its own, created on demand. The directories are the platform's, chosen the
 * way `platformdirs` chooses them, with the same Linux exception the original carries: the log
 * directory is under the cache directory rather than the state directory, so an installation
 * upgraded from an older version still finds its logs.
 *
 * <p>On Windows that means the name appears twice — `…\Localctivitywatchctivitywatch`.
 * `platformdirs` puts a vendor above the application, the original passes no vendor, and the
 * default vendor is the application's own name. A port that tidied that away would write its
 * configuration somewhere an existing installation does not look.
 */
public final class Dirs {

  private static final String APP = "activitywatch";

  private Dirs() {}

  public static Path dataDir(String module) {
    return ensure(base("data"), module);
  }

  public static Path configDir(String module) {
    return ensure(base("config"), module);
  }

  public static Path cacheDir(String module) {
    return ensure(base("cache"), module);
  }

  public static Path logDir(String module) {
    if (isLinux()) {
      return ensure(base("cache").resolve("log"), module);
    }
    return ensure(base("log"), module);
  }

  private static Path ensure(Path root, String module) {
    Path path = module == null || module.isEmpty() ? root : root.resolve(module);
    try {
      Files.createDirectories(path);
    } catch (IOException e) {
      throw new IllegalStateException("could not create " + path, e);
    }
    return path;
  }

  private static Path base(String kind) {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    String home = System.getProperty("user.home");
    if (os.contains("win")) {
      // The vendor above the application is the application again, and cache and log are
      // named subdirectories of it rather than siblings of it.
      Path root = Paths.get(env("LOCALAPPDATA", home + "\\AppData\\Local"))
          .resolve(APP).resolve(APP);
      return switch (kind) {
        case "cache" -> root.resolve("Cache");
        case "log" -> root.resolve("Logs");
        default -> root;
      };
    }
    if (os.contains("mac")) {
      return switch (kind) {
        case "cache" -> Paths.get(home, "Library", "Caches", APP);
        case "log" -> Paths.get(home, "Library", "Logs", APP);
        default -> Paths.get(home, "Library", "Application Support", APP);
      };
    }
    return switch (kind) {
      case "config" -> Paths.get(env("XDG_CONFIG_HOME", home + "/.config"), APP);
      case "cache" -> Paths.get(env("XDG_CACHE_HOME", home + "/.cache"), APP);
      case "log" -> Paths.get(env("XDG_STATE_HOME", home + "/.local/state"), APP);
      default -> Paths.get(env("XDG_DATA_HOME", home + "/.local/share"), APP);
    };
  }

  private static boolean isLinux() {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    return !os.contains("win") && !os.contains("mac");
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
