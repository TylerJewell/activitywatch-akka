package io.akka.activitywatch.api;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCode;
import akka.http.javadsl.model.StatusCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.akka.activitywatch.domain.Event;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The wire shapes the API answers with.
 *
 * <p>Two of them are Python's rather than Java's, because a caller written against the
 * original reads them: an instant is `2020-01-01T00:00:00+00:00` — a `+00:00` offset, not a
 * `Z`, and six digits of fraction or none at all — and a duration is a number of seconds.
 */
public final class Json {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Json() {}

  public static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("could not write a response", e);
    }
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> readObject(String text) {
    try {
      return MAPPER.readValue(text, Map.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("not a JSON object", e);
    }
  }

  public static Object read(String text) {
    try {
      return MAPPER.readValue(text, Object.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("not JSON", e);
    }
  }

  public static HttpResponse respond(StatusCode status, Object body) {
    return HttpResponses.jsonBytes(status, write(body));
  }

  /**
   * The same value with every object's keys in alphabetical order.
   *
   * <p>Three of the original's routes — the query, and the two that read settings — build
   * their answer with Flask's `jsonify`, which sorts. The rest go through the REST framework
   * and keep the order they were built in, so an event read from `/events` begins `id` and
   * the same event read from `/query/` begins `data`. Both orders are what a caller of the
   * original sees, and both are reproduced.
   */
  public static Object sortKeys(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> out = new java.util.TreeMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        out.put(String.valueOf(entry.getKey()), sortKeys(entry.getValue()));
      }
      return out;
    }
    if (value instanceof List<?> list) {
      List<Object> out = new java.util.ArrayList<>(list.size());
      for (Object item : list) {
        out.add(sortKeys(item));
      }
      return out;
    }
    return value;
  }

  public static HttpResponse ok(Object body) {
    return respond(StatusCodes.OK, body);
  }

  public static HttpResponse message(StatusCode status, String message) {
    return respond(status, Map.of("message", message));
  }

  /**
   * The shape every event takes on the wire, ordered as the original orders it.
   *
   * <p>An event's data can itself hold events — `chunk_events_by_key` puts the events it
   * chunked under `subevents` — so the data is walked rather than handed over whole.
   */
  public static Map<String, Object> event(Event event) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", event.id());
    out.put("timestamp", instant(event.timestamp()));
    out.put("duration", event.durationSeconds());
    out.put("data", plain(event.data()));
    return out;
  }

  private static Object plain(Object value) {
    if (value instanceof Event nested) {
      return event(nested);
    }
    if (value instanceof List<?> list) {
      List<Object> out = new ArrayList<>(list.size());
      for (Object item : list) {
        out.add(plain(item));
      }
      return out;
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        out.put(String.valueOf(entry.getKey()), plain(entry.getValue()));
      }
      return out;
    }
    if (value instanceof Duration duration) {
      return seconds(duration);
    }
    if (value instanceof Instant at) {
      return instant(at);
    }
    return value;
  }

  public static List<Object> events(List<Event> events) {
    List<Object> out = new ArrayList<>(events.size());
    for (Event event : events) {
      out.add(event(event));
    }
    return out;
  }

  /** Python's `datetime.isoformat()`: an offset rather than a `Z`, microseconds or nothing. */
  public static String instant(Instant instant) {
    var time = instant.atOffset(ZoneOffset.UTC);
    String date = String.format("%04d-%02d-%02dT%02d:%02d:%02d",
        time.getYear(), time.getMonthValue(), time.getDayOfMonth(),
        time.getHour(), time.getMinute(), time.getSecond());
    if (time.getNano() != 0) {
      date = date + String.format(".%06d", time.getNano() / 1000);
    }
    return date + "+00:00";
  }

  public static double seconds(Duration duration) {
    return duration.toNanos() / 1_000_000_000d;
  }

  /** Kept separate so the content type is written in exactly one place. */
  static final class HttpResponses {
    private HttpResponses() {}

    static HttpResponse jsonBytes(StatusCode status, String body) {
      return akka.javasdk.http.HttpResponses.of(status, ContentTypes.APPLICATION_JSON,
          body.getBytes(StandardCharsets.UTF_8));
    }
  }
}
