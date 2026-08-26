package io.akka.activitywatch.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.http.StrictResponse;
import akka.javasdk.testkit.TestKitSupport;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The route another module posts a notification to — SPEC-001 §3 R142.
 *
 * <p>Driven over HTTP because the statuses are the whole contract: a module that gets a 429
 * backs off and retries, and one that gets a 400 does not.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotifyEndpointIntegrationTest extends TestKitSupport {

  private StrictResponse<String> post(String path, Object body) {
    return httpClient.POST(path)
        .addHeader("Host", "localhost")
        .withRequestBody(body)
        .parseResponseBody(bytes -> new String(bytes, StandardCharsets.UTF_8))
        .invoke();
  }

  private StrictResponse<String> get(String path) {
    return httpClient.GET(path)
        .addHeader("Host", "localhost")
        .parseResponseBody(bytes -> new String(bytes, StandardCharsets.UTF_8))
        .invoke();
  }

  @Test
  @Order(1)
  void thereIsNoRouteUntilTheServiceIsRunning() {
    assertEquals(404, post("/notify", Map.of("title", "t", "message", "m"))
        .status().intValue(),
        "a module that posts to a service nobody started gets nothing through, which is "
            + "what a refused connection means on the original");
    assertEquals(200, get("/api/0/notify").status().intValue());
    assertTrue(get("/api/0/notify").body().contains("\"running\":false"));
  }

  @Test
  @Order(2)
  void aNotificationPostedByAnotherModuleIsQueuedAndShown() {
    assertEquals(200, post("/api/0/notify/start", Map.of()).status().intValue());
    assertEquals(200,
        post("/notify", Map.of("title", "Backup done", "message", "Synced 1,234 events",
            "watcher", "my-script")).status().intValue(),
        "`watcher` is accepted as another name for `sender`");

    String shown = "";
    for (int attempt = 0; attempt < 80 && !shown.contains("Backup done"); attempt++) {
      shown = get("/api/0/notify").body();
      if (!shown.contains("Backup done")) {
        try {
          Thread.sleep(250);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
    assertTrue(shown.contains("Backup done (my-script)"), shown);
  }

  @Test
  @Order(3)
  void aBodyThatIsNotANotificationIsRefused() {
    assertEquals(400, post("/notify", Map.of("title", "only a title")).status().intValue());
    assertEquals(400, post("/notify", Map.of("message", "only a message"))
        .status().intValue());
    assertEquals(400, post("/notify", "not an object at all").status().intValue());
  }

  @Test
  @Order(4)
  void aBodyTooLargeToBeANotificationIsRefusedWithoutBeingRead() {
    assertEquals(400, post("/notify",
        Map.of("title", "t", "message", "x".repeat(70_000))).status().intValue());
  }

  @Test
  @Order(5)
  void tenWaitingIsWhereACallerIsToldToBackOff() {
    int refused = 0;
    for (int i = 0; i < 40; i++) {
      if (post("/notify", Map.of("title", "t" + i, "message", "m")).status().intValue()
          == 429) {
        refused++;
      }
    }
    assertTrue(refused > 0,
        "the queue is drained a second apart, so forty at once cannot all be taken");
    assertEquals(200, post("/api/0/notify/stop", Map.of()).status().intValue());
  }
}
