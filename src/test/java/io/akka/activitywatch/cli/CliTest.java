package io.akka.activitywatch.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The two command-line tools — SPEC-001 §3 R83, R84.
 *
 * <p>A tool is a surface, so these run it the way somebody types it and read what came out,
 * rather than calling the functions behind it. The server is a real one on a socket so the
 * request each subcommand makes is a real request.
 */
class CliTest {

  private HttpServer server;
  private final List<String> received = new CopyOnWriteArrayList<>();

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      received.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " " + body);
      if (body.contains("RETURN = {")) {
        // The dashboard query asks for a shaped object, not a list of events.
        exchange.setAttribute("dashboard", true);
      }
      respond(exchange, answerFor(exchange));
    });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private static String answerFor(HttpExchange exchange) {
    String path = exchange.getRequestURI().getPath();
    if (path.endsWith("/buckets/")) {
      return "{\"aw-watcher-window_h\":{\"id\":\"aw-watcher-window_h\"},"
          + "\"aw-watcher-afk_h\":{\"id\":\"aw-watcher-afk_h\"}}";
    }
    if (path.endsWith("/events")) {
      return "[{\"id\":1,\"timestamp\":\"2020-01-01T00:00:00+00:00\",\"duration\":10.0,"
          + "\"data\":{\"app\":\"code\"}}]";
    }
    if (path.contains("/query")) {
      String event = "{\"id\":1,\"timestamp\":\"2020-01-01T00:00:00+00:00\","
          + "\"duration\":10.0,\"data\":{\"app\":\"code\",\"title\":\"a.py\","
          + "\"$category\":[\"Work\"]}}";
      if (exchange.getAttribute("dashboard") != null) {
        return "[{\"events\":[" + event + "],\"window\":{\"app_events\":[" + event
            + "],\"title_events\":[" + event + "],\"cat_events\":[" + event
            + "],\"active_events\":[],\"duration\":10.0},"
            + "\"browser\":{\"domains\":[],\"urls\":[],\"duration\":0}}]";
      }
      return "[[" + event + "]]";
    }
    if (path.endsWith("/settings/classes")) {
      return "null";
    }
    return "{}";
  }

  private static void respond(HttpExchange exchange, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private record Result(int code, String out, String err) {}

  private Result runClient(String... command) {
    List<String> args = new ArrayList<>(List.of("--host", "127.0.0.1",
        "--port", String.valueOf(server.getAddress().getPort())));
    args.addAll(List.of(command));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int code = AwClientCli.run(args.toArray(new String[0]),
        new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8));
    return new Result(code, out.toString(StandardCharsets.UTF_8),
        err.toString(StandardCharsets.UTF_8));
  }

  private static Result runCli(String... command) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int code = AwCli.run(command, new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8));
    return new Result(code, out.toString(StandardCharsets.UTF_8),
        err.toString(StandardCharsets.UTF_8));
  }

  @Test
  void withNoCommandItSaysWhatItCanDo() {
    Result result = runClient();
    assertEquals(2, result.code());
    for (String command : List.of("heartbeat", "buckets", "events", "query", "report",
        "canonical")) {
      assertTrue(result.err().contains(command), "the usage names " + command);
    }
  }

  @Test
  void listingBucketsAsksTheServerAndPrintsWhatItSaid() {
    Result result = runClient("buckets");
    assertEquals(0, result.code());
    assertTrue(result.out().contains("aw-watcher-window_h"));
    assertTrue(received.stream().anyMatch(r -> r.startsWith("GET /api/0/buckets/")));
  }

  @Test
  void listingEventsPrintsOnePerLine() {
    Result result = runClient("events", "aw-watcher-window_h");
    assertEquals(0, result.code());
    // The whole line, because every part of it is the original's rendering rather than the
    // platform's: a space instead of a `T`, seconds that are kept when they are zero, no
    // zone, and the data as Python prints a dict.
    assertTrue(result.out().contains(" - 2020-01-01 00:00:00 (0:00:10) {'app': 'code'}"),
        result.out());
  }

  @Test
  void aHeartbeatIsPostedWithThePulsetimeThatWasAskedFor() {
    Result result = runClient("heartbeat", "--pulsetime", "30", "b", "{\"app\":\"code\"}");
    assertEquals(0, result.code());
    assertTrue(received.stream().anyMatch(r -> r.contains("/heartbeat?pulsetime=30")),
        received.toString());
  }

  @Test
  void aReportAsksAQueryAndPrintsTheTopOfEachTable() {
    Result result = runClient("report", "h");
    assertEquals(0, result.code());
    assertTrue(result.out().contains("Categories"), result.out());
    assertTrue(result.out().contains("Titles"), result.out());
    assertTrue(result.out().contains("Total duration"), result.out());
  }

  @Test
  void canonicalEventsArePrintedNewestLast() {
    Result result = runClient("canonical", "h");
    assertEquals(0, result.code());
    assertTrue(result.out().contains("[code] a.py"), result.out());
  }

  @Test
  void aPortOf5600MeansNobodyChoseOne() {
    // R83: `--port 5600` is the default, so `--testing` may still move it. Anything else a
    // caller typed wins over `--testing`.
    assertEquals(5666, AwClientCli.resolvePort(5600, true));
    assertEquals(5600, AwClientCli.resolvePort(5600, false));
    assertEquals(9999, AwClientCli.resolvePort(9999, true));
  }

  @Test
  void anUnknownOptionIsRefusedRatherThanIgnored() {
    Result result = runClient("--nonsense", "buckets");
    assertEquals(2, result.code());
    assertTrue(result.err().contains("no such option"));
  }

  @Test
  void anUnknownCommandSaysSo() {
    Result result = runClient("nonesuch");
    assertEquals(2, result.code());
    assertTrue(result.err().contains("no such command"));
  }

  @Test
  void theOtherToolPrintsWhereItKeepsItsFiles() {
    Result result = runCli("directories");
    assertEquals(0, result.code());
    for (String kind : List.of("config", "data", "logs", "cache")) {
      assertTrue(result.out().contains(kind), result.out());
    }
    assertTrue(result.out().contains("activitywatch"));
  }

  @Test
  void theOtherToolListsModules() {
    Result result = runCli("modules");
    assertEquals(0, result.code());
    assertTrue(result.out().contains("name"), result.out());
    assertTrue(result.out().contains("status"), result.out());
  }

  @Test
  void theOtherToolRefusesACommandItDoesNotHave() {
    Result result = runCli("nonesuch");
    assertEquals(2, result.code());
    assertTrue(result.err().contains("no such command"));
  }

  @Test
  void theOtherToolWithNothingSaysWhatItCanDo() {
    Result result = runCli();
    assertEquals(2, result.code());
    for (String command : List.of("directories", "logs", "modules")) {
      assertTrue(result.out().contains(command), result.out());
    }
  }

  @Test
  void aDurationIsPrintedTheWayTheOriginalPrintsIt() {
    assertEquals("0:00:10", AwClientCli.shortDuration(java.time.Duration.ofSeconds(10)));
    assertEquals("1:02:03",
        AwClientCli.shortDuration(java.time.Duration.ofSeconds(3723)));
    assertEquals("0:00:10",
        AwClientCli.shortDuration(java.time.Duration.ofMillis(10_500)),
        "the fraction is dropped, not rounded");
  }

  @Test
  void aRangeMayBeGivenInAnyOfTheShapesTheOriginalTakes() {
    AwClientCli.Args args = AwClientCli.Args.of(new String[] {
        "--start", "2020-01-01T00:00:00Z", "query", "q.aw"});
    assertEquals(java.time.Instant.parse("2020-01-01T00:00:00Z"), args.startAt());

    args = AwClientCli.Args.of(new String[] {
        "--start", "2020-01-01 06:00:00", "--timezone", "UTC", "query", "q.aw"});
    assertEquals(java.time.Instant.parse("2020-01-01T06:00:00Z"), args.startAt(),
        "a space between the date and the time is one of the three shapes");

    args = AwClientCli.Args.of(new String[] {
        "--start", "2020-01-01", "--timezone", "Europe/Warsaw", "query", "q.aw"});
    assertEquals(java.time.Instant.parse("2019-12-31T23:00:00Z"), args.startAt(),
        "a bare date is midnight in the zone named, not midnight in UTC");
  }

  @Test
  void aTimezoneGivenAfterARangeStillDecidesIt() {
    AwClientCli.Args before = AwClientCli.Args.of(new String[] {
        "--timezone", "UTC", "--start", "2020-01-01", "canonical", "h"});
    AwClientCli.Args after = AwClientCli.Args.of(new String[] {
        "--start", "2020-01-01", "--timezone", "UTC", "canonical", "h"});
    assertEquals(before.startAt(), after.startAt(),
        "the range is read once the arguments are all in, not as each one arrives");
  }
}
