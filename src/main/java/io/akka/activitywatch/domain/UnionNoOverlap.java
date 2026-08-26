package io.akka.activitywatch.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Two streams merged so the first one keeps whatever they both cover — SPEC-001 §3 R21.
 *
 * <pre>
 *   events1  | xxx    xx     xxx     |
 *   events2  |  ----     ------   -- |
 *   result   | xxx--  xx ----xxx  -- |
 * </pre>
 */
public final class UnionNoOverlap {

  private UnionNoOverlap() {}

  public static List<Event> unionNoOverlap(List<Event> events1, List<Event> events2) {
    List<Event> a = new ArrayList<>(events1);
    List<Event> b = new ArrayList<>(events2);
    List<Event> out = new ArrayList<>();

    int i = 0;
    int j = 0;
    while (i < a.size() && j < b.size()) {
      Event e1 = a.get(i);
      Event e2 = b.get(j);
      Timeslot p1 = Timeslot.of(e1);
      Timeslot p2 = Timeslot.of(e2);

      if (p1.overlaps(p2)) {
        if (!e1.timestamp().isAfter(e2.timestamp())) {
          out.add(e1);
          i++;
          // Whatever of the second event runs on past the first is kept for the next round.
          Event remainder = after(e2, e1.end());
          if (remainder != null) {
            b.set(j, remainder);
          } else {
            j++;
          }
        } else {
          Event before = before(e2, e1.timestamp());
          out.add(before);
          j++;
          Event remainder = after(e2, e1.timestamp());
          if (remainder != null) {
            b.add(j, remainder);
          }
        }
      } else if (!e1.timestamp().isAfter(e2.timestamp())) {
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

  /** The part of the event before {@code at}, or the whole event when the split misses it. */
  private static Event before(Event event, Instant at) {
    if (splits(event, at)) {
      return event.withPeriod(event.timestamp(), at);
    }
    return event;
  }

  /** The part of the event after {@code at}, or null when the split misses it. */
  private static Event after(Event event, Instant at) {
    if (splits(event, at)) {
      return event.withPeriod(at, event.end());
    }
    return null;
  }

  private static boolean splits(Event event, Instant at) {
    return event.timestamp().isBefore(at) && at.isBefore(event.end());
  }
}
