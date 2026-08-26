package io.akka.activitywatch.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which files count as modules, and in what order they are started —
 * SPEC-001 §3 R100–R102, R105.
 *
 * <p>All of this is decided from names and flags rather than from anything on disk, so it can
 * be checked without a filesystem; where a real directory is walked, the walk asks these
 * questions.
 */
public final class ModuleRules {

  /** R100: names that look like modules and are not — the tools, and the manager itself. */
  public static final List<String> IGNORED = List.of(
      "aw-cli", "aw-client", "aw-qt", "aw-qt.desktop", "aw-qt.spec");

  /** R105. */
  public static final List<String> DEFAULT_AUTOSTART = List.of(
      "aw-server", "aw-watcher-afk", "aw-watcher-window");

  private static final List<String> WINDOWS_SUFFIXES = List.of(".exe", ".bat", ".cmd");

  private ModuleRules() {}

  /** Where a module was found. A bundled one wins over a system one of the same name. */
  public enum Origin { BUNDLED, SYSTEM }

  /** A discovered module. */
  public record Module(String name, String path, Origin origin) {}

  /**
   * R100: on Windows a suffix decides, elsewhere the execute bit does — and a `.desktop` file
   * is never a module however its permissions read.
   */
  public static boolean isExecutable(String filename, boolean windows, boolean regularFile,
      boolean executableBit) {
    if (!regularFile) {
      return false;
    }
    if (windows) {
      String lower = filename.toLowerCase(Locale.ROOT);
      return WINDOWS_SUFFIXES.stream().anyMatch(lower::endsWith);
    }
    return executableBit && !filename.endsWith(".desktop");
  }

  /** R100: only the three Windows suffixes are stripped, which is why `.desktop` stays. */
  public static String filenameToName(String filename, boolean windows) {
    if (!windows) {
      return filename;
    }
    String lower = filename.toLowerCase(Locale.ROOT);
    for (String suffix : WINDOWS_SUFFIXES) {
      if (lower.endsWith(suffix)) {
        return filename.substring(0, filename.length() - suffix.length());
      }
    }
    return filename;
  }

  public static List<Module> filterModules(List<Module> modules) {
    List<Module> out = new ArrayList<>();
    for (Module module : modules) {
      if (!IGNORED.contains(module.name())) {
        out.add(module);
      }
    }
    return List.copyOf(out);
  }

  /** R101: the bundled copy of a name, else the system one, else nothing. */
  public static Module preferred(List<Module> modules, String name) {
    Module system = null;
    for (Module module : modules) {
      if (!module.name().equals(name)) {
        continue;
      }
      if (module.origin() == Origin.BUNDLED) {
        return module;
      }
      if (system == null) {
        system = module;
      }
    }
    return system;
  }

  /**
   * R102: which modules to start and in what order.
   *
   * <p>A server goes first because everything else talks to it, and where both servers are
   * asked for only the Rust one starts — the list has both names removed afterwards, so the
   * Python one is not started as an "everything else".
   */
  public static List<String> autostartOrder(List<String> requested, List<String> discovered) {
    List<String> order = new ArrayList<>();
    Set<String> rest = new LinkedHashSet<>(requested);
    if (rest.contains("aw-server-rust")) {
      order.add("aw-server-rust");
    } else if (rest.contains("aw-server")) {
      order.add("aw-server");
    }
    rest.remove("aw-server");
    rest.remove("aw-server-rust");
    order.addAll(rest);
    List<String> out = new ArrayList<>();
    for (String name : order) {
      if (discovered.contains(name)) {
        out.add(name);
      }
    }
    return List.copyOf(out);
  }

  /** Names asked for that were never found; the manager logs each one. */
  public static List<String> unknown(List<String> requested, List<String> discovered) {
    List<String> out = new ArrayList<>();
    for (String name : requested) {
      if (!discovered.contains(name) && !out.contains(name)) {
        out.add(name);
      }
    }
    return List.copyOf(out);
  }
}
