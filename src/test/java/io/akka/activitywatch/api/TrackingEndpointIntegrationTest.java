package io.akka.activitywatch.api;

import static org.assertj.core.api.Assertions.assertThat;

import akka.http.javadsl.model.sse.ServerSentEvent;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.activitywatch.domain.Corpus;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/** SPEC-001 §5 conformance for the surface — rules 32 and 33, against a running service. */
public class TrackingEndpointIntegrationTest extends TestKitSupport {

  private void createBucket(String bucket, String type) {
    var response = httpClient.POST("/tracking/buckets/" + bucket)
        .withRequestBody(new TrackingEndpoint.CreateRequest(type, "test", "host", null))
        .responseBodyAs(TrackingEndpoint.BucketView.class)
        .invoke();
    assertThat(response.status().isSuccess()).isTrue();
  }

  private TrackingEndpoint.WrittenView beat(String bucket, double offset, double duration,
      Map<String, Object> data, double pulsetime) {
    var response = httpClient.POST("/tracking/buckets/" + bucket + "/heartbeat")
        .withRequestBody(new TrackingEndpoint.HeartbeatRequest(
            Corpus.at(offset).toString(), duration, data, pulsetime))
        .responseBodyAs(TrackingEndpoint.WrittenView.class)
        .invoke();
    assertThat(response.status().isSuccess()).isTrue();
    return response.body();
  }

  private TrackingEndpoint.EventsView events(String bucket) {
    return httpClient.GET("/tracking/buckets/" + bucket + "/events")
        .responseBodyAs(TrackingEndpoint.EventsView.class)
        .invoke()
        .body();
  }

  @Test
  public void aRunOfHeartbeatsBecomesOneEvent() {
    createBucket("window-run", "currentwindow");
    assertThat(beat("window-run", 0, 0, Map.of("app", "editor"), 10).action()).isEqualTo("insert");
    assertThat(beat("window-run", 5, 0, Map.of("app", "editor"), 10).action()).isEqualTo("merge");
    assertThat(beat("window-run", 10, 0, Map.of("app", "editor"), 10).action()).isEqualTo("merge");

    var view = events("window-run");
    assertThat(view.events()).hasSize(1);
    assertThat(view.events().get(0).duration()).isEqualTo(10.0);
    assertThat(view.complete()).isTrue();
    assertThat(view.count()).isEqualTo(1);
  }

  @Test
  public void aChangeOfApplicationStartsANewEvent() {
    createBucket("window-change", "currentwindow");
    beat("window-change", 0, 0, Map.of("app", "editor"), 10);
    beat("window-change", 5, 0, Map.of("app", "browser"), 10);
    assertThat(events("window-change").events()).hasSize(2);
  }

  @Test
  public void aHeartbeatIntoAnUnknownBucketIsRefused() {
    var response = httpClient.POST("/tracking/buckets/never-created/heartbeat")
        .withRequestBody(new TrackingEndpoint.HeartbeatRequest(
            Corpus.at(0).toString(), 0, Map.of("app", "editor"), 5))
        .invoke();
    assertThat(response.status().isSuccess()).isFalse();
  }

  @Test
  public void activitiesAddUpWhatWasOnScreenWhileTheMachineWasInUse() {
    // Rule 32, end to end: two buckets in, one list of applications out.
    createBucket("window-activities", "currentwindow");
    createBucket("afk-activities", "afkstatus");

    beat("window-activities", 0, 60, Map.of("app", "editor", "title", "a"), 5);
    beat("afk-activities", 0, 20, Map.of("status", "not-afk"), 5);
    beat("afk-activities", 20, 20, Map.of("status", "afk"), 5);
    beat("afk-activities", 40, 20, Map.of("status", "not-afk"), 5);

    var response = httpClient.POST("/tracking/activities")
        .withRequestBody(new TrackingEndpoint.ActivityRequest(
            "window-activities", "afk-activities", null, null, 5.0, List.of("app")))
        .responseBodyAs(TrackingEndpoint.ActivitiesView.class)
        .invoke();
    assertThat(response.status().isSuccess()).isTrue();
    assertThat(response.body().activities()).hasSize(1);
    assertThat(response.body().activities().get(0).data()).isEqualTo(Map.of("app", "editor"));
    assertThat(response.body().activities().get(0).duration()).isEqualTo(40.0);
    assertThat(response.body().total()).isEqualTo(40.0);
  }

  @Test
  public void everyBucketIsListedWithHowMuchItHolds() {
    createBucket("listed-bucket", "currentwindow");
    beat("listed-bucket", 0, 0, Map.of("app", "editor"), 5);

    Awaitility.await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
      var listed = httpClient.GET("/tracking/buckets")
          .responseBodyAs(TrackingEndpoint.BucketsView.class)
          .invoke()
          .body();
      assertThat(listed.buckets())
          .anySatisfy(b -> {
            assertThat(b.bucket()).isEqualTo("listed-bucket");
            assertThat(b.type()).isEqualTo("currentwindow");
            assertThat(b.events()).isEqualTo(1);
          });
    });
  }

  @Test
  public void aWatcherIsHandedEventsAsTheyAreWrittenRatherThanAskingAgain() {
    // Rule 33. The subscriber attaches first and the heartbeats follow, so what it receives
    // is what was written while it was listening — not the answer to a question it asked.
    createBucket("watched-bucket", "currentwindow");

    Thread writer = new Thread(() -> {
      try {
        Thread.sleep(1000);
        beat("watched-bucket", 0, 0, Map.of("app", "editor"), 10);
        beat("watched-bucket", 5, 0, Map.of("app", "editor"), 10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    writer.start();

    List<ServerSentEvent> received = testKit.getSelfSseRouteTester()
        .receiveFirstN("/tracking/buckets/watched-bucket/watch", 2, Duration.ofSeconds(30));

    assertThat(received).hasSize(2);
    assertThat(received.get(0).getData()).contains("\"action\":\"insert\"");
    assertThat(received.get(1).getData()).contains("\"action\":\"merge\"");
  }
}
