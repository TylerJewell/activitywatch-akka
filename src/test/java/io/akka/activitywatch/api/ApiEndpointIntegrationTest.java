package io.akka.activitywatch.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.http.StrictResponse;
import akka.javasdk.testkit.TestKitSupport;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The routes, driven the way a client drives them — SPEC-001 §3 R42–R60.
 *
 * <p>These go through HTTP rather than calling the components behind it, because the route
 * table, the status codes and the exact bodies are what a caller sees, and none of them is
 * exercised by asking a component directly. The two routes that share a prefix —
 * `events/count` and `events/{id}` — are here because which of them answers is a fact about
 * the router, not about this code.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiEndpointIntegrationTest extends TestKitSupport {

  private static StrictResponse<String> text(akka.javasdk.http.RequestBuilder<?> request) {
    return request
        .addHeader("Host", "localhost")
        .parseResponseBody(bytes -> new String(bytes, StandardCharsets.UTF_8))
        .invoke();
  }

  private StrictResponse<String> get(String path) {
    return text(httpClient.GET(path));
  }

  private StrictResponse<String> post(String path, Object body) {
    return text(httpClient.POST(path).withRequestBody(body));
  }

  private void makeBucket(String id) {
    var response = post("/api/0/buckets/" + id,
        Map.of("client", "probe", "type", "test", "hostname", "probehost"));
    assertTrue(response.status().intValue() == 200 || response.status().intValue() == 304,
        "creating a bucket answers 200 or 304, was " + response.status());
  }

  @Test
  @Order(1)
  void infoNamesTheHostAndTheVersion() {
    var response = get("/api/0/info");
    assertEquals(200, response.status().intValue());
    Map<String, Object> body = Json.readObject(response.body());
    assertNotNull(body.get("hostname"));
    assertNotNull(body.get("device_id"));
    assertEquals(Boolean.FALSE, body.get("testing"));
  }

  @Test
  @Order(2)
  void creatingABucketTwiceAnswersNotModified() {
    var first = post("/api/0/buckets/it-create",
        Map.of("client", "probe", "type", "test", "hostname", "probehost"));
    assertEquals(200, first.status().intValue());
    var second = post("/api/0/buckets/it-create",
        Map.of("client", "probe", "type", "test", "hostname", "probehost"));
    assertEquals(304, second.status().intValue());
  }

  @Test
  @Order(3)
  void aBucketThatDoesNotExistAnswersNotFound() {
    var response = get("/api/0/buckets/it-nope");
    assertEquals(404, response.status().intValue());
    assertTrue(response.body().contains("There's no bucket named it-nope"));
  }

  @Test
  @Order(4)
  void eventsAreWrittenAndReadBackNewestFirst() {
    makeBucket("it-events");
    var one = post("/api/0/buckets/it-events/events",
        Map.of("timestamp", "2020-01-01T00:00:00+00:00", "duration", 10, "data",
            Map.of("a", 1)));
    assertEquals(200, one.status().intValue());
    assertTrue(one.body().startsWith("[{"), "a single event answers a one-element list");

    var many = post("/api/0/buckets/it-events/events", List.of(
        Map.of("timestamp", "2020-01-01T00:00:20+00:00", "duration", 5, "data", Map.of("a", 2)),
        Map.of("timestamp", "2020-01-01T00:00:30+00:00", "duration", 5, "data", Map.of("a", 3))));
    assertEquals(200, many.status().intValue());
    assertEquals("[]", many.body(), "a bulk write answers an empty list");

    var all = get("/api/0/buckets/it-events/events");
    assertEquals(200, all.status().intValue());
    assertTrue(all.body().indexOf("00:00:30") < all.body().indexOf("00:00:00"),
        "events come back newest first");
  }

  @Test
  @Order(5)
  void countAndEventByIdShareAPrefixAndBothAnswer() {
    makeBucket("it-count");
    post("/api/0/buckets/it-count/events",
        Map.of("timestamp", "2020-01-01T00:00:00+00:00", "duration", 10, "data", Map.of("a", 1)));

    var count = get("/api/0/buckets/it-count/events/count");
    assertEquals(200, count.status().intValue());
    assertEquals("1", count.body(), "the count is a bare integer");

    var event = get("/api/0/buckets/it-count/events/1");
    assertEquals(200, event.status().intValue());
    assertTrue(event.body().contains("\"id\":1"));

    var missing = get("/api/0/buckets/it-count/events/999");
    assertEquals(404, missing.status().intValue());
    assertEquals("null", missing.body(), "an event that is not there answers the body null");
  }

  @Test
  @Order(6)
  void aHeartbeatMergesAndThenStartsANewEvent() {
    makeBucket("it-hb");
    var first = post("/api/0/buckets/it-hb/heartbeat?pulsetime=5",
        Map.of("timestamp", "2020-01-01T00:00:00+00:00", "duration", 0, "data", Map.of("a", 1)));
    assertEquals(200, first.status().intValue());
    long firstId = ((Number) Json.readObject(first.body()).get("id")).longValue();

    var merged = post("/api/0/buckets/it-hb/heartbeat?pulsetime=5",
        Map.of("timestamp", "2020-01-01T00:00:03+00:00", "duration", 0, "data", Map.of("a", 1)));
    Map<String, Object> mergedBody = Json.readObject(merged.body());
    assertEquals(firstId, ((Number) mergedBody.get("id")).longValue(),
        "a merge lengthens the event it merged into and keeps its identity");
    assertEquals(3.0, ((Number) mergedBody.get("duration")).doubleValue(), 1e-9);

    var separate = post("/api/0/buckets/it-hb/heartbeat?pulsetime=5",
        Map.of("timestamp", "2020-01-01T00:00:30+00:00", "duration", 0, "data", Map.of("a", 1)));
    assertEquals(firstId + 1,
        ((Number) Json.readObject(separate.body()).get("id")).longValue());
  }

  @Test
  @Order(7)
  void aHeartbeatWithoutAPulsetimeIsRefused() {
    makeBucket("it-hb2");
    var response = post("/api/0/buckets/it-hb2/heartbeat",
        Map.of("timestamp", "2020-01-01T00:00:00+00:00", "duration", 0, "data", Map.of("a", 1)));
    assertEquals(400, response.status().intValue());
    assertTrue(response.body().contains("Missing required parameter pulsetime"));
  }

  @Test
  @Order(8)
  void aQueryAnswersOncePerTimeperiod() {
    makeBucket("it-query");
    post("/api/0/buckets/it-query/events",
        Map.of("timestamp", "2020-01-01T00:00:00+00:00", "duration", 10, "data",
            Map.of("app", "code")));
    var response = post("/api/0/query/", Map.of(
        "timeperiods", List.of("2020-01-01T00:00:00+00:00/2020-01-02T00:00:00+00:00"),
        "query", List.of("RETURN = query_bucket(\"it-query\");")));
    assertEquals(200, response.status().intValue());
    assertTrue(response.body().contains("\"app\":\"code\""));
  }

  @Test
  @Order(9)
  void aQueryThatWillNotParseAnswersItsOwnExceptionName() {
    var response = post("/api/0/query/", Map.of(
        "timeperiods", List.of("2020-01-01T00:00:00+00:00/2020-01-02T00:00:00+00:00"),
        "query", List.of("RETURN = @;")));
    assertEquals(400, response.status().intValue());
    assertTrue(response.body().contains("QueryParseException"), response.body());
  }

  @Test
  @Order(10)
  void aSettingIsWrittenReadAndDeletedByWritingNothing() {
    var written = post("/api/0/settings/it-key", Map.of("hello", "world"));
    assertEquals(200, written.status().intValue());
    var read = get("/api/0/settings/it-key");
    assertTrue(read.body().contains("world"));

    post("/api/0/settings/it-key", Map.of());
    var afterwards = get("/api/0/settings/it-key");
    assertEquals("null", afterwards.body(), "a falsy value removes the setting");
  }

  @Test
  @Order(11)
  void aForeignHostHeaderIsRefused() {
    var response = httpClient.GET("/api/0/info")
        .addHeader("Host", "evil.example.com")
        .parseResponseBody(bytes -> new String(bytes, StandardCharsets.UTF_8))
        .invoke();
    assertEquals(400, response.status().intValue());
    assertTrue(response.body().contains("host header is invalid"));
  }

  @Test
  @Order(12)
  void theBucketListNamesEveryBucketWithItsLastUpdate() {
    makeBucket("it-list");
    post("/api/0/buckets/it-list/events",
        Map.of("timestamp", "2020-01-01T00:00:00+00:00", "duration", 10, "data", Map.of("a", 1)));
    // The listing is a view, so it settles a moment after the write it reflects.
    String body = "";
    for (int attempt = 0; attempt < 40 && !body.contains("it-list"); attempt++) {
      body = get("/api/0/buckets/").body();
      if (!body.contains("it-list")) {
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
    assertTrue(body.contains("it-list"), body);
  }

  /**
   * More events than a bucket holds — SPEC-001 §4 OD-4, OD-8.
   *
   * <p>A bucket carries only its two hundred most recent events; everything else is in the
   * day pages a consumer writes. Two hundred and sixty events spread over three days is more
   * than the window and more than one page, so an answer that is short, or that is missing a
   * day, is a page that was never written or never asked.
   */
  @Test
  @Order(11)
  @SuppressWarnings("unchecked")
  void aBucketAnswersForMoreEventsThanItHolds() {
    makeBucket("it-pages");
    List<Object> batch = new java.util.ArrayList<>();
    for (int day = 1; day <= 3; day++) {
      for (int minute = 0; minute < 87; minute++) {
        batch.add(Map.of(
            "timestamp", String.format("2020-03-%02dT%02d:%02d:00+00:00",
                day, minute / 60, minute % 60),
            "duration", 30, "data", Map.of("n", day * 100 + minute)));
      }
    }
    assertEquals(200, post("/api/0/buckets/it-pages/events", batch).status().intValue());

    // The pages are written by a consumer, so they settle a moment after the write they
    // reflect. Read the oldest minute while waiting rather than the whole range: only its
    // page can answer for it, it is the first thing the consumer copies, and a poll of the
    // whole range queues a read against every page behind the writes still going into them.
    String oldest = "?start=2020-03-01T00:00:00%2B00:00&end=2020-03-01T00:01:00%2B00:00";
    List<Object> firstDay = List.of();
    for (int attempt = 0; attempt < 40 && firstDay.size() < 2; attempt++) {
      firstDay = (List<Object>) Json.read(get("/api/0/buckets/it-pages/events" + oldest).body());
      if (firstDay.size() < 2) {
        try {
          Thread.sleep(250);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
    assertEquals(2, firstDay.size(),
        "the oldest minute is far outside the window the bucket keeps, so only its page can "
            + "answer for it");

    String range = "?start=2020-03-01T00:00:00%2B00:00&end=2020-03-04T00:00:00%2B00:00";
    List<Object> read = List.of();
    for (int attempt = 0; attempt < 40 && read.size() < 261; attempt++) {
      read = (List<Object>) Json.read(get("/api/0/buckets/it-pages/events" + range).body());
      if (read.size() < 261) {
        try {
          Thread.sleep(250);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
    assertEquals(261, read.size(),
        "every event comes back, including the ones the bucket itself no longer holds");

    // The count above is reached as soon as the pages hold the events the bucket has
    // dropped, which are the oldest and the first copied — the newest day may still be on
    // its way. Waiting for the last page here is what makes the copy finished rather than
    // merely far enough along, so the assertions are not read against a projection that is
    // still moving.
    int lastPage = 0;
    for (int attempt = 0; attempt < 40 && lastPage < 87; attempt++) {
      lastPage = componentClient.forEventSourcedEntity("it-pages_2020-03-03")
          .method(io.akka.activitywatch.application.EventPageEntity::all)
          .invoke().events().size();
      if (lastPage < 87) {
        try {
          Thread.sleep(250);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
    assertEquals(87, lastPage, "every write reached the page for the day it belongs to");
    assertEquals(2, firstDay.size(), firstDay.toString());
  }
}
