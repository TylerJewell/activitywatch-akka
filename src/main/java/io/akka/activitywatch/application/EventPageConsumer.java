package io.akka.activitywatch.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.consumer.Consumer;
import io.akka.activitywatch.domain.BucketEvent;
import io.akka.activitywatch.domain.EventSelection;

/**
 * Copies every write a bucket makes into the page for the day it belongs to.
 *
 * <p>This is what makes a bucket's history survive without the bucket carrying it. Delivery
 * is at-least-once and durable: a write that reaches the bucket reaches its page, eventually,
 * whatever happens in between, and a write delivered twice is applied once because
 * {@link io.akka.activitywatch.domain.PageState} keys on the event's identity.
 *
 * <p>Which page a change belongs to is worked out from the event's own timestamp, so a change
 * to an event written a week ago goes to the page it has always been in. The three that carry
 * no event — a bucket created, updated or deleted — are handled by naming the day themselves:
 * a deletion has to reach every page the bucket ever wrote to, so it is fanned out.
 */
@Component(id = "event-page-consumer")
@Consume.FromEventSourcedEntity(BucketEntity.class)
public class EventPageConsumer extends Consumer {

  private final ComponentClient componentClient;

  public EventPageConsumer(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  public Effect onEvent(BucketEvent event) {
    String bucket = messageContext().eventSubject().orElseThrow();
    switch (event) {
      case BucketEvent.Inserted e -> apply(
          EventSelection.pageOf(bucket, e.event().timestamp()), e);
      case BucketEvent.Extended e -> applyToEventsPage(bucket, e.id(), e);
      case BucketEvent.Replaced e -> applyToEventsPage(bucket, e.id(), e);
      case BucketEvent.Removed e -> applyToEventsPage(bucket, e.id(), e);
      case BucketEvent.Deleted e -> applyToEvery(bucket, e);
      default -> {
        // Creating or renaming a bucket changes nothing a page holds.
      }
    }
    return effects().done();
  }

  /**
   * A change that names an event but not a day.
   *
   * <p>Lengthening, replacing or removing an event names it by an identity, and the day it
   * sits in is not in the change. Rather than carry the day in every event — which would put
   * a derived value in the journal, where it can only ever go stale — the day is looked up
   * from the bucket, which still holds the events it wrote recently. A heartbeat that
   * lengthens the event before it is by far the commonest write there is, and the event it
   * lengthens is by definition one of those, so this is one read and one write.
   *
   * <p>An identity the bucket no longer holds is a change to something written long enough
   * ago to have fallen out of that window — a corrected import, an event deleted by hand. Its
   * day is no longer knowable, so the change is offered to every page the bucket has written
   * to and the ones that do not hold that identity say so and persist nothing.
   */
  private void applyToEventsPage(String bucket, long id, BucketEvent change) {
    var info = componentClient.forEventSourcedEntity(bucket)
        .method(BucketEntity::info).invoke();
    for (var event : info.recent()) {
      if (Long.valueOf(id).equals(event.id())) {
        apply(EventSelection.pageOf(bucket, event.timestamp()), change);
        return;
      }
    }
    for (String page : info.pages()) {
      apply(page, change);
    }
  }

  /** A change with no event to name: every page the bucket has written to takes it. */
  private void applyToEvery(String bucket, BucketEvent change) {
    var info = componentClient.forEventSourcedEntity(bucket)
        .method(BucketEntity::info).invoke();
    for (String page : info.pages()) {
      apply(page, change);
    }
  }

  private void apply(String page, BucketEvent change) {
    componentClient.forEventSourcedEntity(page)
        .method(EventPageEntity::apply)
        .invoke(EventPageEntity.Apply.of(change));
  }
}
