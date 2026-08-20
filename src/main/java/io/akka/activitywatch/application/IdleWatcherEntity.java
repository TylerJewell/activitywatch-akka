package io.akka.activitywatch.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import akka.javasdk.eventsourcedentity.EventSourcedEntityContext;
import io.akka.activitywatch.domain.IdleEvent;
import io.akka.activitywatch.domain.IdleRule;
import io.akka.activitywatch.domain.IdleState;
import java.time.Instant;
import java.util.List;

/**
 * The idle rule, as something that survives being restarted — SPEC-001 §3 rules 12–21.
 *
 * <p>The original runs this in a process on the machine being watched: it polls the operating
 * system for how long there has been no input, decides whether that counts as away, and posts
 * heartbeats to a server. The reading is the part that has to happen on that machine. The
 * decision does not, and here it does not: a caller sends the reading and the rule is applied
 * against a bit that is recorded rather than remembered.
 *
 * <p>The visible consequence is at the edges. A watcher that stops and starts while the user
 * is away carries on the same away stretch; the original opens a fresh present one, because
 * a new process starts out believing the user is there.
 */
@Component(id = "idle-watcher")
public class IdleWatcherEntity extends EventSourcedEntity<IdleState, IdleEvent> {

  private final String watcherId;

  public IdleWatcherEntity(EventSourcedEntityContext context) {
    this.watcherId = context.entityId();
  }

  /**
   * @param bucket where this watcher's heartbeats go
   * @param timeoutSeconds how long without input counts as away
   * @param pollSeconds how often readings are taken, which sets the pulsetime with the timeout
   */
  public record Start(String bucket, double timeoutSeconds, double pollSeconds) {}

  /**
   * @param idleSeconds how long the machine had gone untouched when the reading was taken
   */
  public record Observation(Instant at, double idleSeconds) {}

  public record Observed(boolean idle, List<IdleRule.Ping> pings) {}

  public record Status(String watcher, String bucket, double timeoutSeconds, double pollSeconds,
      boolean idle, boolean started) {}

  @Override
  public IdleState emptyState() {
    return IdleState.empty();
  }

  public Effect<Status> start(Start command) {
    if (command.bucket() == null || command.bucket().isBlank()) {
      return effects().error("a watcher must say which bucket its heartbeats go to");
    }
    if (command.pollSeconds() <= 0) {
      return effects().error("the poll interval must be positive");
    }
    if (command.timeoutSeconds() < command.pollSeconds()) {
      // The original asserts the same thing on startup: a timeout shorter than the interval
      // between readings cannot be observed accurately.
      return effects().error("the timeout must not be shorter than the poll interval");
    }
    return effects()
        .persist(new IdleEvent.Started(command.bucket(), command.timeoutSeconds(),
            command.pollSeconds()))
        .thenReply(this::status);
  }

  /** Rules 12–19. One reading in, the heartbeats it produced out, in order. */
  public Effect<Observed> observe(Observation command) {
    IdleState state = currentState();
    if (!state.started()) {
      return effects().error("this watcher has not been started");
    }
    if (command.at() == null) {
      return effects().error("an observation must say when it was taken");
    }
    if (command.idleSeconds() < 0) {
      return effects().error("idle time cannot be negative");
    }

    IdleRule.Outcome outcome = IdleRule.observe(state.idle(), command.at(),
        command.idleSeconds(), state.timeoutSeconds(), state.pollSeconds());

    return effects()
        .persist(new IdleEvent.Observed(state.bucket(), outcome.idle(), outcome.pings()))
        .thenReply(updated -> new Observed(outcome.idle(), outcome.pings()));
  }

  public ReadOnlyEffect<Status> status() {
    return effects().reply(status(currentState()));
  }

  @Override
  public IdleState applyEvent(IdleEvent event) {
    return currentState().with(event);
  }

  private Status status(IdleState state) {
    return new Status(watcherId, state.bucket(), state.timeoutSeconds(),
        state.pollSeconds(), state.idle(), state.started());
  }
}
