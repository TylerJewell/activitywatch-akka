package io.akka.activitywatch.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Closing the small gaps that collecting data leaves behind — SPEC-001 §3 rules 22–27.
 *
 * <p>A watcher polls, so the record of what happened has holes in it a second or two wide.
 * A hole between two records of the same thing is that thing continuing. A hole between
 * records of different things belongs to neither, and is split down the middle.
 */
public final class Flood {

  /**
   * Gaps at least this negative are left alone rather than treated as rounding.
   *
   * <p>A hundredth of a second of overlap between differing events is measurement noise; a
   * second of it means the two records genuinely disagree, and nothing here can settle which
   * is right.
   */
  private static final Duration NEGATIVE_GAP_TRIM = Duration.ofMillis(100);

  private Flood() {}

  public static List<Event> flood(List<Event> events, double pulsetimeSeconds) {
    Duration pulsetime = Event.seconds(pulsetimeSeconds);

    // Shortest first among events starting together, so the processing order is defined.
    List<Span> spans = new ArrayList<>(events.size());
    events.stream()
        .sorted(Comparator.comparing(Event::timestamp).thenComparing(Event::duration))
        .forEach(e -> spans.add(new Span(e)));

    for (int i = 0; i + 1 < spans.size(); i++) {
      Span first = spans.get(i);
      Span second = spans.get(i + 1);
      Duration gap = Duration.between(first.end(), second.start);

      if (gap.isZero()) {
        continue;
      }

      if (gap.isNegative() && first.data.equals(second.data)) {
        // Overlapping records of the same thing are one stretch, and the second collapses.
        Instant start = earlier(first.start, second.start);
        Instant end = later(first.end(), second.end());
        first.start = start;
        first.duration = Duration.between(start, end);
        second.start = end;
        second.duration = Duration.ZERO;
      } else if (gap.compareTo(NEGATIVE_GAP_TRIM.negated()) > 0 && gap.compareTo(pulsetime) <= 0) {
        Instant secondEnd = second.end();
        if (first.data.equals(second.data)) {
          // The longer neighbour keeps its start, and the other collapses into it.
          if (first.duration.compareTo(second.duration) >= 0) {
            first.duration = Duration.between(first.start, secondEnd);
            second.start = secondEnd;
            second.duration = Duration.ZERO;
          } else {
            second.start = first.start;
            second.duration = Duration.between(second.start, secondEnd);
            first.duration = Duration.ZERO;
          }
        } else {
          // Nothing says which side the gap belongs to, so neither side gets all of it.
          Instant midpoint = first.end().plus(Event.half(gap));
          first.duration = Duration.between(first.start, midpoint);
          second.start = midpoint;
          second.duration = Duration.between(midpoint, secondEnd);
        }
      }
    }

    return normalise(spans);
  }

  /**
   * A stream in which no two events overlap.
   *
   * <p>Pairwise flooding can move an event after the pair before it has been settled, so the
   * result is swept once more. Where two differing events still overlap the later one keeps
   * the overlap, which is what stops the same second being counted twice.
   */
  private static List<Event> normalise(List<Span> spans) {
    List<Span> kept = new ArrayList<>();
    for (Span span : spans) {
      if (span.duration.compareTo(Duration.ZERO) <= 0) {
        continue;
      }
      boolean settled = false;
      while (!kept.isEmpty() && kept.get(kept.size() - 1).end().isAfter(span.start)) {
        Span previous = kept.get(kept.size() - 1);
        if (previous.data.equals(span.data)) {
          previous.duration = Duration.between(previous.start, later(previous.end(), span.end()));
          settled = true;
          break;
        }
        previous.duration = Duration.between(previous.start, span.start);
        if (previous.duration.compareTo(Duration.ZERO) <= 0) {
          kept.remove(kept.size() - 1);
          continue;
        }
        kept.add(span);
        settled = true;
        break;
      }
      if (!settled) {
        kept.add(span);
      }
    }

    List<Event> out = new ArrayList<>(kept.size());
    for (Span span : kept) {
      out.add(Event.of(span.start, span.duration, span.data));
    }
    return List.copyOf(out);
  }

  private static Instant earlier(Instant a, Instant b) {
    return a.isBefore(b) ? a : b;
  }

  private static Instant later(Instant a, Instant b) {
    return a.isAfter(b) ? a : b;
  }

  /** A working copy. Flooding moves events about, and the caller's list must not move. */
  private static final class Span {
    private Instant start;
    private Duration duration;
    private final Map<String, Object> data;

    Span(Event event) {
      this.start = event.timestamp();
      this.duration = event.duration();
      this.data = event.data();
    }

    Instant end() {
      return start.plus(duration);
    }
  }
}
