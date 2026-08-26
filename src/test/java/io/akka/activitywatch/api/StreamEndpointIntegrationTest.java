package io.akka.activitywatch.api;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import akka.javasdk.testkit.TestKitSupport;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * The change stream — SPEC-001 §3 R112, RENDERING.md R1.
 *
 * <p>The rules this holds are the ones a screenshot cannot: that a write shows up on the
 * stream without anyone asking, that the first thing a subscriber receives is the current
 * state rather than an empty feed it has to fill by asking, and that a subscriber which drops
 * the connection and comes back is not missing anything.
 */
class StreamEndpointIntegrationTest extends TestKitSupport {

  /** A subscriber, reading lines off the stream in the background as a browser would. */
  private static final class Subscriber implements AutoCloseable {
    private final List<String> messages = new CopyOnWriteArrayList<>();
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final Thread reader;
    private volatile String lastEventId;

    Subscriber(String url, String since) {
      HttpClient http = HttpClient.newHttpClient();
      HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
          .header("Accept", "text/event-stream")
          .timeout(Duration.ofSeconds(30));
      if (since != null) {
        request.header("Last-Event-ID", since);
      }
      reader = new Thread(() -> {
        try {
          HttpResponse<java.io.InputStream> response = http.send(request.build(),
              HttpResponse.BodyHandlers.ofInputStream());
          try (BufferedReader lines = new BufferedReader(
              new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while (open.get() && (line = lines.readLine()) != null) {
              if (line.startsWith("data:")) {
                messages.add(line.substring(5).strip());
              } else if (line.startsWith("id:")) {
                lastEventId = line.substring(3).strip();
              }
            }
          }
        } catch (Exception e) {
          // Closing the stream from this side ends the read, which is how it is stopped.
        }
      }, "stream-subscriber");
      reader.setDaemon(true);
      reader.start();
    }

    List<String> messages() {
      return new ArrayList<>(messages);
    }

    String lastEventId() {
      return lastEventId;
    }

    @Override
    public void close() {
      open.set(false);
      reader.interrupt();
    }
  }

  private String streamUrl() {
    return "http://localhost:" + testKit.getPort() + "/api/0/stream";
  }

  private void post(String path, Object body) {
    httpClient.POST(path).addHeader("Host", "localhost").withRequestBody(body)
        .parseResponseBody(bytes -> new String(bytes, StandardCharsets.UTF_8)).invoke();
  }

  private static void waitFor(java.util.function.BooleanSupplier condition, String what) {
    for (int attempt = 0; attempt < 200 && !condition.getAsBoolean(); attempt++) {
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    assertTrue(condition.getAsBoolean(), what);
  }

  @Test
  void aWriteAppearsOnTheStreamWithoutAnybodyAskingForIt() {
    try (Subscriber subscriber = new Subscriber(streamUrl(), null)) {
      post("/api/0/buckets/stream-one",
          Map.of("client", "probe", "type", "test", "hostname", "probehost"));
      waitFor(() -> subscriber.messages().stream().anyMatch(m -> m.contains("stream-one")),
          "the bucket that was just created arrived on the stream: " + subscriber.messages());

      post("/api/0/buckets/stream-one/heartbeat?pulsetime=5",
          Map.of("timestamp", "2020-01-01T00:00:00+00:00", "duration", 0,
              "data", Map.of("a", 1)));
      waitFor(() -> subscriber.messages().stream()
              .anyMatch(m -> m.contains("event-inserted")),
          "the heartbeat arrived too: " + subscriber.messages());
    }
  }

  @Test
  void aRowCarriesTheChangeThatProducedItSoASubscriberNeedNotAsk() {
    post("/api/0/buckets/stream-two",
        Map.of("client", "probe", "type", "test", "hostname", "probehost"));
    post("/api/0/buckets/stream-two/heartbeat?pulsetime=5",
        Map.of("timestamp", "2020-01-01T00:00:00+00:00", "duration", 0, "data", Map.of("a", 7)));

    try (Subscriber subscriber = new Subscriber(streamUrl(), null)) {
      waitFor(() -> subscriber.messages().stream().anyMatch(m -> m.contains("stream-two")),
          "the current state of every bucket arrives first: " + subscriber.messages());
      String row = subscriber.messages().stream()
          .filter(m -> m.contains("stream-two")).findFirst().orElseThrow();
      Map<String, Object> parsed = Json.readObject(row);
      assertEquals("stream-two", parsed.get("bucket"));
      assertEquals(1, ((Number) parsed.get("events")).intValue());
      assertTrue(parsed.containsKey("changes"),
          "a subscriber can tell a coalesced burst from a single change");
    }
  }

  @Test
  void aSubscriberThatComesBackResumesRatherThanReplayingEverything() {
    post("/api/0/buckets/stream-three",
        Map.of("client", "probe", "type", "test", "hostname", "probehost"));

    String resumeFrom;
    try (Subscriber first = new Subscriber(streamUrl(), null)) {
      waitFor(() -> first.lastEventId() != null,
          "the stream numbers its messages so a reader can say where it got to");
      resumeFrom = first.lastEventId();
    }

    // Something happens while nobody is listening.
    post("/api/0/buckets/stream-four",
        Map.of("client", "probe", "type", "test", "hostname", "probehost"));

    try (Subscriber second = new Subscriber(streamUrl(), resumeFrom)) {
      waitFor(() -> second.messages().stream().anyMatch(m -> m.contains("stream-four")),
          "what happened during the break arrives on reconnection: " + second.messages());
    }
  }

  @Test
  void nothingIsAskedForWhileNothingIsHappening() {
    // R1.1 in the small: with a subscriber attached and no writes, the stream is quiet.
    // The interface's own idle window is measured separately with a browser, in gui/.
    try (Subscriber subscriber = new Subscriber(streamUrl(), null)) {
      waitFor(() -> !subscriber.messages().isEmpty(), "the first rows arrive");
      int settled = subscriber.messages().size();
      try {
        Thread.sleep(3000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      assertEquals(settled, subscriber.messages().size(),
          "a stream with nothing to say says nothing");
    }
  }
}
