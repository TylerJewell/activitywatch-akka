package io.akka.activitywatch.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The expected answers, as the original produced them.
 *
 * <p>Loaded from `corpus.json`, which `activitywatch-port/probes/probe_05_corpus.py` writes by
 * running ActivityWatch itself. Nothing in this class decides what is correct; it only reads
 * what was measured. `probe_05_corpus.py --check` fails if the file has drifted from the
 * original, so a stale expectation shows up as a red probe rather than a passing test.
 */
public final class Corpus {

  /** Offsets in the corpus are seconds from this instant. */
  public static final Instant EPOCH = Instant.parse("2026-01-01T12:00:00Z");

  public record Heartbeat(Event event) {}

  public record Decision(String action, Event event) {
    public boolean merged() {
      return action.equals("merge");
    }
  }

  public record Ingest(String name, double pulsetime, List<Event> heartbeats,
      List<Decision> decisions, List<Event> bucket) {}

  public record Idle(String name, double timeout, double pollTime, List<Double> readings,
      List<IdleRule.Ping> pings) {}

  public record Activity(String name, double pulsetime, List<String> keys, List<Event> window,
      List<Event> afk, List<Event> floodedWindow, List<Event> notAfk, List<Event> clipped,
      List<Event> activities) {}

  private final List<Ingest> ingests = new ArrayList<>();
  private final List<Idle> idles = new ArrayList<>();
  private final List<Activity> activities = new ArrayList<>();

  private static Corpus loaded;

  public static synchronized Corpus load() {
    if (loaded == null) {
      loaded = new Corpus();
    }
    return loaded;
  }

  private Corpus() {
    JsonNode root;
    try (InputStream in = Corpus.class.getClassLoader().getResourceAsStream("corpus.json")) {
      if (in == null) {
        throw new IllegalStateException(
            "corpus.json is not on the test classpath — run "
                + "activitywatch-port/probes/probe_05_corpus.py to write it");
      }
      root = new ObjectMapper().readTree(in);
    } catch (IOException e) {
      throw new IllegalStateException("corpus.json could not be read", e);
    }

    for (JsonNode c : root.get("cases")) {
      switch (c.get("kind").asText()) {
        case "ingest" -> ingests.add(new Ingest(
            c.get("name").asText(),
            c.get("pulsetime").asDouble(),
            events(c.get("heartbeats")),
            decisions(c.get("decisions")),
            events(c.get("bucket"))));
        case "idle" -> idles.add(new Idle(
            c.get("name").asText(),
            c.get("timeout").asDouble(),
            c.get("poll_time").asDouble(),
            doubles(c.get("readings")),
            pings(c.get("pings"))));
        case "activity" -> activities.add(new Activity(
            c.get("name").asText(),
            c.get("pulsetime").asDouble(),
            strings(c.get("keys")),
            events(c.get("window")),
            events(c.get("afk")),
            events(c.get("flooded_window")),
            events(c.get("not_afk")),
            events(c.get("clipped")),
            events(c.get("activities"))));
        default -> throw new IllegalStateException("unknown case kind " + c.get("kind"));
      }
    }
  }

  public List<Ingest> ingests() {
    return List.copyOf(ingests);
  }

  public List<Idle> idles() {
    return List.copyOf(idles);
  }

  public List<Activity> activities() {
    return List.copyOf(activities);
  }

  /** Seconds from the epoch, as the corpus writes them. */
  public static Instant at(double seconds) {
    return EPOCH.plus(seconds(seconds));
  }

  public static Duration seconds(double value) {
    return Duration.ofNanos(Math.round(value * 1_000_000_000L));
  }

  private static Event event(JsonNode n) {
    return Event.of(at(n.get("t").asDouble()), seconds(n.get("d").asDouble()), data(n.get("data")));
  }

  private static List<Event> events(JsonNode n) {
    List<Event> out = new ArrayList<>();
    n.forEach(e -> out.add(event(e)));
    return out;
  }

  private static List<Decision> decisions(JsonNode n) {
    List<Decision> out = new ArrayList<>();
    n.forEach(d -> out.add(new Decision(d.get("action").asText(), event(d))));
    return out;
  }

  private static List<IdleRule.Ping> pings(JsonNode n) {
    List<IdleRule.Ping> out = new ArrayList<>();
    n.forEach(p -> out.add(new IdleRule.Ping(
        at(p.get("t").asDouble()),
        seconds(p.get("d").asDouble()),
        p.get("status").asText(),
        p.get("pulsetime").asDouble())));
    return out;
  }

  private static Map<String, Object> data(JsonNode n) {
    Map<String, Object> out = new LinkedHashMap<>();
    n.fields().forEachRemaining(f -> out.put(f.getKey(), scalar(f.getValue())));
    return out;
  }

  private static Object scalar(JsonNode n) {
    if (n.isTextual()) {
      return n.asText();
    }
    if (n.isIntegralNumber()) {
      return n.asLong();
    }
    if (n.isNumber()) {
      return n.asDouble();
    }
    if (n.isBoolean()) {
      return n.asBoolean();
    }
    if (n.isObject()) {
      return data(n);
    }
    throw new IllegalStateException("unsupported value in corpus data: " + n);
  }

  private static List<Double> doubles(JsonNode n) {
    List<Double> out = new ArrayList<>();
    n.forEach(v -> out.add(v.asDouble()));
    return out;
  }

  private static List<String> strings(JsonNode n) {
    List<String> out = new ArrayList<>();
    n.forEach(v -> out.add(v.asText()));
    return out;
  }
}
