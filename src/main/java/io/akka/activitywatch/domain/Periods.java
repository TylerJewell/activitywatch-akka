package io.akka.activitywatch.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The three transforms that work on when things happened rather than on what they were —
 * SPEC-001 §3 R18–R20.
 */
public final class Periods {

  private Periods() {}

  /**
   * Every event clipped to where a filter event overlaps it — R18.
   *
   * <p>One event overlapping two filter events comes back twice. Touching at a single instant
   * is not an overlap and yields nothing; a zero-length filter strictly inside an event yields
   * a zero-length result, which is what the original's interval arithmetic does.
   */
  public static List<Event> filterPeriodIntersect(List<Event> events, List<Event> filterEvents) {
    List<Event> out = new ArrayList<>();
    forEachIntersection(sorted(events), sorted(filterEvents),
        (event, filter, overlap) -> out.add(event.withPeriod(overlap.start(), overlap.end())));
    return List.copyOf(out);
  }

  /**
   * The union of the time covered by both lists, with all data stripped — R19.
   *
   * <p>The data cannot be kept consistent across a merge of two differing events, so the
   * original clears it rather than picking one, and so does this.
   */
  public static List<Event> periodUnion(List<Event> events1, List<Event> events2) {
    List<Event> all = new ArrayList<>(events1);
    all.addAll(events2);
    all.sort(Comparator.comparing(Event::timestamp));

    List<Event> merged = new ArrayList<>();
    for (Event event : all) {
      if (merged.isEmpty()) {
        merged.add(event);
        continue;
      }
      Event last = merged.get(merged.size() - 1);
      Timeslot lastSlot = Timeslot.of(last);
      Timeslot slot = Timeslot.of(event);
      if (slot.gap(lastSlot) == null) {
        Timeslot union = slot.union(lastSlot);
        merged.set(merged.size() - 1, last.withPeriod(union.start(), union.end()));
      } else {
        merged.add(event);
      }
    }

    List<Event> out = new ArrayList<>(merged.size());
    for (Event event : merged) {
      out.add(event.withData(Map.of()));
    }
    return List.copyOf(out);
  }

  /**
   * Two sorted lists interleaved, with an event present in both appearing once — R20.
   *
   * <p>Equality here is the event's own: timestamp, duration and data. Two events sharing a
   * timestamp and a duration but differing in data both survive, the second list's first,
   * because the tie-break falls through to the final else.
   */
  public static List<Event> union(List<Event> events1, List<Event> events2) {
    List<Event> a = sortedByStartThenDuration(events1);
    List<Event> b = sortedByStartThenDuration(events2);
    List<Event> out = new ArrayList<>(a.size() + b.size());

    int i = 0;
    int j = 0;
    while (i < a.size() && j < b.size()) {
      Event e1 = a.get(i);
      Event e2 = b.get(j);
      if (e1.equals(e2)) {
        out.add(e1);
        i++;
        j++;
      } else if (e1.timestamp().isBefore(e2.timestamp())) {
        out.add(e1);
        i++;
      } else if (e1.timestamp().isAfter(e2.timestamp())) {
        out.add(e2);
        j++;
      } else if (e1.duration().compareTo(e2.duration()) < 0) {
        out.add(e1);
        i++;
      } else {
        out.add(e2);
        j++;
      }
    }
    out.addAll(a.subList(i, a.size()));
    out.addAll(b.subList(j, b.size()));
    return List.copyOf(out);
  }

  /** What a caller does with each overlapping pair. */
  private interface OnIntersection {
    void accept(Event event, Event filter, Timeslot overlap);
  }

  /**
   * Walks two sorted lists together, yielding each overlapping pair once.
   *
   * <p>Advancing is by whichever side ends first, so an event spanning several filter events
   * is yielded once per filter event.
   */
  private static void forEachIntersection(List<Event> events1, List<Event> events2,
      OnIntersection onIntersection) {
    int i = 0;
    int j = 0;
    while (i < events1.size() && j < events2.size()) {
      Event e1 = events1.get(i);
      Event e2 = events2.get(j);
      Timeslot p1 = Timeslot.of(e1);
      Timeslot p2 = Timeslot.of(e2);
      Timeslot overlap = p1.intersection(p2);
      if (overlap != null) {
        onIntersection.accept(e1, e2, overlap);
        if (!p1.end().isAfter(p2.end())) {
          i++;
        } else {
          j++;
        }
      } else if (!p1.end().isAfter(p2.start())) {
        i++;
      } else if (!p2.end().isAfter(p1.start())) {
        j++;
      } else {
        // The original logs "should be unreachable" here and advances both rather than
        // looping forever; keeping the same escape means a pathological input ends.
        i++;
        j++;
      }
    }
  }

  private static List<Event> sorted(List<Event> events) {
    List<Event> copy = new ArrayList<>(events);
    copy.sort(Comparator.comparing(Event::timestamp));
    return copy;
  }

  private static List<Event> sortedByStartThenDuration(List<Event> events) {
    List<Event> copy = new ArrayList<>(events);
    copy.sort(Comparator.comparing(Event::timestamp).thenComparing(Event::duration));
    return copy;
  }
}
