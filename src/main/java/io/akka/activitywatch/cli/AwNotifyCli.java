package io.akka.activitywatch.cli;

import io.akka.activitywatch.application.Notifier;
import io.akka.activitywatch.application.NotifyService;
import io.akka.activitywatch.client.ActivityWatchClient;
import io.akka.activitywatch.domain.Dirs;
import io.akka.activitywatch.domain.NotifyConfig;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * `aw-notify`: the notification service — SPEC-001 §3 R144, R145.
 *
 * <p>Three things to do: run it, or ask it once for a summary at either level of detail.
 * Running it is the only one that keeps state, and the state is the alerts.
 */
public final class AwNotifyCli {

  private AwNotifyCli() {}

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  /** R144: the flags, and what the port defaults to without them. */
  public record Options(String command, boolean verbose, Path config, boolean testing,
      Integer port, boolean outputOnly) {

    /** R144: `--testing` moves the server port, and `--port` overrides both. */
    public int serverPort() {
      return port != null ? port : (testing ? 5666 : 5600);
    }
  }

  /** Visible for testing: the arguments, read. */
  public static Options parse(String[] args) {
    String command = null;
    boolean verbose = false;
    Path config = null;
    boolean testing = false;
    Integer port = null;
    boolean outputOnly = false;
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "-v", "--verbose" -> verbose = true;
        case "-c", "--config" -> config = ++i < args.length ? Path.of(args[i]) : null;
        case "--testing" -> testing = true;
        case "--port" -> port = ++i < args.length ? Integer.valueOf(args[i]) : null;
        case "-o", "--output-only" -> outputOnly = true;
        default -> {
          if (command == null) {
            command = args[i];
          }
        }
      }
    }
    return new Options(command == null ? "start" : command, verbose, config, testing, port,
        outputOnly);
  }

  public static int run(String[] args, PrintStream out, PrintStream err) {
    Options options;
    try {
      options = parse(args);
    } catch (NumberFormatException e) {
      err.println("--port takes a number");
      return 2;
    }
    if (!List.of("start", "checkin", "checkin-detailed", "help", "--help").contains(
        options.command())) {
      err.println("no such command: " + options.command());
      usage(err);
      return 2;
    }
    if (options.command().equals("help") || options.command().equals("--help")) {
      usage(out);
      return 0;
    }

    NotifyConfig config;
    try {
      config = loadConfig(options.config());
    } catch (RuntimeException e) {
      err.println(e.getMessage());
      return 1;
    }

    try (ActivityWatchClient client = new ActivityWatchClient(
        "aw-notify", "127.0.0.1", options.serverPort(), options.testing())) {
      NotifyService service = new NotifyService(
          io.akka.activitywatch.application.NotifySource.of(client), config,
          new Notifier(notification -> show(notification, options.outputOnly(), out)),
          ZoneId.systemDefault(), Instant::now);
      return switch (options.command()) {
        case "checkin" -> once(service, out, () -> service.sendCheckin("Time today", null));
        case "checkin-detailed" -> once(service, out,
            () -> service.sendDetailedCheckin("Detailed Time Summary", null));
        default -> start(service, out);
      };
    } catch (RuntimeException e) {
      err.println(e.getMessage());
      return 1;
    }
  }

  /**
   * R113: the configuration, written out with the defaults in it if there is none.
   *
   * <p>Under the configuration directory rather than beside the server's own file: the
   * service is a separate program with a separate lifetime, and a person turning off the
   * hourly summary should not be editing the file the server reads.
   */
  public static NotifyConfig loadConfig(Path given) {
    Path path = given != null ? given
        : Dirs.configDir(null).resolve("aw-notify").resolve("config.toml");
    if (!Files.exists(path)) {
      NotifyConfig defaults = NotifyConfig.defaults();
      try {
        Files.createDirectories(path.getParent());
        Files.writeString(path, defaults.toToml(), StandardCharsets.UTF_8);
      } catch (java.io.IOException e) {
        throw new IllegalStateException(
            "Failed to write config file " + path + ": " + e.getMessage());
      }
      return defaults;
    }
    try {
      return NotifyConfig.read(Files.readString(path, StandardCharsets.UTF_8));
    } catch (java.io.IOException e) {
      throw new IllegalStateException(
          "Failed to read config file " + path + ": " + e.getMessage());
    } catch (RuntimeException e) {
      throw new IllegalStateException(
          "Failed to parse config file " + path + ": " + e.getMessage());
    }
  }

  private static int once(NotifyService service, PrintStream out, Runnable send) {
    send.run();
    service.notifier().drain(Instant::now, AwNotifyCli::sleep);
    return 0;
  }

  private static int start(NotifyService service, PrintStream out) {
    service.run(() -> Thread.currentThread().isInterrupted(), AwNotifyCli::sleep);
    return 0;
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(Math.max(0, duration.toMillis()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * R143: what a notification looks like when nothing is going to show it.
   *
   * <p>Putting it on the screen is the operating system's call and is out of scope; printing
   * it is what makes the decision checkable, and it is the original's own `--output-only`.
   */
  private static void show(Notifier.Notification notification, boolean outputOnly,
      PrintStream out) {
    if (outputOnly) {
      out.println(Notifier.outputLine(notification, Instant.now()));
    } else {
      out.println(notification.displayTitle() + "\n" + notification.message());
    }
  }

  private static void usage(PrintStream out) {
    out.println("ActivityWatch notification service");
    out.println();
    out.println("Usage: aw-notify [OPTIONS] [COMMAND]");
    out.println();
    out.println("Commands:");
    out.println("  start             Start the notification service");
    out.println("  checkin           Send a summary notification (top-level categories)");
    out.println("  checkin-detailed  Send a detailed summary with all category levels");
    out.println("  help              Print this message");
    out.println();
    out.println("Options:");
    out.println("  -v, --verbose        Verbose logging");
    out.println("  -c, --config <PATH>  Path to custom configuration file");
    out.println("      --testing        Testing mode (port 5666)");
    out.println("      --port <PORT>    Port to connect to ActivityWatch server");
    out.println("  -o, --output-only    Only print JSON to stdout, no desktop notifications");
  }
}
