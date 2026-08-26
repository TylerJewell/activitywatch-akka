package io.akka.activitywatch.cli;

import io.akka.activitywatch.client.ActivityWatchClient;
import io.akka.activitywatch.domain.DefaultClasses;
import io.akka.activitywatch.domain.Event;
import io.akka.activitywatch.domain.Queries;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * `aw-client`: the tool for poking at a running server — SPEC-001 §3 R83.
 *
 * <p>Six subcommands, the same six the original has. `--port 5600` is treated as unset so
 * that `--testing` can move it, which is the original's own rule and reads oddly until you
 * see why: the port has a default, and a default cannot be told apart from a value somebody
 * typed unless one value is reserved to mean "nobody typed anything".
 */
public final class AwClientCli {

  private static final int DEFAULT_PORT = 5600;
  private static final int TESTING_PORT = 5666;

  private AwClientCli() {}

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  /** Visible for testing: the same entry point without ending the process. */
  public static int run(String[] args, PrintStream out, PrintStream err) {
    Args parsed;
    try {
      parsed = Args.of(args);
    } catch (IllegalArgumentException e) {
      err.println(e.getMessage());
      usage(err);
      return 2;
    }
    if (parsed.help) {
      // R83: the original's argument parser answers `--help` on the tool and on each of its
      // subcommands, and answers it with a zero exit status. A user who types it has asked a
      // question, not made a mistake.
      if (parsed.command == null) {
        usage(out);
      } else {
        usageFor(parsed.command, out);
      }
      return 0;
    }
    if (parsed.command == null) {
      usage(err);
      return 2;
    }

    int port = resolvePort(parsed.port, parsed.testing);
    ActivityWatchClient client = new ActivityWatchClient("aw-client", parsed.host, port,
        parsed.testing);

    try {
      return switch (parsed.command) {
        case "heartbeat" -> heartbeat(client, parsed, out);
        case "buckets" -> buckets(client, out);
        case "events" -> events(client, parsed, out);
        case "query" -> query(client, parsed, out);
        case "report" -> report(client, parsed, out);
        case "canonical" -> canonical(client, parsed, out);
        default -> {
          err.println("no such command: " + parsed.command);
          usage(err);
          yield 2;
        }
      };
    } catch (RuntimeException e) {
      err.println(e.getMessage());
      return 1;
    }
  }

  /**
   * R83: which port a run talks to.
   *
   * <p>The default and "nobody chose one" are the same value, so a caller who typed 5600 gets
   * the testing port under `--testing` — which is the original's rule, and the only way it has
   * of telling a default from a choice.
   */
  static int resolvePort(int given, boolean testing) {
    return given != DEFAULT_PORT ? given : (testing ? TESTING_PORT : DEFAULT_PORT);
  }

  private static int heartbeat(ActivityWatchClient client, Args args, PrintStream out) {
    if (args.positional.size() < 2) {
      out.println("usage: aw-client heartbeat <bucket_id> <json data>");
      return 2;
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) io.akka.activitywatch.api.Json
        .read(args.positional.get(1));
    Event event = Event.of(Instant.now(), 0d, data);
    client.heartbeat(args.positional.get(0), event, args.pulsetime, false);
    out.println(io.akka.activitywatch.api.Json.write(
        io.akka.activitywatch.api.Json.event(event)));
    return 0;
  }

  private static int buckets(ActivityWatchClient client, PrintStream out) {
    out.println("Buckets:");
    for (String bucket : client.buckets().keySet()) {
      out.println(" - " + bucket);
    }
    return 0;
  }

  private static int events(ActivityWatchClient client, Args args, PrintStream out) {
    if (args.positional.isEmpty()) {
      out.println("usage: aw-client events <bucket_id>");
      return 2;
    }
    out.println("events:");
    for (Event event : client.events(args.positional.get(0), null, null, null)) {
      // The original prints its timestamp with the zone dropped and the seconds kept —
      // `2020-01-01 00:00:02`, a space rather than a `T` and never an abbreviated `00:00` —
      // and its data as Python renders a dict.
      out.println(" - " + event.timestamp().atOffset(ZoneOffset.UTC).toLocalDateTime()
          .withNano(0).format(java.time.format.DateTimeFormatter
              .ofPattern("yyyy-MM-dd HH:mm:ss"))
          + " (" + shortDuration(event.duration()) + ") "
          + io.akka.activitywatch.domain.PyJson.repr(event.data()));
    }
    return 0;
  }

