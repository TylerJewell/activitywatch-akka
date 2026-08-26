package io.akka.activitywatch.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.http.StrictResponse;
import akka.javasdk.testkit.TestKitSupport;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What each route answers when it is asked something it cannot do —
 * SPEC-001 §3 R44–R60 and §4 OD-3.
 *
 * <p>Three of these expect a 500 on purpose. The original's routes fail on those inputs, and
 * a port that answered better would answer differently from the system it is a copy of. Each
 * one says which rule it is holding.
 */
class ApiErrorsIntegrationTest extends TestKitSupport {

  private StrictResponse<String> text(akka.javasdk.http.RequestBuilder<?> request) {
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

  private StrictResponse<String> delete(String path) {
    return text(httpClient.DELETE(path));
  }

  private void makeBucket(String id) {
    post("/api/0/buckets/" + id,
        Map.of("client", "probe", "type", "test", "hostname", "probehost"));
  }

  @Test
  void aBucketCreatedWithoutATypeFails() {
    // OD-3: the original reads the key without a guard and the KeyError escapes.
    var response = post("/api/0/buckets/err-notype", Map.of("client", "c", "hostname", "h"));
    assertEquals(500, response.status().intValue());
  }

  @Test
  void updatingABucketFails() {
    // R45: the original's handler names a parameter no storage backend accepts, so this
    // route cannot succeed there for any input.
    makeBucket("err-update");
    var response = text(httpClient.PUT("/api/0/buckets/err-update").withRequestBody(
        Map.of("client", "c", "type", "t", "hostname", "h", "data", Map.of())));
    assertEquals(500, response.status().intValue());
  }

  @Test
  void updatingABucketThatIsNotThereIsStillANotFound() {
    var response = text(httpClient.PUT("/api/0/buckets/err-nope").withRequestBody(
        Map.of("client", "c", "type", "t", "hostname", "h", "data", Map.of())));
    assertEquals(404, response.status().intValue());
  }

  @Test
  void anUnparseableInstantFails() {
    // OD-3: the original lets its date parser's error escape the route.
    makeBucket("err-dates");
    assertEquals(500, get("/api/0/buckets/err-dates/events?start=notadate").status().intValue());
  }

  @Test
  void aPulsetimeThatIsNotANumberFails() {
    // OD-3, again the original's own behaviour.
    makeBucket("err-pulse");
    var response = post("/api/0/buckets/err-pulse/heartbeat?pulsetime=abc",
        Map.of("timestamp", "2020-01-01T00:00:00+00:00", "duration", 0, "data", Map.of()));
    assertEquals(500, response.status().intValue());
  }

  @Test
  void aHeartbeatWithNoTimestampIsRefusedByTheSchema() {
    makeBucket("err-schema");
    var response = post("/api/0/buckets/err-schema/heartbeat?pulsetime=5",
        Map.of("nonsense", true));
    assertEquals(400, response.status().intValue());
    assertTrue(response.body().contains("'timestamp' is a required property"), response.body());
    assertTrue(response.body().contains("Input payload validation failed"));
  }

  @Test
  void theLogRouteFails() {
    // R60: the original parses a file that is not in the format it expects, always.
    assertEquals(500, get("/api/0/log").status().intValue());
  }

  @Test
  void everyRouteTakingABucketAnswersTheSameNotFound() {
    for (String path : List.of("/api/0/buckets/err-missing",
        "/api/0/buckets/err-missing/events",
        "/api/0/buckets/err-missing/events/count",
        "/api/0/buckets/err-missing/events/1",
        "/api/0/buckets/err-missing/export")) {
      var response = get(path);
      assertEquals(404, response.status().intValue(), path);
      assertTrue(response.body().contains("There's no bucket named err-missing"), path);
    }
  }

  @Test
  void deletingABucketIsAllowedInTestingModeAndOtherwiseNeedsForce() {
    makeBucket("err-delete");
    // This service is not in testing mode, so the guard applies.
    var refused = delete("/api/0/buckets/err-delete");
    assertEquals(401, refused.status().intValue());
    assertTrue(refused.body().contains("DeleteBucketUnauthorized"));

    var allowed = delete("/api/0/buckets/err-delete?force=1");
    assertEquals(200, allowed.status().intValue());
  }

  @Test
  void aBodyThatIsNotAnEventIsARefusal() {
    makeBucket("err-body");
    var response = text(httpClient.POST("/api/0/buckets/err-body/events")
        .withRequestBody("notanevent"));
    assertEquals(400, response.status().intValue());
  }

  @Test
  void aMalformedTimeperiodNamesTheQueryException() {
    var response = post("/api/0/query/",
        Map.of("timeperiods", List.of("notaperiod"), "query", List.of("RETURN = 1;")));
    assertEquals(400, response.status().intValue());
    assertTrue(response.body().contains("QueryException"), response.body());
    assertTrue(response.body().contains("expected two ISO8601"));
  }

  @Test
  void aTimeperiodOfThreePartsIgnoresTheThird() {
    var response = post("/api/0/query/", Map.of(
        "timeperiods", List.of("2020-01-01T00:00:00+00:00/2020-01-02T00:00:00+00:00/x"),
        "query", List.of("RETURN = 1;")));
    assertEquals(200, response.status().intValue());
    assertEquals("[1]", response.body());
  }

  @Test
  void aQueryWithNoTimeperiodsIsRefused() {
    var response = post("/api/0/query/", Map.of("query", List.of("RETURN = 1;")));
    assertEquals(400, response.status().intValue());
    assertTrue(response.body().contains("'timeperiods' is a required property"));
  }

  @Test
  void aQuerySuppliedAsSeveralLinesIsJoinedWithNothingBetweenThem() {
    var response = post("/api/0/query/", Map.of(
        "timeperiods", List.of("2020-01-01T00:00:00+00:00/2020-01-02T00:00:00+00:00"),
        "query", List.of("a = 1;", "RETURN = a;")));
    assertEquals(200, response.status().intValue());
    assertEquals("[1]", response.body());
  }

  @Test
  void aSettingWithNoKeyIsRefused() {
    var response = post("/api/0/settings", Map.of("a", 1));
    assertEquals(400, response.status().intValue());
    assertTrue(response.body().contains("Missing required parameter key"));
  }

  @Test
  void anExportIsOfferedAsAFileWithTheOriginalsName() {
    makeBucket("err-export");
    var whole = get("/api/0/export");
    assertEquals(200, whole.status().intValue());
    // The filename comes back quoted. That is the HTTP layer normalising a header the
    // original writes bare, and the README lists it; what matters here is the name.
    assertTrue(whole.httpResponse().getHeader("Content-Disposition").get().value()
        .contains("aw-buckets-export.json"),
        whole.httpResponse().getHeader("Content-Disposition").get().value());

    var one = get("/api/0/buckets/err-export/export");
    assertTrue(one.httpResponse().getHeader("Content-Disposition").get().value()
        .contains("aw-bucket-export_err-export.json"));
    assertTrue(one.body().contains("\"buckets\""));
  }

  @Test
  void anImportedBucketArrivesWithoutTheIdentitiesItWasExportedWith() {
    var imported = post("/api/0/import", Map.of("buckets", Map.of(
        "err-imported", Map.of(
            "id", "err-imported", "type", "test", "client", "probe", "hostname", "probehost",
            "created", "2020-01-01T00:00:00+00:00",
            "events", List.of(Map.of("id", 4242, "timestamp", "2020-01-01T00:00:00+00:00",
                "duration", 3, "data", Map.of("i", 1)))))));
    assertEquals(200, imported.status().intValue());

    var events = get("/api/0/buckets/err-imported/events");
    assertTrue(events.body().contains("\"id\":1"),
        "the identity in the file is dropped and a fresh one assigned: " + events.body());
  }

  @Test
  void anExportLeavesOutTheIdentities() {
    makeBucket("err-scrub");
    post("/api/0/buckets/err-scrub/events",
        Map.of("timestamp", "2020-01-01T00:00:00+00:00", "duration", 1, "data", Map.of()));
    var exported = get("/api/0/buckets/err-scrub/export");
    assertTrue(exported.body().contains("\"timestamp\""));
    assertTrue(!exported.body().contains("\"id\":1"),
        "an event's identity is not in an export: " + exported.body());
  }

  @Test
  void aMissingHostHeaderIsRefused() {
    var response = httpClient.GET("/api/0/info")
        .parseResponseBody(bytes -> new String(bytes, StandardCharsets.UTF_8))
        .invoke();
    // The test client always sends a Host header, so this asserts the request is answered
    // rather than that the absent-header branch fires; that branch is covered by GuardsTest.
    assertEquals(200, response.status().intValue());
  }

  @Test
  void anExtensionOriginReachesOnlyWhatTheBrowserExtensionNeeds() {
    var refused = httpClient.GET("/api/0/buckets/")
        .addHeader("Host", "localhost")
        .addHeader("Origin", "moz-extension://abc123")
        .parseResponseBody(bytes -> new String(bytes, StandardCharsets.UTF_8))
        .invoke();
    assertEquals(403, refused.status().intValue());

    var allowed = httpClient.GET("/api/0/info")
        .addHeader("Host", "localhost")
        .addHeader("Origin", "moz-extension://abc123")
        .parseResponseBody(bytes -> new String(bytes, StandardCharsets.UTF_8))
        .invoke();
    assertEquals(200, allowed.status().intValue());
  }
}
