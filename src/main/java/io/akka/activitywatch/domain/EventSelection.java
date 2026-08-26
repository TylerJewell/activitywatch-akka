package io.akka.activitywatch.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which events a range asks for, and what shape they come back in —
 * SPEC-001 §3 R33–R36.
 *
 * <p>Here rather than in whichever component holds the events, because two of them do: a
 * bucket keeps the most recent events so a heartbeat has something to compare against and a
 * caller can read what it just wrote, and a page keeps a day of history. A range that spans
 * both is answered from both, and the rounding, the clipping, the ordering and the limit have
 * to be applied once, after the two have been put together — applying them twice would clip
 * an already-clipped event and limit an already-limited list.
 */
public final class EventSelection {

  private EventSelection() {}

  /** R33: every event overlapping the range, uncut and in the order it was written. */
  public static List<Event> overlapping(List<Event> events, Instant from, Instant to) {
    List<Event> selected = new ArrayList<>();
    for (Event event : events) {
      if (overlaps(event, from, to)) {
        selected.add(event);
      }
    }
    return selected;
  }

  public static boolean overlaps(Event event, Instant from, Instant to) {
    boolean afterStart = from == null || !from.isAfter(event.end());
    boolean beforeEnd = to == null || !event.timestamp().isAfter(to);
    return afterStart && beforeEnd;
  }

  /**
   * R33–R36: the answer a caller gets.
   *
   * <p>The range's edges are rounded outwards first, then events are selected, clipped to it,
   * ordered newest first and limited. Duplicates by identity are dropped, because an event
   * that has been written to a page and is still in the bucket's recent window arrives twice.
   *
   * @param limit negative for no limit, zero for nothing
   */
  public static List<Event> answer(List<Event> events, Instant from, Instant to, int limit) {
    if (limit == 0) {
      return List.of();
    }
    Instant start = from == null ? null : floorToMillis(from);
    Instant end = to == null ? null : ceilToMillis(to);

    Map<Object, Event> byIdentity = new LinkedHashMap<>();
    List<Event> unidentified = new ArrayList<>();
    for (Event event : events) {
      if (!overlaps(event, start, end)) {
        continue;
      }
      Event clipped = clip(event, start, end);
      if (event.id() == null) {
        unidentified.add(clipped);
      } else {
        byIdentity.putIfAbsent(event.id(), clipped);
      }
    }

    List<Event> selected = new ArrayList<>(byIdentity.values());
    selected.addAll(unidentified);
    // Ascending and then reversed, not descending: the two differ for events sharing a
    // timestamp, and the original sorts this way round.
    selected.sort(Comparator.comparing(Event::timestamp));
    java.util.Collections.reverse(selected);

    if (limit > 0 && limit < selected.size()) {
      selected = selected.subList(0, limit);
    }
    return List.copyOf(selected);
  }

  /** R37: counting uses the same selection, without the rounding and without a limit. */
  public static long count(List<Event> events, Instant from, Instant to) {
    java.util.Set<Object> seen = new java.util.HashSet<>();
    long total = 0;
    for (Event event : events) {
      if (!overlaps(event, from, to)) {
        continue;
      }
      if (event.id() == null || seen.add(event.id())) {
        total++;
      }
    }
    return total;
  }

  /** Newest first, uncut, with duplicates by identity dropped. */
  public static List<Event> newestFirst(List<Event> events) {
    return answer(events, null, null, -1);
  }

  /** R35. */
  public static Event clip(Event event, Instant start, Instant end) {
    Instant from = event.timestamp();
    Instant to = event.end();
    if (start != null && from.isBefore(start)) {
      from = start;
    }
    if (end != null && to.isAfter(end)) {
      to = end;
    }
    if (from.equals(event.timestamp()) && to.equals(event.end())) {
      return event;
    }
    return event.withPeriod(from, to);
  }

  /** R34: down to the millisecond. */
  public static Instant floorToMillis(Instant instant) {
    return Event.truncateToMillis(instant);
  }

  /** R34: up to the next millisecond, carrying into the next second when it overflows. */
  public static Instant ceilToMillis(Instant instant) {
    long micros = instant.getNano() / 1000L;
    long milliseconds = 1 + micros / 1000L;
    long secondOffset = milliseconds / 1000L;
    long microseconds = (1000L * milliseconds) % 1_000_000L;
    return instant.minusNanos(instant.getNano())
        .plusNanos(microseconds * 1000L)
        .plusSeconds(secondOffset);
  }

  /**
   * Which page an event belongs to: its bucket and the UTC day it starts in.
   *
   * <p>A day, because it is the unit a person reads their own history in and because it
   * bounds a page at what one machine can produce in a day — a few hundred kilobytes for a
   * watcher that changes what it is looking at every few seconds.
   *
   * <p>The two halves are joined with an underscore. An entity id may not contain the
   * characters the runtime reserves for its own addressing, and a bucket id may itself
   * contain underscores — {@code aw-watcher-window_hostname} is the usual shape — so the id
   * is split from the end, where the day is always ten characters.
   */
  public static String pageOf(String bucketId, Instant timestamp) {
    return bucketId + "_" + java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
        .withZone(java.time.ZoneOffset.UTC).format(timestamp);
  }

  /** Every page a range could touch, oldest first. */
  public static List<String> pagesFor(String bucketId, Instant from, Instant to,
      List<String> known) {
    if (from == null && to == null) {
      return List.copyOf(known);
    }
    // An event starting the day before the range can still reach into it, so the first page
    // considered is the one before the range's own.
    String first = from == null ? null : pageOf(bucketId, from.minus(java.time.Duration.ofDays(1)));
    String last = to == null ? null : pageOf(bucketId, to);
    List<String> wanted = new ArrayList<>();
    for (String page : known) {
      if ((first == null || page.compareTo(first) >= 0)
          && (last == null || page.compareTo(last) <= 0)) {
        wanted.add(page);
      }
    }
    return List.copyOf(wanted);
  }
}
