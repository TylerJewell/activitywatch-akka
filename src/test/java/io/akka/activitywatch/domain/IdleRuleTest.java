package io.akka.activitywatch.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 rules 12–19 — deciding when the machine counts as untouched. */
public class IdleRuleTest {

  /** Drive the rule over a scripted sequence of readings, one per poll. */
  private static List<IdleRule.Ping> run(List<Double> readings, double timeout, double pollTime) {
    List<IdleRule.Ping> pings = new ArrayList<>();
    boolean idle = false;
    for (int i = 0; i < readings.size(); i++) {
      IdleRule.Outcome outcome = IdleRule.observe(
          idle, Corpus.at(pollTime * i), readings.get(i), timeout, pollTime);
      idle = outcome.idle();
      pings.addAll(outcome.pings());
    }
    return pings;
  }

  @Test
  public void everyPingCarriesTheTimeoutPlusThePollInterval() {
    // Rule 12.
    assertThat(run(List.of(0.0, 5.0, 10.0, 15.0, 20.0), 20, 5))
        .allSatisfy(p -> assertThat(p.pulsetime()).isEqualTo(25.0));
  }

  @Test
  public void aPingIsStampedAtTheLastInputNotAtThePoll() {
    // Rules 13 and 18. Four polls with no fresh input all name the same instant.
    assertThat(run(List.of(0.0, 5.0, 10.0, 15.0), 20, 5))
        .allSatisfy(p -> {
          assertThat(p.timestamp()).isEqualTo(Corpus.at(0));
          assertThat(p.status()).isEqualTo("not-afk");
          assertThat(p.duration()).isEqualTo(Corpus.seconds(0));
        });
  }

  @Test
  public void theThresholdIsInclusive() {
    // Rule 14.
    assertThat(run(List.of(0.0, 5.0, 10.0, 15.0, 20.0), 20, 5))
        .anySatisfy(p -> assertThat(p.status()).isEqualTo("afk"));
    assertThat(run(List.of(0.0, 5.0, 10.0, 19.999), 20, 5))
        .allSatisfy(p -> assertThat(p.status()).isEqualTo("not-afk"));
  }

  @Test
  public void becomingIdleSendsAPair() {
    // Rule 15.
    List<IdleRule.Ping> pings = run(List.of(0.0, 5.0, 10.0, 15.0, 20.0), 20, 5);
    IdleRule.Ping closing = pings.get(pings.size() - 2);
    IdleRule.Ping opening = pings.get(pings.size() - 1);
    assertThat(closing).isEqualTo(
        new IdleRule.Ping(Corpus.at(0), Corpus.seconds(0), "not-afk", 25.0));
    assertThat(opening).isEqualTo(
        new IdleRule.Ping(Corpus.at(0.001), Corpus.seconds(20), "afk", 25.0));
  }

  @Test
  public void whileIdleOnlyTheDurationGrows() {
    // Rule 16.
    List<IdleRule.Ping> pings = run(List.of(0.0, 5.0, 10.0, 15.0, 20.0, 25.0), 20, 5);
    assertThat(pings.subList(pings.size() - 2, pings.size())).isEqualTo(List.of(
        new IdleRule.Ping(Corpus.at(0.001), Corpus.seconds(20), "afk", 25.0),
        new IdleRule.Ping(Corpus.at(0.001), Corpus.seconds(25), "afk", 25.0)));
  }

  @Test
  public void returningStretchesTheIdleEventAndThenOpensAnActiveOne() {
    // Rules 17 and 19. The active event opens a millisecond after the last input, and the
    // next poll names the last input itself, which is a millisecond earlier.
    List<IdleRule.Ping> pings = run(List.of(0.0, 5.0, 10.0, 15.0, 20.0, 25.0, 0.0, 5.0), 20, 5);
    assertThat(pings.subList(pings.size() - 3, pings.size())).isEqualTo(List.of(
        new IdleRule.Ping(Corpus.at(30), Corpus.seconds(0), "afk", 25.0),
        new IdleRule.Ping(Corpus.at(30.001), Corpus.seconds(0), "not-afk", 25.0),
        new IdleRule.Ping(Corpus.at(30), Corpus.seconds(0), "not-afk", 25.0)));
  }

  @Test
  public void everyIdleCaseInTheCorpusIsReproduced() {
    // Rules 12–19 against the heartbeats the real watcher sent for the same readings.
    for (Corpus.Idle c : Corpus.load().idles()) {
      assertThat(run(c.readings(), c.timeout(), c.pollTime()))
          .as("pings for %s", c.name())
          .isEqualTo(c.pings());
    }
  }
}
