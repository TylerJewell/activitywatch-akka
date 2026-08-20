package io.akka.activitywatch.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 rules 22–27 — closing the gaps that data collection leaves behind. */
public class FloodTest {

  private static Event at(double offset, double duration, Map<String, Object> data) {
    return Event.of(Corpus.at(offset), Corpus.seconds(duration), data);
  }

  private static List<List<Object>> shape(List<Event> events) {
    List<List<Object>> out = new ArrayList<>();
    for (Event e : events) {
      out.add(List.of(
          (double) java.time.Duration.between(Corpus.EPOCH, e.timestamp()).toMillis() / 1000,
          (double) e.duration().toMillis() / 1000,
          e.data()));
    }
    return out;
  }

  @Test
  public void aSmallGapBetweenEqualDataCloses() {
    // Rule 22, both orderings of which neighbour is longer.
    assertThat(shape(Flood.flood(List.of(at(0, 10, Map.of("a", 1L)), at(12, 3, Map.of("a", 1L))), 5)))
        .isEqualTo(List.of(List.of(0.0, 15.0, Map.of("a", 1L))));
    assertThat(shape(Flood.flood(List.of(at(0, 3, Map.of("a", 1L)), at(5, 10, Map.of("a", 1L))), 5)))
        .isEqualTo(List.of(List.of(0.0, 15.0, Map.of("a", 1L))));
  }

  @Test
  public void aSmallGapBetweenDifferingDataIsSplitDownTheMiddle() {
    // Rule 23.
    assertThat(shape(Flood.flood(List.of(at(0, 10, Map.of("a", 1L)), at(12, 3, Map.of("a", 2L))), 5)))
        .isEqualTo(List.of(List.of(0.0, 11.0, Map.of("a", 1L)), List.of(11.0, 4.0, Map.of("a", 2L))));
  }

  @Test
  public void aGapWiderThanThePulsetimeStaysOpen() {
    // Rule 24.
    assertThat(shape(Flood.flood(List.of(at(0, 10, Map.of("a", 1L)), at(20, 3, Map.of("a", 1L))), 5)))
        .isEqualTo(List.of(List.of(0.0, 10.0, Map.of("a", 1L)), List.of(20.0, 3.0, Map.of("a", 1L))));
  }

  @Test
  public void overlapsAreAbsorbedOrTrimmed() {
    // Rule 25.
    assertThat(shape(Flood.flood(List.of(at(0, 10, Map.of("a", 1L)), at(5, 10, Map.of("a", 1L))), 5)))
        .isEqualTo(List.of(List.of(0.0, 15.0, Map.of("a", 1L))));
    assertThat(shape(Flood.flood(List.of(at(0, 10, Map.of("a", 1L)), at(5, 10, Map.of("a", 2L))), 5)))
        .isEqualTo(List.of(List.of(0.0, 5.0, Map.of("a", 1L)), List.of(5.0, 10.0, Map.of("a", 2L))));
  }

  @Test
  public void floodingLeavesItsInputAlone() {
    // Rule 26.
    List<Event> source = new ArrayList<>(List.of(at(0, 10, Map.of("a", 1L)), at(12, 3, Map.of("a", 1L))));
    Flood.flood(source, 5);
    assertThat(shape(source))
        .isEqualTo(List.of(List.of(0.0, 10.0, Map.of("a", 1L)), List.of(12.0, 3.0, Map.of("a", 1L))));
  }

  @Test
  public void anEmptyOrSingleListComesBackUnchanged() {
    assertThat(Flood.flood(List.of(), 5)).isEmpty();
    assertThat(Flood.flood(List.of(at(0, 10, Map.of("a", 1L))), 5))
        .isEqualTo(List.of(at(0, 10, Map.of("a", 1L))));
  }

  @Test
  public void everyFloodedStageInTheCorpusIsReproduced() {
    // Rules 22–25 and 27, against what the original produced for the same input.
    for (Corpus.Activity c : Corpus.load().activities()) {
      assertThat(Flood.flood(c.window(), c.pulsetime()))
          .as("flooded window events for %s", c.name())
          .isEqualTo(c.floodedWindow());
    }
  }
}
