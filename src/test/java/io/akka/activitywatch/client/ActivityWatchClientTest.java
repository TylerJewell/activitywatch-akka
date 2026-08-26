package io.akka.activitywatch.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.akka.activitywatch.domain.Event;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The client library — SPEC-001 §3 R79–R82.
 *
 * <p>The queued heartbeat is a rule about a *sequence*: what a watcher sends over several
 * polls, not what one call does. Each case here drives a run of heartbeats and looks at what
 * came out the other end, because a single call tells you nothing — the first one is always
 * held back.
 *
 * <p>The server is a real one on a real socket rather than a stubbed method, so what is
 * exercised includes the request, the retry rules and the file the queue keeps.
 */
class ActivityWatchClientTest {

  private static final Instant T0 = Instant.parse("2020-01-01T00:00:00Z");

  private HttpServer server;
  private final List<String> received = new CopyOnWriteArrayList<>();
  private final AtomicInteger status = new AtomicInteger(200);

  @TempDir
  Path queueDirectory;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      received.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " " + body);
      respond(exchange, status.get(), answerFor(exchange));
    });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private static String answerFor(HttpExchange exchange) {
    String path = exchange.getRequestURI().getPath();
    if (path.endsWith("/info")) {
      return "{\"hostname\":\"h\",\"version\":\"v\",\"testing\":false,\"device_id\":\"d\"}";
    }
    if (path.endsWith("/events")) {
      return "[]";
    }
    return "{}";
  }

  private static void respond(HttpExchange exchange, int code, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(code, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private ActivityWatchClient client() {
    return new ActivityWatchClient("test-client", "127.0.0.1",
        server.getAddress().getPort(), false, null, queueDirectory);
  }

  private static Event ping(double at, Object value) {
    return Event.of(T0.plusSeconds((long) at), 0d, Map.of("a", value));
  }

  @Test
  void anUnqueuedHeartbeatGoesStraightOut() {
    try (ActivityWatchClient client = client()) {
      client.heartbeat("b", ping(0, 1), 5, false);
      client.heartbeat("b", ping(2, 1), 5, false);
    }
    assertEquals(2, received.size());
    assertTrue(received.get(0).contains("/api/0/buckets/b/heartbeat?pulsetime=5.0"),
        received.get(0));
  }

  @Test
  void aSingleQueuedHeartbeatIsHeldBackAndNeverSent() {
    try (ActivityWatchClient client = client()) {
      client.heartbeat("b", ping(0, 1), 5, true, 10);
      assertEquals(0, queued().size());
      assertEquals(0.0, client.held("b").orElseThrow().durationSeconds(), 1e-9);
    }
  }

  @Test
  void queuedHeartbeatsBelowTheCommitIntervalStayHeld() {
    try (ActivityWatchClient client = client()) {
      for (double at : new double[] {0, 2, 4}) {
        client.heartbeat("b", ping(at, 1), 5, true, 10);
      }
      assertEquals(0, queued().size(), "nothing is sent until the run is worth sending");
      assertEquals(4.0, client.held("b").orElseThrow().durationSeconds(), 1e-9);
    }
  }

  @Test
  void aRunThatReachesTheCommitIntervalIsSentAndTheNextOneStarts() {
    try (ActivityWatchClient client = client()) {
      for (double at : new double[] {0, 5, 10, 12}) {
        client.heartbeat("b", ping(at, 1), 6, true, 10);
      }
      List<Map<String, Object>> sent = queued();
      assertEquals(1, sent.size());
      assertEquals(10.0, duration(sent.get(0)), 1e-9,
          "what is sent is the merged run, not the heartbeat that closed it");
      assertEquals(2.0, client.held("b").orElseThrow().durationSeconds(), 1e-9,
          "the heartbeat that took it past the interval starts the next run");
    }
  }

  @Test
  void aHeartbeatThatWillNotMergeSendsTheRunItEnded() {
    try (ActivityWatchClient client = client()) {
      client.heartbeat("b", ping(0, 1), 5, true, 100);
      client.heartbeat("b", ping(2, 1), 5, true, 100);
      client.heartbeat("b", ping(30, 1), 5, true, 100);
      List<Map<String, Object>> sent = queued();
      assertEquals(1, sent.size());
      assertEquals(2.0, duration(sent.get(0)), 1e-9);
      assertEquals(0.0, client.held("b").orElseThrow().durationSeconds(), 1e-9);
    }
  }

  @Test
  void differingDataEndsARunEvenInsideTheWindow() {
    try (ActivityWatchClient client = client()) {
      client.heartbeat("b", ping(0, 1), 5, true, 100);
      client.heartbeat("b", ping(2, 2), 5, true, 100);
      assertEquals(1, queued().size());
    }
  }

  @Test
  void twoBucketsAreHeldSeparately() {
    try (ActivityWatchClient client = client()) {
      client.heartbeat("one", ping(0, 1), 5, true, 100);
      client.heartbeat("two", ping(0, 2), 5, true, 100);
      assertEquals(1, client.held("one").orElseThrow().data().get("a"));
      assertEquals(2, client.held("two").orElseThrow().data().get("a"));
    }
  }

  @Test
  void onlyHeartbeatsMayBeQueued() {
    try (ActivityWatchClient client = client()) {
      RequestQueue queue = new RequestQueue(client, "test", false, queueDirectory);
      assertThrows(IllegalArgumentException.class,
          () -> queue.add("buckets/b/events", Map.of()));
    }
  }

  @Test
  void theQueueSurvivesTheProcessThatWroteIt() {
    try (ActivityWatchClient client = client()) {
      client.heartbeat("b", ping(0, 1), 5, true, 100);
      client.heartbeat("b", ping(30, 1), 5, true, 100);
    }
    // A second client over the same directory sees what the first one had not yet sent.
    try (ActivityWatchClient second = client()) {
      RequestQueue queue = new RequestQueue(second, "test-client", false, queueDirectory);
      assertEquals(1, queue.size());
    }
  }

  @Test
  void aQueuedHeartbeatReachesTheServerOnceTheQueueIsRunning() throws Exception {
    try (ActivityWatchClient client = client()) {
      client.connect();
      client.heartbeat("b", ping(0, 1), 5, true, 100);
      client.heartbeat("b", ping(30, 1), 5, true, 100);
      waitFor(() -> received.stream().anyMatch(r -> r.contains("/heartbeat")));
    }
    assertTrue(received.stream().anyMatch(r -> r.contains("/heartbeat")), received.toString());
  }

  @Test
  void aBadRequestIsDroppedRatherThanRetriedForever() throws Exception {
    status.set(400);
    try (ActivityWatchClient client = client()) {
      RequestQueue queue = new RequestQueue(client, "drop", false, queueDirectory);
      queue.add("buckets/b/heartbeat?pulsetime=5",
          io.akka.activitywatch.api.Json.event(ping(0, 1)));
      queue.start();
      waitFor(() -> queue.size() == 0);
      assertEquals(0, queue.size(), "a payload the server will never accept is let go");
      queue.stop();
    }
  }

  @Test
  void aServerErrorIsKeptForAnotherTry() throws Exception {
    status.set(500);
    try (ActivityWatchClient client = client()) {
      RequestQueue queue = new RequestQueue(client, "keep", false, queueDirectory);
      queue.add("buckets/b/heartbeat?pulsetime=5",
          io.akka.activitywatch.api.Json.event(ping(0, 1)));
      queue.start();
      Thread.sleep(1500);
      assertEquals(1, queue.size(), "a server that broke may be restarted into one that works");
      queue.stop();
    }
  }

  @Test
  void theClientNamesItsOwnBucketAfterItselfAndTheMachine() {
    try (ActivityWatchClient client = client()) {
      assertTrue(client.ownBucket().startsWith("test-client_"));
    }
  }

  @Test
  void waitingForAServerThatNeverAnswersGivesUp() {
    ActivityWatchClient elsewhere = new ActivityWatchClient("test-client", "127.0.0.1",
        1, false, null, queueDirectory);
    assertThrows(IllegalStateException.class,
        () -> elsewhere.waitForStart(java.time.Duration.ofMillis(300)));
  }

  private List<Map<String, Object>> queued() {
    Path file = queueDirectory.resolve("queued").resolve("test-client.v1.queue");
    if (!Files.isRegularFile(file)) {
      return List.of();
    }
    try {
      List<Map<String, Object>> out = new ArrayList<>();
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        if (!line.isBlank()) {
          out.add(io.akka.activitywatch.api.Json.readObject(line));
        }
      }
      return out;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private static double duration(Map<String, Object> request) {
    Map<String, Object> data = (Map<String, Object>) request.get("data");
    return ((Number) data.get("duration")).doubleValue();
  }

  private static void waitFor(java.util.function.BooleanSupplier condition)
      throws InterruptedException {
    for (int attempt = 0; attempt < 100 && !condition.getAsBoolean(); attempt++) {
      Thread.sleep(50);
    }
  }
}
