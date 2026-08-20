package io.akka.activitywatch.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.consumer.Consumer;
import io.akka.activitywatch.domain.IdleEvent;
import io.akka.activitywatch.domain.IdleRule;
import java.util.List;

/**
 * Carries a watcher's heartbeats to its bucket, in the order the watcher produced them —
 * SPEC-001 §3 rule 21.
 *
 * <p>The two heartbeats of a transition are one record in the watcher's journal, and the
 * runtime hands a journal to a consumer in the order it was written. So the heartbeat that
 * closes a stretch cannot arrive after the one that opens the next, and neither can arrive
 * without the other.
 *
 * <p>In the original the watcher queues heartbeats to an HTTP client, and nothing states what
 * happens when two are in flight at once — the ordering is whatever the transport gives.
 */
@Component(id = "idle-ping-delivery")
@Consume.FromEventSourcedEntity(IdleWatcherEntity.class)
public class IdlePingConsumer extends Consumer {

  private final ComponentClient componentClient;

  public IdlePingConsumer(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  public Effect onEvent(IdleEvent event) {
    return switch (event) {
      case IdleEvent.Started ignored -> effects().ignore();
      case IdleEvent.Observed observed -> {
        // A name derived from where the record sits in the watcher's journal, so a
        // redelivery of that record names its heartbeats the same way and the bucket
        // recognises them — SPEC-001 §3 rule 6a.
        String watcher = messageContext().eventSubject().orElseThrow();
        long sequence = messageContext().metadata().asCloudEvent().sequence().orElseThrow();
        List<IdleRule.Ping> pings = observed.pings();
        for (int i = 0; i < pings.size(); i++) {
          componentClient
              .forEventSourcedEntity(observed.bucket())
              .method(BucketEntity::heartbeat)
              .invoke(new BucketEntity.Heartbeat(pings.get(i).asEvent(), pings.get(i).pulsetime(),
                  watcher + ":" + sequence + ":" + i));
        }
        yield effects().done();
      }
    };
  }
}