  private static int query(ActivityWatchClient client, Args args, PrintStream out) {
    if (args.positional.isEmpty()) {
      out.println("usage: aw-client query <path to a query file>");
      return 2;
    }
    String text;
    try {
      text = Files.readString(Paths.get(args.positional.get(0)), StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      out.println("cannot read " + args.positional.get(0));
      return 1;
    }
    Object answer = client.query(text, List.<Instant[]>of(new Instant[] {args.startAt(), args.stopAt()}),
        args.name);
    if (args.json) {
      out.println(io.akka.activitywatch.api.Json.write(answer));
      return 0;
    }
    for (Object period : (List<?>) answer) {
      List<?> rows = (List<?>) period;
      out.println("Showing 10 out of " + rows.size() + " events:");
      double total = 0;
      for (int i = 0; i < rows.size(); i++) {
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) rows.get(i);
        double duration = ((Number) row.get("duration")).doubleValue();
        total += duration;
        if (i < 10) {
          out.println(" - Duration: " + shortDuration(Event.seconds(duration))
              + " \tData: " + row.get("data"));
        }
      }
      out.println("Total duration:\t " + shortDuration(Event.seconds(total)));
    }
    return 0;
  }

  private static int report(ActivityWatchClient client, Args args, PrintStream out) {
    if (args.positional.isEmpty()) {
      out.println("usage: aw-client report <hostname>");
      return 2;
    }
    String hostname = args.positional.get(0);
    Queries.Params params = Queries.Params.desktop(
        "aw-watcher-window_" + hostname, "aw-watcher-afk_" + hostname, classes(client));
    Object answer = client.query(Queries.fullDesktopQuery(params),
        List.<Instant[]>of(new Instant[] {args.startAt(), args.stopAt()}), args.name);

    for (Object period : (List<?>) answer) {
      @SuppressWarnings("unchecked")
      Map<String, Object> row = (Map<String, Object>) period;
      @SuppressWarnings("unchecked")
      Map<String, Object> window = (Map<String, Object>) row.get("window");
      out.println();
      printTop(out, (List<?>) window.get("cat_events"), "Categories", "$category", args.limit);
      printTop(out, (List<?>) window.get("title_events"), "Titles", "title", args.limit);
      double total = 0;
      for (Object item : (List<?>) window.get("title_events")) {
        total += ((Number) ((Map<?, ?>) item).get("duration")).doubleValue();
      }
      out.println("Total duration:\t " + shortDuration(Event.seconds(total)));
    }
    return 0;
  }

