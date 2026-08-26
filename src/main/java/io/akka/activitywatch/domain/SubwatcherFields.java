package io.akka.activitywatch.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Copying a second watcher's fields onto the window stream without inventing time —
 * SPEC-001 §3 R31.
 *
 * <p>A browser or editor watcher records what it was doing while some window was in front. The
 * naive join concatenates the two streams and doubles the time; this one splits the base
 * events where the subwatcher's fields change and copies the fields onto each piece, so the
 * total time is exactly the base stream's.
 */
public final class SubwatcherFields {

  public static final String BASE_WINS = "base_wins";
  public static final String SUB_WINS = "sub_wins";

  private SubwatcherFields() {}

  public static List<Event> mergeSubwatcherFields(List<Event> baseEvents,
      List<Event> subwatcherEvents, List<String> keys, String conflict) {
    if (!BASE_WINS.equals(conflict) && !SUB_WINS.equals(conflict)) {
      throw new IllegalArgumentException(
          "conflict must be 'base_wins' or 'sub_wins', got '" + conflict + "'");
    }
    if (subwatcherEvents == null || subwatcherEvents.isEmpty() || keys == null || keys.isEmpty()) {
      return List.copyOf(baseEvents);
    }

    List<Event> subs = Aggregations.sortByTimestamp(subwatcherEvents);
    List<Event> result = new ArrayList<>();

    for (Event base : baseEvents) {
      Timeslot basePeriod = Timeslot.of(base);
      List<Event> overlapping = new ArrayList<>();
      TreeSet<Instant> boundaries = new TreeSet<>();
      boundaries.add(basePeriod.start());
      boundaries.add(basePeriod.end());

      for (Event sub : subs) {
        Timeslot subPeriod = Timeslot.of(sub);
        if (!subPeriod.start().isBefore(basePeriod.end())) {
          break;
        }
        if (!subPeriod.end().isAfter(basePeriod.start())) {
          continue;
        }
        Timeslot overlap = basePeriod.intersection(subPeriod);
        if (overlap != null) {
          overlapping.add(sub);
          boundaries.add(overlap.start());
          boundaries.add(overlap.end());
        }
      }

      if (overlapping.isEmpty()) {
        result.add(base);
        continue;
      }

      List<Instant> points = new ArrayList<>(boundaries);
      List<Event> segments = new ArrayList<>();
      for (int i = 0; i + 1 < points.size(); i++) {
        Timeslot segment = new Timeslot(points.get(i), points.get(i + 1));
        Event bestSub = null;
        Timeslot bestPeriod = null;
        for (Event sub : overlapping) {
          Timeslot subPeriod = Timeslot.of(sub);
          if (segment.intersection(subPeriod) == null) {
            continue;
          }
          // A later pulse supersedes an older overlapping one on the slice they share, so a
          // transition is not smeared backwards by a longer earlier reading.
          if (bestSub == null
              || sub.timestamp().isAfter(bestSub.timestamp())
              || (sub.timestamp().equals(bestSub.timestamp())
                  && bestPeriod != null && subPeriod.end().isAfter(bestPeriod.end()))) {
            bestSub = sub;
            bestPeriod = subPeriod;
          }
        }

        Map<String, Object> data = new LinkedHashMap<>(base.data());
        if (bestSub != null) {
          for (String key : keys) {
            if (!bestSub.data().containsKey(key)) {
              continue;
            }
            if (BASE_WINS.equals(conflict) && data.containsKey(key)) {
              continue;
            }
            data.put(key, bestSub.data().get(key));
          }
        }
        // The segment is a copy of the base event, so it carries the base's identity --
        // several segments of one base event therefore share one identity, which is what the
        // original's copy does and what a caller reading the answer sees.
        Event enriched = Event.of(segment.start(), segment.duration(), data).withId(base.id());

        if (!segments.isEmpty()) {
          Event previous = segments.get(segments.size() - 1);
          if (previous.end().equals(enriched.timestamp())
              && previous.data().equals(enriched.data())) {
            segments.set(segments.size() - 1,
                previous.withDuration(previous.duration().plus(enriched.duration())));
            continue;
          }
        }
        segments.add(enriched);
      }
      result.addAll(segments);
    }
    return List.copyOf(result);
  }
}
