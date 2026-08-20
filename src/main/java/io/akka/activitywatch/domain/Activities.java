package io.akka.activitywatch.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turning what was on screen into how long was spent on what — SPEC-001 §3 rules 28–31.
 *
 * <p>Four steps, and the order is the argument: work out when the machine was being used,
 * clip everything that was on screen down to those stretches, add the stretches up per
 * application, and put the longest first.
 */
public final class Activities {

  private Activities() {}

  /** The canonical query, minus the browser and category steps — see SPEC-001 §1. */
  public static List<Event> query(List<Event> window, List<Event> idle, double pulsetimeSeconds,
      List<String> keys) {
    List<Event> active = filterKeyvals(
        Flood.flood(idle, pulsetimeSeconds), "status", List.of("not-afk"));
    List<Event> clipped =
        filterPeriodIntersect(Flood.flood(window, pulsetimeSeconds), active);
    return sortByDuration(mergeEventsByKeys(clipped, keys));
  }

  /** Rule 28 — the events whose value at {@code key} is one of {@code values}. */
  public static List<Event> filterKeyvals(List<Event> events, String key, List<Object> values) {
    List<Event> out = new ArrayList<>();
    for (Event e : events) {
      Object value = e.data().get(key);
      if (value != null && values.contains(value)) {
        out.add(e);
      }
    }
    return List.copyOf(out);
  }

  /**
   * Rule 29 — each event cut down to the parts of it covered by a period.
   *
   * <p>An event overlapping two periods comes back as two. An event overlapping none
   * disappears. Both lists are walked once together, so this is linear in their combined
   * length rather than quadratic.
   */
  public static List<Event> filterPeriodIntersect(List<Event> events, List<Event> periods) {
    List<Event> subjects = sortedByTimestamp(events);
    List<Event> filters = sortedByTimestamp(periods);

    List<Event> out = new ArrayList<>();
    int s = 0;
    int f = 0;
    while (s < subjects.size() && f < filters.size()) {
      Event subject = subjects.get(s);
      Event filter = filters.get(f);
      Optional<Instant[]> overlap = intersection(subject, filter);

      if (overlap.isPresent()) {
        out.add(subject.withPeriod(overlap.get()[0], overlap.get()[1]));
        if (!subject.end().isAfter(filter.end())) {
          s++;
        } else {
          f++;
        }
      } else if (!subject.end().isAfter(filter.timestamp())) {
        s++;
      } else {
        f++;
      }
    }
    return List.copyOf(out);
  }

  /**
   * The span covered by both, or empty.
   *
   * <p>A span entirely inside the other counts even when it has no length, which is why this
   * is not simply "the later start is before the earlier end". Two spans that merely touch at
   * one instant do not count.
   */
  private static Optional<Instant[]> intersection(Event a, Event b) {
    Instant aStart = a.timestamp();
    Instant aEnd = a.end();
    Instant bStart = b.timestamp();
    Instant bEnd = b.end();

    if (!aStart.isAfter(bStart) && !bEnd.isAfter(aEnd)) {
      return Optional.of(new Instant[] {bStart, bEnd});
    }
    if (!aStart.isAfter(bStart) && bStart.isBefore(aEnd)) {
      return Optional.of(new Instant[] {bStart, aEnd});
    }
    if (aStart.isBefore(bEnd) && !bEnd.isAfter(aEnd)) {
      return Optional.of(new Instant[] {aStart, bEnd});
    }
    if (!bStart.isAfter(aStart) && !aEnd.isAfter(bEnd)) {
      return Optional.of(new Instant[] {aStart, aEnd});
    }
    return Optional.empty();
  }

  /**
   * Rule 30 — durations summed per distinct set of key values.
   *
   * <p>The result keeps the first contributing event's timestamp. An activity is a total, so
   * that timestamp says when the first of its parts happened and nothing more.
   */
  public static List<Event> mergeEventsByKeys(List<Event> events, List<String> keys) {
    if (keys.isEmpty()) {
      return List.copyOf(events);
    }

    Map<List<Object>, Event> grouped = new LinkedHashMap<>();
    for (Event event : events) {
      List<Object> composite = new ArrayList<>(keys.size());
      Map<String, Object> carried = new LinkedHashMap<>();
      for (String key : keys) {
        Object value = event.data().get(key);
        if (value != null) {
          composite.add(value);
          carried.put(key, value);
        }
      }
      Event existing = grouped.get(composite);
      grouped.put(composite, existing == null
          ? Event.of(event.timestamp(), event.duration(), carried)
          : existing.withDuration(existing.duration().plus(event.duration())));
    }
    return List.copyOf(grouped.values());
  }

  /** Rule 31 — longest first, and equal durations keep the order they arrived in. */
  public static List<Event> sortByDuration(List<Event> events) {
    List<Event> out = new ArrayList<>(events);
    out.sort(Comparator.comparing(Event::duration).reversed());
    return List.copyOf(out);
  }

  private static List<Event> sortedByTimestamp(List<Event> events) {
    List<Event> out = new ArrayList<>(events);
    out.sort(Comparator.comparing(Event::timestamp));
    return out;
  }

  /** The total time an activity list accounts for. */
  public static Duration total(List<Event> events) {
    Duration sum = Duration.ZERO;
    for (Event e : events) {
      sum = sum.plus(e.duration());
    }
    return sum;
  }
}
