package io.akka.activitywatch.domain;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adding events up, chunking them and putting them in order — SPEC-001 §3 R22–R24.
 */
public final class Aggregations {

  private Aggregations() {}

  /**
   * Durations summed per distinct combination of the named keys — R22.
   *
   * <p>A key an event does not have contributes no position to that event's group key, so
   * every event missing the same key groups together and the result carries only the keys the
   * first contributor actually had. The result's timestamp is the first contributor's, even
   * though the original's own documentation says a merged event has none.
   */
  public static List<Event> mergeEventsByKeys(List<Event> events, List<String> keys) {
    if (keys == null || keys.isEmpty()) {
      return List.copyOf(events);
    }
    Map<List<Object>, Event> merged = new LinkedHashMap<>();
    for (Event event : events) {
      List<Object> composite = new ArrayList<>();
      for (String key : keys) {
        if (event.data().containsKey(key)) {
          composite.add(event.data().get(key));
        }
      }
      Event existing = merged.get(composite);
      if (existing == null) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (String key : keys) {
          if (event.data().containsKey(key)) {
            data.put(key, event.data().get(key));
          }
        }
        merged.put(composite, Event.of(event.timestamp(), event.duration(), data));
      } else {
        merged.put(composite, existing.withDuration(existing.duration().plus(event.duration())));
      }
    }
    return List.copyOf(merged.values());
  }

  /**
   * Adjacent events sharing a value chunked into one, each keeping its parts — R23.
   *
   * <p>Two things here read wrongly and are the original's: the walk stops at the first event
   * that lacks the key rather than skipping it, and the gap is measured against the last event
   * of the whole input list rather than the previous one — so on an in-order list the gap is a
   * large negative number and the pulsetime never refuses a chunk.
   */
  public static List<Event> chunkEventsByKey(List<Event> events, String key,
      double pulsetimeSeconds) {
    List<Chunk> chunks = new ArrayList<>();
    Duration pulsetime = Event.seconds(pulsetimeSeconds);
    for (Event event : events) {
      if (!event.data().containsKey(key)) {
        break;
      }
      Duration sinceLast = Duration.ofSeconds(999_999_999L);
      if (!chunks.isEmpty()) {
        Event lastOfInput = events.get(events.size() - 1);
        sinceLast = Duration.between(lastOfInput.end(), event.timestamp());
      }
      Chunk open = chunks.isEmpty() ? null : chunks.get(chunks.size() - 1);
      if (open != null
          && java.util.Objects.equals(open.value, event.data().get(key))
          && sinceLast.compareTo(pulsetime) < 0) {
        open.duration = open.duration.plus(event.duration());
        open.subevents.add(event);
      } else {
        Chunk chunk = new Chunk();
        chunk.value = event.data().get(key);
        chunk.start = event.timestamp();
        chunk.duration = event.duration();
        chunk.subevents.add(event);
        chunks.add(chunk);
      }
    }

    List<Event> out = new ArrayList<>(chunks.size());
    for (Chunk chunk : chunks) {
      Map<String, Object> data = new LinkedHashMap<>();
      data.put(key, chunk.value);
      data.put("subevents", List.copyOf(chunk.subevents));
      out.add(Event.of(chunk.start, chunk.duration, data));
    }
    return List.copyOf(out);
  }

  public static List<Event> sortByTimestamp(List<Event> events) {
    List<Event> copy = new ArrayList<>(events);
    copy.sort(Comparator.comparing(Event::timestamp));
    return List.copyOf(copy);
  }

  public static List<Event> sortByDuration(List<Event> events) {
    List<Event> copy = new ArrayList<>(events);
    copy.sort(Comparator.comparing(Event::duration).reversed());
    return List.copyOf(copy);
  }

  /**
   * The first {@code count} events, and for a negative count all but the last {@code |count|} —
   * which is what the original's list slice does with one.
   */
  public static List<Event> limitEvents(List<Event> events, int count) {
    int end = count < 0 ? Math.max(0, events.size() + count) : Math.min(count, events.size());
    return List.copyOf(events.subList(0, end));
  }

  public static Duration sumDurations(List<Event> events) {
    double total = 0;
    for (Event event : events) {
      total += event.durationSeconds();
    }
    return Event.seconds(total);
  }

  public static List<Event> concat(List<Event> events1, List<Event> events2) {
    List<Event> out = new ArrayList<>(events1);
    out.addAll(events2);
    return List.copyOf(out);
  }

  private static final class Chunk {
    private Object value;
    private java.time.Instant start;
    private Duration duration;
    private final List<Event> subevents = new ArrayList<>();
  }
}
