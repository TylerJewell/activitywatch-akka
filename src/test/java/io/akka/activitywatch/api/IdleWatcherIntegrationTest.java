package io.akka.activitywatch.api;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.activitywatch.domain.Corpus;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 §3 rule 21 — the heartbeats a transition produces reach the bucket in the order
 * the watcher produced them, against a running service.
 */
public class IdleWatcherIntegrationTest extends TestKitSupport {

  private void startWatcher(String watcher, String bucket, double timeout, double poll) {
    var created = httpClient.POST("/tracking/buckets/" + bucket)
        .withRequestBody(new TrackingEndpoint.CreateRequest("afkstatus", "test", "host", null))
        .invoke();
    assertThat(created.status().isSuccess()).isTrue();

    var started = httpClient.POST("/tracking/watchers/" + watcher)
        .withRequestBody(new TrackingEndpoint.StartRequest(bucket, timeout, poll))
        .invoke();
    assertThat(started.status().isSuccess()).isTrue();
  }

  private void observe(String watcher, double at, double idleSeconds) {
    var response = httpClient.POST("/tracking/watchers/" + watcher + "/observe")
        .withRequestBody(new TrackingEndpoint.ObserveRequest(
            Corpus.at(at).toString(), idleSeconds))
        .invoke();
    assertThat(response.status().isSuccess()).isTrue();
  }

  @Test
  public void anIdleStretchReachesTheBucketAsOneEvent() {
    startWatcher("afk-watcher", "afk-bucket", 20, 5);

    // Readings a machine could actually produce: idle time grows one poll at a time.
    List<Double> readings = List.of(0.0, 5.0, 10.0, 15.0, 20.0, 25.0);
    for (int i = 0; i < readings.size(); i++) {
      observe("afk-watcher", 5.0 * i, readings.get(i));
    }

    Awaitility.await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
      var events = httpClient.GET("/tracking/buckets/afk-bucket/events")
          .responseBodyAs(TrackingEndpoint.EventsView.class)
          .invoke()
          .body();
      // Newest first: the idle stretch, which the last two readings lengthened rather than
      // split, and behind it the active run it closed.
      assertThat(events.events()).hasSize(2);
      assertThat(events.events().get(0).data()).isEqualTo(java.util.Map.of("status", "afk"));
      assertThat(events.events().get(0).duration()).isEqualTo(25.0);
      assertThat(events.events().get(1).data()).isEqualTo(java.util.Map.of("status", "not-afk"));
    });
  }

  @Test
  public void aWatcherThatIsAskedAgainAboutTheSameInstantDoesNotChangeItsMind() {
    startWatcher("steady-watcher", "steady-bucket", 20, 5);
    for (int i = 0; i < 4; i++) {
      observe("steady-watcher", 5.0 * i, 5.0 * i);
    }
    var status = httpClient.GET("/tracking/watchers/steady-watcher")
        .responseBodyAs(TrackingEndpoint.WatcherView.class)
        .invoke()
        .body();
    assertThat(status.idle()).isFalse();
    assertThat(status.timeoutSeconds()).isEqualTo(20.0);
    assertThat(status.pollSeconds()).isEqualTo(5.0);
  }

  @Test
  public void aTimeoutShorterThanThePollIntervalIsRefused() {
    var created = httpClient.POST("/tracking/buckets/impossible-bucket")
        .withRequestBody(new TrackingEndpoint.CreateRequest("afkstatus", "test", "host", null))
        .invoke();
    assertThat(created.status().isSuccess()).isTrue();

    var response = httpClient.POST("/tracking/watchers/impossible-watcher")
        .withRequestBody(new TrackingEndpoint.StartRequest("impossible-bucket", 2, 5))
        .invoke();
    assertThat(response.status().isSuccess()).isFalse();
  }
}
