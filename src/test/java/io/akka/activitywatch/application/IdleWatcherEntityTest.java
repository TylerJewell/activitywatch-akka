package io.akka.activitywatch.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.EventSourcedTestKit;
import io.akka.activitywatch.domain.Corpus;
import io.akka.activitywatch.domain.IdleEvent;
import io.akka.activitywatch.domain.IdleRule;
import io.akka.activitywatch.domain.IdleState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 rules 12–20 — the idle rule as a thing that survives being restarted. */
class IdleWatcherEntityTest {

  private static EventSourcedTestKit<IdleState, IdleEvent, IdleWatcherEntity> kit(
      String id, double timeout, double poll) {
    var testKit = EventSourcedTestKit.of(id, IdleWatcherEntity::new);
    testKit.method(IdleWatcherEntity::start)
        .invoke(new IdleWatcherEntity.Start("aw-watcher-afk_host", timeout, poll));
    return testKit;
  }

  @Test
  void anObservationBeforeTheWatcherIsStartedIsRefused() {
    var testKit = EventSourcedTestKit.of("unstarted", IdleWatcherEntity::new);
    var result = testKit.method(IdleWatcherEntity::observe)
        .invoke(new IdleWatcherEntity.Observation(Corpus.at(0), 0));
    assertThat(result.isError()).isTrue();
  }

  @Test
  void theBitItCarriesIsPersistedRatherThanHeldInMemory() {
    // Rule 20. The transition into idle is in the journal, so replaying it gives a watcher
    // that is still idle — a restart resumes rather than starting out active.
    var testKit = kit("durable", 20, 5);
    List<Object> journal = new ArrayList<>();
    for (double idle : new double[] {0, 5, 10, 15, 20}) {
      journal.addAll(testKit.method(IdleWatcherEntity::observe)
          .invoke(new IdleWatcherEntity.Observation(Corpus.at(0), idle)).getAllEvents());
    }
    assertThat(testKit.method(IdleWatcherEntity::status).invoke().getReply().idle()).isTrue();

    IdleState replayed = IdleState.empty();
    for (Object event : journal) {
      replayed = replayed.with((IdleEvent) event);
    }
    assertThat(replayed.idle()).isTrue();
  }

  @Test
  void aTransitionProducesItsTwoHeartbeatsInOneRecord() {
    // Rule 21. The pair is one journal entry, so nothing can deliver half of it or reorder
    // the two halves.
    var testKit = kit("transition", 20, 5);
    for (double idle : new double[] {0, 5, 10, 15}) {
      testKit.method(IdleWatcherEntity::observe)
          .invoke(new IdleWatcherEntity.Observation(Corpus.at(0), idle));
    }
    var crossing = testKit.method(IdleWatcherEntity::observe)
        .invoke(new IdleWatcherEntity.Observation(Corpus.at(20), 20));
    assertThat(crossing.getAllEvents()).hasSize(1);
    var recorded = crossing.getNextEventOfType(IdleEvent.Observed.class);
    assertThat(recorded.pings().stream().map(IdleRule.Ping::status).toList())
        .containsExactly(IdleRule.PRESENT, IdleRule.AWAY);
  }

  @Test
  void everyIdleCaseInTheCorpusIsReproducedThroughTheEntity() {
    // Rules 12–19 through the durable watcher rather than the rule on its own.
    int caseNumber = 0;
    for (Corpus.Idle c : Corpus.load().idles()) {
      var testKit = kit("corpus-idle-" + caseNumber++, c.timeout(), c.pollTime());
      List<IdleRule.Ping> pings = new ArrayList<>();
      for (int i = 0; i < c.readings().size(); i++) {
        pings.addAll(testKit.method(IdleWatcherEntity::observe)
            .invoke(new IdleWatcherEntity.Observation(
                Corpus.at(c.pollTime() * i), c.readings().get(i)))
            .getReply().pings());
      }
      assertThat(pings).as("pings for %s", c.name()).isEqualTo(c.pings());
    }
  }
}
