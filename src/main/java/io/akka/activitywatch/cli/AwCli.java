package io.akka.activitywatch.cli;

import io.akka.activitywatch.application.ModuleManager;
import io.akka.activitywatch.domain.Dirs;
import io.akka.activitywatch.domain.ModuleRules;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * `aw-cli`: the tools that are not about a running server — SPEC-001 §3 R84, R100–R106.
 *
 * <p>Three subcommands on the original — where the files live, what the logs say, and start
 * the tray — and the tray's job is the module manager, which is here as `modules`. The tray
 * itself is out of scope: its only check is a person looking at it.
 */
public final class AwCli {

  private AwCli() {}

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  /** Visible for testing: the same entry point without ending the process. */
  public static int run(String[] args, PrintStream out, PrintStream err) {
    List<String> rest = new ArrayList<>();
    boolean testing = false;
    boolean help = false;
    String since = null;
    String level = null;
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--testing" -> testing = true;
        case "-h", "--help" -> help = true;
        case "--since" -> since = ++i < args.length ? args[i] : null;
        case "--level" -> level = ++i < args.length ? args[i] : null;
        default -> rest.add(args[i]);
      }
    }
    if (help) {
      // R84: `--help` on the tool, and on each of its commands, is a question answered with
      // a zero status rather than a mistake.
      if (rest.isEmpty()) {
        usage(out);
      } else {
        usageFor(rest.get(0), out);
      }
      return 0;
    }
    if (rest.isEmpty()) {
      usage(out);
      return 2;
    }

    return switch (rest.get(0)) {
      case "directories" -> directories(out);
      case "logs" -> logs(out, rest.size() > 1 ? rest.get(1) : null, testing, since, level);
      case "modules" -> modules(out, rest, testing);
      default -> {
        err.println("no such command: " + rest.get(0));
        usage(err);
        yield 2;
      }
    };
  }

  private static int directories(PrintStream out) {
    out.println("Directory paths used");
    out.println(" - config:  " + Dirs.configDir(null));
    out.println(" - data:    " + Dirs.dataDir(null));
    out.println(" - logs:    " + Dirs.logDir(null));
    out.println(" - cache:   " + Dirs.cacheDir(null));
    return 0;
  }

  private static int logs(PrintStream out, String module, boolean testing, String since,
      String level) {
    Path root = Dirs.logDir(null);
    List<Path> directories = new ArrayList<>();
    if (module != null) {
      directories.add(root.resolve(module));
    } else {
      try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
        for (Path entry : entries) {
          if (Files.isDirectory(entry)) {
            directories.add(entry);
          }
        }
      } catch (IOException e) {
        out.println("No log directory at " + root);
        return 1;
      }
      directories.sort(java.util.Comparator.comparing(Path::toString));
    }

    for (Path directory : directories) {
      Path oldest = oldestLog(directory, testing);
      if (oldest == null) {
        out.println("No logfile found in " + directory);
        continue;
      }
      out.println("# " + oldest);
      printLog(out, oldest, since, level);
    }
    return 0;
  }

  private static int modules(PrintStream out, List<String> rest, boolean testing) {
    ModuleManager manager = new ModuleManager(testing, testing ? 5666 : 5600,
        ModuleManager.defaultBundledPaths());
    String action = rest.size() > 1 ? rest.get(1) : "status";
    switch (action) {
      case "status" -> {
        out.println("name                status      type");
        for (ModuleManager.Status status : manager.statuses()) {
          out.println(String.format("%-18s  %-10s  %s", status.name(),
              status.alive() ? "running" : "stopped", status.origin()));
        }
      }
      case "start" -> {
        if (rest.size() < 3) {
          out.println("usage: aw-cli modules start <name>");
          return 2;
        }
        out.println(manager.start(rest.get(2)) ? "started" : "no such module");
      }
      case "stop" -> {
        if (rest.size() < 3) {
          out.println("usage: aw-cli modules stop <name>");
          return 2;
        }
        out.println(manager.stop(rest.get(2)) ? "stopped" : "was not running");
      }
      case "autostart" -> {
        List<String> requested = rest.size() > 2
            ? List.of(rest.get(2).split(",")) : ModuleRules.DEFAULT_AUTOSTART;
        out.println("started: " + manager.autostart(requested));
      }
      default -> {
        out.println("usage: aw-cli modules [status|start|stop|autostart]");
        return 2;
      }
    }
    return 0;
  }

  /**
   * The oldest logfile in a directory, which is the one the original prints.
   *
   * <p>Names carry an ISO timestamp, so oldest-first is the same as sorting the names.
   */
  private static Path oldestLog(Path directory, boolean testing) {
    if (!Files.isDirectory(directory)) {
      return null;
    }
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
      Path oldest = null;
      for (Path entry : entries) {
        String filename = entry.getFileName().toString();
        if (testing != filename.contains("testing")) {
          continue;
        }
        if (oldest == null
            || filename.compareTo(oldest.getFileName().toString()) < 0) {
          oldest = entry;
        }
      }
      return oldest;
    } catch (IOException e) {
      return null;
    }
  }

  private static void printLog(PrintStream out, Path file, String since, String level) {
    LocalDate from = since == null ? null : LocalDate.parse(since);
    List<String> levels = List.of("DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL");
    int minimum = level == null ? 0 : levels.indexOf(level.toUpperCase(Locale.ROOT));
    try {
      for (String line : Files.readAllLines(file)) {
        if (from != null && line.length() >= 10) {
          try {
            if (LocalDate.parse(line.substring(0, 10)).isBefore(from)) {
              continue;
            }
          } catch (RuntimeException e) {
            // A continuation line carries no date of its own; it belongs with the one above.
          }
        }
        if (minimum > 0) {
          boolean wanted = false;
          for (int i = minimum; i < levels.size(); i++) {
            if (line.contains(levels.get(i))) {
              wanted = true;
              break;
            }
          }
          if (!wanted) {
            continue;
          }
        }
        out.println(line);
      }
    } catch (IOException e) {
      out.println("could not read " + file);
    }
  }

  /** R84: what one command takes. */
  private static void usageFor(String command, PrintStream out) {
    switch (command) {
      case "logs" -> {
        out.println("Usage: aw-cli logs [OPTIONS] [MODULE_NAME]");
        out.println();
        out.println("Options:");
        out.println("  --since [%Y-%m-%d]              Only show logs since this date");
        out.println("  --level [DEBUG|INFO|WARNING|ERROR|CRITICAL]");
        out.println("                                  Only show logs of this level, "
            + "or higher.");
      }
      case "directories" -> {
        out.println("Usage: aw-cli directories [OPTIONS]");
        out.println();
        out.println("  Print the directory paths in use");
      }
      case "modules", "qt" -> {
        out.println("Usage: aw-cli modules [OPTIONS] [status|start|stop|autostart] [NAMES]");
        out.println();
        out.println("  Start and stop the modules the tray would have started");
      }
      default -> {
        usage(out);
        return;
      }
    }
    out.println("  --help                          Show this message and exit.");
  }

  private static void usage(PrintStream out) {
    out.println("aw-cli: helper tools for an ActivityWatch installation");
    out.println();
    out.println("  --testing                       use the testing files and ports");
    out.println("  --help                          Show this message and exit.");
    out.println();
    out.println("Commands:");
    out.println("  directories                     print the paths in use");
    out.println("  logs [module]                   print logs, --since <date> --level <level>");
    out.println("  modules [status|start|stop|autostart [names]]");
  }
}
