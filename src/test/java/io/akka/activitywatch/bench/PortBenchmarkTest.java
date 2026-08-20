package io.akka.activitywatch.bench;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.akka.activitywatch.domain.Activities;
import io.akka.activitywatch.domain.BucketEvent;
import io.akka.activitywatch.domain.BucketState;
import io.akka.activitywatch.domain.Event;
import io.akka.activitywatch.domain.Flood;
import io.akka.activitywatch.domain.Heartbeats;
import io.akka.activitywatch.domain.IdleRule;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * What this port answers on the benchmark workload, and how long it takes.
 *
 * <p>Writes `target/bench-java.json`. `activitywatch-port/bench/compare.py` runs the original
 * over the same file and puts the two side by side; nothing here decides whether the answers
 * agree, only what this side says.
 *
 * <p>Timings are a median of five runs after a warm-up, which is enough to stop the just-in-time
 * compiler being measured instead of the code and is not enough to call this a microbenchmark
 * harness. What that means for reading the numbers is in `bench/REPORT.md` §4.
 */
public class PortBenchmarkTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int WARMUP = 3;
  private static final int RUNS = 5;

  private Instant epoch;

  @Test
  public void answersAndTimesTheBenchmarkWorkload() throws IOException {
    JsonNode workload = load();
    epoch = Instant.parse(workload.get("epoch").asText());

    ObjectNode out = JSON.createObjectNode();
    out.put("runtime", "Java " + System.getProperty("java.version"));

    ObjectNode answers = out.putObject("answers");
    ObjectNode timings = out.putObject("nanosPerOperation");

    // 1 — the merge decision on its own, the one function both sides have.
    JsonNode heartbeats = workload.get("heartbeats");
    double pulsetime = heartbeats.get("pulsetime").asDouble();
    List<Event> beats = events(heartbeats.get("beats"));
    Ingested ingested = ingest(beats, pulsetime);
    answers.set("heartbeatDecisions", strings(ingested.actions));
    answers.set("heartbeatBucket", eventsJson(ingested.bucket));
    timings.put("mergeDecision", time(() -> {
      Optional<Event> merged = Optional.empty();
      for (int i = 1; i < beats.size(); i++) {
        merged = Heartbeats.merge(beats.get(i - 1), beats.get(i), pulsetime);
      }
      return merged.hashCode();
    }, beats.size() - 1));

    // 2 — a heartbeat all the way into a bucket, decision and state together.
    timings.put("ingestOneHeartbeat",
        time(() -> ingest(beats, pulsetime).bucket.size(), beats.size()));

    // 3 — the idle rule, one reading at a time.
    JsonNode observed = workload.get("observed");
    double timeout = observed.get("timeout").asDouble();
    double poll = observed.get("poll").asDouble();
    List<Double> readings = new ArrayList<>();
    observed.get("readings").forEach(r -> readings.add(r.asDouble()));
    answers.set("pings", pingsJson(observe(readings, timeout, poll)));
    timings.put("oneIdleReading",
        time(() -> observe(readings, timeout, poll).size(), readings.size()));

    // 4 — the whole activity query, once.
    JsonNode activity = workload.get("activity");
    double activityPulse = activity.get("pulsetime").asDouble();
    List<String> keys = new ArrayList<>();
    activity.get("keys").forEach(k -> keys.add(k.asText()));
    List<Event> window = events(activity.get("window"));
    List<Event> idle = events(activity.get("idle"));
    answers.set("activities", eventsJson(Activities.query(window, idle, activityPulse, keys)));
    answers.set("floodedWindow", eventsJson(Flood.flood(window, activityPulse)));
    timings.put("wholeActivityQuery",
        time(() -> Activities.query(window, idle, activityPulse, keys).size(), 1));

    // 5 — the streams where the original's own backends disagree. Not timed.
    ArrayNode disputed = answers.putArray("disputed");
    for (JsonNode c : workload.get("disputed")) {
      Ingested result = ingest(events(c.get("beats")), c.get("pulsetime").asDouble());
      ObjectNode node = disputed.addObject();
      node.put("name", c.get("name").asText());
      node.set("actions", strings(result.actions));
      node.set("bucket", eventsJson(result.bucket));
    }

    Path target = Path.of("target", "bench-java.json");
    Files.createDirectories(target.getParent());
    Files.writeString(target, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(out));

    assertThat(ingested.bucket).isNotEmpty();
    assertThat(timings.get("mergeDecision").asDouble()).isGreaterThan(0);
  }

  /** The port's bucket after a stream of heartbeats: the entity's rules, without the runtime. */
  private record Ingested(List<String> actions, List<Event> bucket) {}

  private Ingested ingest(List<Event> beats, double pulsetime) {
    BucketState state = BucketState.empty("bench").withCreated("test", "bench", "host",
        BucketState.MAX_RETAINED);
    List<String> actions = new ArrayList<>(beats.size());
    for (Event beat : beats) {
      Optional<BucketState.Stored> last = state.lastWritten();
      Optional<Event> merged =
          last.flatMap(s -> Heartbeats.merge(s.event(), beat, pulsetime));
      if (merged.isPresent()) {
        state = state.with(
            new BucketEvent.Extended(last.get().id(), merged.get().duration(), null));
        actions.add("merge");
      } else {
        state = state.with(new BucketEvent.Inserted(state.nextId(), beat, null));
        actions.add("insert");
      }
    }
    return new Ingested(actions,
        state.inRange(null, null, null).stream().map(BucketState.Stored::event).toList());
  }

  private static List<IdleRule.Ping> observe(List<Double> readings, double timeout, double poll) {
    List<IdleRule.Ping> pings = new ArrayList<>();
    boolean idle = false;
    Instant base = Instant.EPOCH;
    for (int i = 0; i < readings.size(); i++) {
      IdleRule.Outcome outcome = IdleRule.observe(
          idle, base.plus(Event.seconds(poll * i)), readings.get(i), timeout, poll);
      idle = outcome.idle();
      pings.addAll(outcome.pings());
    }
    return pings;
  }

  /** Nanoseconds for one operation: the median of {@link #RUNS} runs, after a warm-up. */
  private static double time(java.util.function.IntSupplier work, int operations) {
    for (int i = 0; i < WARMUP; i++) {
      work.getAsInt();
    }
    double[] each = new double[RUNS];
    for (int i = 0; i < RUNS; i++) {
      long start = System.nanoTime();
      int guard = work.getAsInt();
      long elapsed = System.nanoTime() - start;
      if (guard == Integer.MIN_VALUE) {
        throw new IllegalStateException("unreachable, and here so the work is not discarded");
      }
      each[i] = (double) elapsed / operations;
    }
    java.util.Arrays.sort(each);
    return each[RUNS / 2];
  }

  private List<Event> events(JsonNode array) {
    List<Event> out = new ArrayList<>();
    array.forEach(n -> out.add(Event.of(
        epoch.plus(Event.seconds(n.get("t").asDouble())),
        Event.seconds(n.get("d").asDouble()),
        data(n.get("data")))));
    return out;
  }

  private static Map<String, Object> data(JsonNode node) {
    Map<String, Object> out = new LinkedHashMap<>();
    node.properties().forEach(e -> out.put(e.getKey(), e.getValue().isTextual()
        ? e.getValue().asText()
        : e.getValue().asDouble()));
    return out;
  }

  private ArrayNode eventsJson(List<Event> events) {
    ArrayNode array = JSON.createArrayNode();
    for (Event e : events) {
      ObjectNode node = array.addObject();
      node.put("t", round(Duration.between(epoch, e.timestamp())));
      node.put("d", round(e.duration()));
      node.set("data", JSON.valueToTree(e.data()));
    }
    return array;
  }

  private ArrayNode pingsJson(List<IdleRule.Ping> pings) {
    ArrayNode array = JSON.createArrayNode();
    for (IdleRule.Ping p : pings) {
      ObjectNode node = array.addObject();
      node.put("t", round(Duration.between(Instant.EPOCH, p.timestamp())));
      node.put("d", round(p.duration()));
      node.put("status", p.status());
      node.put("pulsetime", p.pulsetime());
    }
    return array;
  }

  private static ArrayNode strings(List<String> values) {
    ArrayNode array = JSON.createArrayNode();
    values.forEach(array::add);
    return array;
  }

  private static double round(Duration duration) {
    return Math.round(duration.toNanos() / 1_000_000d) / 1_000d;
  }

  private static JsonNode load() throws IOException {
    try (InputStream in =
        PortBenchmarkTest.class.getClassLoader().getResourceAsStream("workload.json")) {
      if (in == null) {
        throw new IllegalStateException(
            "workload.json is not on the test classpath — run "
                + "activitywatch-port/bench/workload.py to write it");
      }
      return JSON.readTree(in);
    }
  }
}
