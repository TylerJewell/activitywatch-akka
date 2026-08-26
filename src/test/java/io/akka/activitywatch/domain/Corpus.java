package io.akka.activitywatch.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.akka.activitywatch.api.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The answers the original gave, replayed against this rebuild.
 *
 * <p>`../activitywatch-port/probes/oracle.py` calls the original 260 times and writes down what
 * came back; `corpus.json` is that file. A test here reads a case, calls the same function on
 * this side, and compares. The point of the arrangement is that the expected answers were
 * fixed before any of this code existed and cannot be edited to suit it: changing one means
 * changing what the original answers, which `oracle.py --check` refuses.
 */
public final class Corpus {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final List<Case> CASES = load();

  private Corpus() {}

  /**
   * @param answer what the original returned, absent when it raised
   * @param error the exception class the original raised, absent when it returned
   */
  public record Case(String family, String name, Map<String, Object> args, Object answer,
      String error, String message) {

    public boolean raised() {
      return error != null;
    }

    public String describe() {
      return family + " / " + name;
    }
  }

  public static List<Case> of(String family) {
    List<Case> out = new ArrayList<>();
    for (Case candidate : CASES) {
      if (candidate.family().equals(family)) {
        out.add(candidate);
      }
    }
    if (out.isEmpty()) {
      throw new AssertionError("no cases in the corpus for " + family);
    }
    return out;
  }

  public static int size() {
    return CASES.size();
  }

  public static List<String> families() {
    List<String> out = new ArrayList<>();
    for (Case candidate : CASES) {
      if (!out.contains(candidate.family())) {
        out.add(candidate.family());
      }
    }
    return out;
  }

  // ------------------------------------------------------------- reading in

  @SuppressWarnings("unchecked")
  public static List<Event> events(Object raw) {
    List<Event> out = new ArrayList<>();
    for (Object item : (List<Object>) raw) {
      out.add(event((Map<String, Object>) item));
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  public static Event event(Map<String, Object> row) {
    Event event = Event.of(instant(row.get("timestamp")),
        ((Number) row.getOrDefault("duration", 0)).doubleValue(),
        (Map<String, Object>) row.getOrDefault("data", Map.of()));
    Object id = row.get("id");
    return id == null ? event : event.withId(((Number) id).longValue());
  }

  public static Instant instant(Object raw) {
    return OffsetDateTime.parse(String.valueOf(raw)).toInstant();
  }

  @SuppressWarnings("unchecked")
  public static List<String> strings(Object raw) {
    List<String> out = new ArrayList<>();
    if (raw == null) {
      return out;
    }
    for (Object item : (List<Object>) raw) {
      out.add(String.valueOf(item));
    }
    return out;
  }

  // ------------------------------------------------------------ writing out

  /**
   * The same shape the oracle wrote, so two answers compare as text.
   *
   * <p>Comparing rendered text rather than objects is deliberate: a difference in how a
   * duration or an instant is written is a difference a caller sees, and comparing parsed
   * values would hide it.
   */
  public static Object render(Object value) {
    if (value instanceof Event event) {
      return Json.event(event);
    }
    if (value instanceof Duration duration) {
      return Json.seconds(duration);
    }
    if (value instanceof Instant instant) {
      return Json.instant(instant);
    }
    if (value instanceof List<?> list) {
      List<Object> out = new ArrayList<>(list.size());
      for (Object item : list) {
        out.add(render(item));
      }
      return out;
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        out.put(String.valueOf(entry.getKey()), render(entry.getValue()));
      }
      return out;
    }
    return value;
  }

  /** A stable text for a value, with numbers normalised so 1 and 1.0 compare equal. */
  public static String canonical(Object value) {
    return write(normalise(value));
  }

  private static Object normalise(Object value) {
    if (value instanceof Number number) {
      double d = number.doubleValue();
      // The two sides answer the same number through different types; the difference between
      // a long 1 and a double 1.0 is Jackson's, not either system's.
      return d == Math.rint(d) && Math.abs(d) < 1e15 ? (Object) (long) d : (Object) d;
    }
    if (value instanceof List<?> list) {
      List<Object> out = new ArrayList<>(list.size());
      for (Object item : list) {
        out.add(normalise(item));
      }
      return out;
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        out.put(String.valueOf(entry.getKey()), normalise(entry.getValue()));
      }
      return out;
    }
    return value;
  }

  private static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static List<Case> load() {
    try (InputStream in = Corpus.class.getResourceAsStream("/corpus.json")) {
      if (in == null) {
        throw new IllegalStateException("corpus.json is not on the test classpath");
      }
      List<Map<String, Object>> rows = MAPPER.readValue(in, List.class);
      List<Case> out = new ArrayList<>(rows.size());
      for (Map<String, Object> row : rows) {
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) row.getOrDefault("args", Map.of());
        out.add(new Case(
            String.valueOf(row.get("family")),
            String.valueOf(row.get("name")),
            args,
            row.get("answer"),
            row.get("error") == null ? null : String.valueOf(row.get("error")),
            row.get("message") == null ? null : String.valueOf(row.get("message"))));
      }
      return List.copyOf(out);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