  private static int canonical(ActivityWatchClient client, Args args, PrintStream out) {
    if (args.positional.isEmpty()) {
      out.println("usage: aw-client canonical <hostname>");
      return 2;
    }
    String hostname = args.positional.get(0);
    Queries.Params params = Queries.Params.desktop(
        "aw-watcher-window_" + hostname, "aw-watcher-afk_" + hostname,
        DefaultClasses.defaults());
    String text = Queries.canonicalEvents(params) + "\n RETURN = events;";
    Object answer = client.query(text, List.<Instant[]>of(new Instant[] {args.startAt(), args.stopAt()}),
        args.name);
    for (Object period : (List<?>) answer) {
      List<?> rows = (List<?>) period;
      out.println();
      out.println("Showing last 10 out of " + rows.size() + " events:");
      double total = 0;
      for (int i = 0; i < rows.size(); i++) {
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) rows.get(i);
        total += ((Number) row.get("duration")).doubleValue();
        if (i >= rows.size() - 10) {
          @SuppressWarnings("unchecked")
          Map<String, Object> data = (Map<String, Object>) row.get("data");
          out.println(String.valueOf(row.get("timestamp")).split("\\.")[0] + "  "
              + shortDuration(Event.seconds(((Number) row.get("duration")).doubleValue()))
              + "  [" + data.get("app") + "] " + shorten(String.valueOf(data.get("title")), 60));
        }
      }
      out.println();
      out.println("Total duration:\t " + shortDuration(Event.seconds(total)));
    }
    return 0;
  }

  /** R87: the server's categories when it has them, the defaults when it does not. */
  private static List<Object> classes(ActivityWatchClient client) {
    try {
      Object setting = client.setting("classes");
      if (setting instanceof List<?> list && !list.isEmpty()) {
        List<Object> out = new ArrayList<>();
        for (Object item : list) {
          Map<?, ?> row = (Map<?, ?>) item;
          out.add(List.of(row.get("name"), row.get("rule")));
        }
        return out;
      }
    } catch (RuntimeException e) {
      // Falling back is the original's own behaviour: a server with no categories set is
      // the normal case on a fresh installation, not a failure.
    }
    return DefaultClasses.defaults();
  }

  private static void printTop(PrintStream out, List<?> rows, String title, String key,
      int limit) {
    out.println("Top " + limit + " " + title
        + (rows.size() > 10 ? " (out of " + rows.size() + ")" : ""));
    List<Map<String, Object>> sorted = new ArrayList<>();
    for (Object item : rows) {
      @SuppressWarnings("unchecked")
      Map<String, Object> row = (Map<String, Object>) item;
      sorted.add(row);
    }
    sorted.sort(Comparator.comparingDouble(
        (Map<String, Object> row) -> ((Number) row.get("duration")).doubleValue()).reversed());
    out.println("Duration    Key");
    out.println("----------  ---");
    for (int i = 0; i < Math.min(10, sorted.size()); i++) {
      Map<String, Object> row = sorted.get(i);
      @SuppressWarnings("unchecked")
      Map<String, Object> data = (Map<String, Object>) row.get("data");
      Object value = data.get(key);
      String rendered = value instanceof List<?> list ? String.join(" > ",
          list.stream().map(String::valueOf).toList()) : String.valueOf(value);
      out.println(String.format("%-10s  %s",
          shortDuration(Event.seconds(((Number) row.get("duration")).doubleValue())), rendered));
    }
    out.println();
  }

  private static String shorten(String text, int width) {
    return text.length() <= width ? text : text.substring(0, width - 3) + "...";
  }

  /** `H:MM:SS`, which is what Python prints for a duration with the fraction stripped. */
  static String shortDuration(Duration duration) {
    long seconds = duration.getSeconds();
    return String.format("%d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
  }

  private static void usage(PrintStream out) {
    out.println("CLI utility for aw-client to aid in interacting with the ActivityWatch server");
    out.println();
    out.println("Options:");
    out.println("  --host <address>   Address of host           (default 127.0.0.1)");
    out.println("  --port <port>      Port to use               (default 5600)");
    out.println("  --testing          Use the testing ports by default");
    out.println("  -v, --verbose      Verbosity");
    out.println("  --help             Show this message and exit.");
    out.println();
    out.println("Commands:");
    out.println("  heartbeat <bucket_id> <data>  Send a heartbeat, --pulsetime <n>");
    out.println("  buckets                       List all buckets");
    out.println("  events <bucket_id>            Query events from a bucket");
    out.println("  query <path>                  Run a query in a file on the server");
    out.println("  report <hostname>             Generate an activity report");
    out.println("  canonical <hostname>          Query canonical events for one host");
    out.println();
    out.println("  --start / --stop take an ISO 8601 instant; --json prints raw JSON");
  }

  /**
   * R83: what one command takes.
   *
   * <p>The original's parser answers `<command> --help` with that command's own options
   * rather than with the whole tool's, and a caller reading it is asking what this command
   * takes.
   */
  private static void usageFor(String command, PrintStream out) {
    switch (command) {
      case "heartbeat" -> {
        out.println("Usage: aw-client heartbeat [OPTIONS] BUCKET_ID DATA");
        out.println();
        out.println("  Send a heartbeat to bucket with ID `bucket_id` with JSON `data`");
        out.println();
        out.println("Options:");
        out.println("  --pulsetime <n>  pulsetime to use for merging heartbeats");
      }
      case "buckets" -> {
        out.println("Usage: aw-client buckets [OPTIONS]");
        out.println();
        out.println("  List all buckets");
      }
      case "events" -> {
        out.println("Usage: aw-client events [OPTIONS] BUCKET_ID");
        out.println();
        out.println("  Query events from bucket with ID `bucket_id`");
      }
      case "query" -> {
        out.println("Usage: aw-client query [OPTIONS] PATH");
        out.println();
        out.println("  Run a query in file at `path` on the server");
        out.println();
        rangeOptions(out, true);
      }
      case "report" -> {
        out.println("Usage: aw-client report [OPTIONS] HOSTNAME");
        out.println();
        out.println("  Generate an activity report");
        out.println();
        rangeOptions(out, false);
        out.println("  --limit <n>");
      }
      case "canonical" -> {
        out.println("Usage: aw-client canonical [OPTIONS] HOSTNAME");
        out.println();
        out.println("  Query 'canonical events' for a single host (filtered, classified)");
        out.println();
        rangeOptions(out, false);
      }
      default -> usage(out);
    }
    out.println("  --help                          Show this message and exit.");
  }

  private static void rangeOptions(PrintStream out, boolean named) {
    out.println("Options:");
    if (named) {
      out.println("  --name <text>");
    }
    out.println("  --cache");
    out.println("  --json");
    out.println("  --start [%Y-%m-%d|%Y-%m-%dT%H:%M:%S|%Y-%m-%d %H:%M:%S]");
    out.println("  --stop [%Y-%m-%d|%Y-%m-%dT%H:%M:%S|%Y-%m-%d %H:%M:%S]");
    out.println("  --timezone <text>               Time zone for start and stop options.");
    out.println("                                  Must be a valid IANA identifier like");
    out.println("                                  e.g. 'Europe/Warsaw'.");
  }

  /** The options the two tools share, parsed the same way for both. */
  static final class Args {
    String host = "127.0.0.1";
    int port = DEFAULT_PORT;
    boolean testing;
    boolean verbose;
    boolean json;
    boolean help;
    String name = "";
    double pulsetime = 60;
    int limit = 10;
    // Kept as text until they are read, because `--timezone` may follow them on the command
    // line and decides which zone a naive one names.
    String startText;
    String stopText;
    String timezone;
    Instant start = Instant.now().minus(Duration.ofDays(1));
    Instant stop = Instant.now().plus(Duration.ofDays(365));
    String command;
    final List<String> positional = new ArrayList<>();

    static Args of(String[] argv) {
      Args args = new Args();
      for (int i = 0; i < argv.length; i++) {
        String argument = argv[i];
        switch (argument) {
          case "--host" -> args.host = next(argv, ++i, "--host");
          case "--port" -> args.port = Integer.parseInt(next(argv, ++i, "--port"));
          case "--testing" -> args.testing = true;
          case "-v", "--verbose" -> args.verbose = true;
          case "-h", "--help" -> args.help = true;
          case "--json" -> args.json = true;
          case "--name" -> args.name = next(argv, ++i, "--name");
          case "--pulsetime" -> args.pulsetime = Double.parseDouble(next(argv, ++i,
              "--pulsetime"));
          case "--limit" -> args.limit = Integer.parseInt(next(argv, ++i, "--limit"));
          case "--start" -> args.startText = next(argv, ++i, "--start");
          case "--stop" -> args.stopText = next(argv, ++i, "--stop");
          case "--timezone" -> args.timezone = next(argv, ++i, "--timezone");
          case "--cache" -> {
            // Accepted and ignored, as on the original: the server has no query cache.
          }
          default -> {
            if (argument.startsWith("-")) {
              throw new IllegalArgumentException("no such option: " + argument);
            }
            if (args.command == null) {
              args.command = argument;
            } else {
              args.positional.add(argument);
            }
          }
        }
      }
      return args;
    }

    private static String next(String[] argv, int index, String option) {
      if (index >= argv.length) {
        throw new IllegalArgumentException(option + " needs a value");
      }
      return argv[index];
    }

    /** An instant, or a local date-time read in the machine's own zone as the original does. */
    /**
     * R83: `--start` and `--stop`, in the zone `--timezone` names.
     *
     * <p>The original's argument parser takes three shapes — a date, a date and time with a
     * `T`, and a date and time with a space — and reads them as naive local times; a
     * `--timezone` given afterwards replaces the zone rather than converting the instant, so
     * `--start 2020-01-01 --timezone Europe/Warsaw` is midnight in Warsaw.
     */
    Instant resolve(String text, Instant fallback) {
      if (text == null) {
        return fallback;
      }
      ZoneId zone = timezone == null ? ZoneId.systemDefault() : ZoneId.of(timezone);
      try {
        return Instant.parse(text);
      } catch (RuntimeException notAnInstant) {
        try {
          return LocalDateTime.parse(text.replace(' ', 'T')).atZone(zone).toInstant();
        } catch (RuntimeException notADateTime) {
          return java.time.LocalDate.parse(text).atStartOfDay(zone).toInstant();
        }
      }
    }

    Instant startAt() {
      return resolve(startText, start);
    }

    Instant stopAt() {
      return resolve(stopText, stop);
    }
  }
}
