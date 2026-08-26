package io.akka.activitywatch.client;

import io.akka.activitywatch.domain.Event;
import io.akka.activitywatch.domain.Heartbeats;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The library a watcher talks to the server through — SPEC-001 §3 R79–R82.
 *
 * <p>The interesting part is the queued heartbeat. A watcher polls every second or so and
 * almost every poll says the same thing as the last one, so the client merges them itself
 * with the very same rule the server uses and only sends when the run it is holding has grown
 * past the commit interval, or when the run ends. That is what keeps a one-second poll from
 * being a one-second request.
 */
public class ActivityWatchClient implements AutoCloseable {

  private final String clientName;
  private final String hostname;
  private final String serverAddress;
  private final String apiKey;
  private final double commitInterval;
  private final HttpClient http;
  private final RequestQueue queue;
  private final Map<String, Event> lastHeartbeat = new ConcurrentHashMap<>();

  public ActivityWatchClient(String clientName, String host, int port, boolean testing) {
    this(clientName, host, port, testing, null,
        java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")));
  }

  public ActivityWatchClient(String clientName, String host, int port, boolean testing,
      String apiKey, java.nio.file.Path queueDirectory) {
    this.clientName = clientName;
    this.hostname = localHostname();
    this.serverAddress = "http://" + host + ":" + port;
    this.apiKey = apiKey;
    this.commitInterval = testing ? 5 : 10;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    this.queue = new RequestQueue(this, clientName, testing, queueDirectory);
  }

  public String clientName() {
    return clientName;
  }

  public String hostname() {
    return hostname;
  }

  public String serverAddress() {
    return serverAddress;
  }

  /** The bucket a watcher writes to: its own name and the machine's — R99. */
  public String ownBucket() {
    return clientName + "_" + hostname;
  }

  // ------------------------------------------------------------- requests

  public Map<String, Object> info() {
    return io.akka.activitywatch.api.Json.readObject(get("info", Map.of()));
  }

  public Map<String, Object> buckets() {
    return io.akka.activitywatch.api.Json.readObject(get("buckets/", Map.of()));
  }

  public void createBucket(String bucketId, String eventType, boolean queued) {
    if (queued) {
      queue.registerBucket(bucketId, eventType);
      return;
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("client", clientName);
    body.put("hostname", hostname);
    body.put("type", eventType);
    post("buckets/" + bucketId, body);
  }

  public void deleteBucket(String bucketId, boolean force) {
    send("DELETE", "buckets/" + bucketId + (force ? "?force=1" : ""), null);
  }

  @SuppressWarnings("unchecked")
  public List<Event> events(String bucketId, Integer limit, Instant start, Instant end) {
    Map<String, String> params = new LinkedHashMap<>();
    if (limit != null) {
      params.put("limit", String.valueOf(limit));
    }
    if (start != null) {
      params.put("start", io.akka.activitywatch.api.Json.instant(start));
    }
    if (end != null) {
      params.put("end", io.akka.activitywatch.api.Json.instant(end));
    }
    Object body = io.akka.activitywatch.api.Json.read(get("buckets/" + bucketId + "/events",
        params));
    List<Event> out = new ArrayList<>();
    for (Object item : (List<Object>) body) {
      out.add(toEvent((Map<String, Object>) item));
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  public Optional<Event> insertEvent(String bucketId, Event event) {
    String body = post("buckets/" + bucketId + "/events",
        List.of(io.akka.activitywatch.api.Json.event(event)));
    List<Object> answered = (List<Object>) io.akka.activitywatch.api.Json.read(body);
    return answered.isEmpty()
        ? Optional.empty()
        : Optional.of(toEvent((Map<String, Object>) answered.get(0)));
  }

  public void insertEvents(String bucketId, List<Event> events) {
    post("buckets/" + bucketId + "/events", io.akka.activitywatch.api.Json.events(events));
  }

  public long eventCount(String bucketId, Instant start, Instant end) {
    Map<String, String> params = new LinkedHashMap<>();
    if (start != null) {
      params.put("start", io.akka.activitywatch.api.Json.instant(start));
    }
    if (end != null) {
      params.put("end", io.akka.activitywatch.api.Json.instant(end));
    }
    return Long.parseLong(get("buckets/" + bucketId + "/events/count", params).strip());
  }

  public Object query(String query, List<Instant[]> timeperiods, String name) {
    List<String> periods = new ArrayList<>();
    for (Instant[] period : timeperiods) {
      periods.add(io.akka.activitywatch.api.Json.instant(period[0]) + "/"
          + io.akka.activitywatch.api.Json.instant(period[1]));
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("timeperiods", periods);
    body.put("query", List.of(query.split("\n")));
    String path = name == null || name.isEmpty() ? "query/" : "query/?name=" + name;
    return io.akka.activitywatch.api.Json.read(post(path, body));
  }

  public Object setting(String key) {
    return io.akka.activitywatch.api.Json.read(
        get(key == null ? "settings" : "settings/" + key, Map.of()));
  }

  public void setSetting(String key, Object value) {
    post("settings/" + key, value);
  }

  // ------------------------------------------------------------ heartbeat

  /** R79, R80. */
  public void heartbeat(String bucketId, Event event, double pulsetime, boolean queued) {
    heartbeat(bucketId, event, pulsetime, queued, commitInterval);
  }

  public void heartbeat(String bucketId, Event event, double pulsetime, boolean queued,
      double commitIntervalSeconds) {
    String endpoint = "buckets/" + bucketId + "/heartbeat?pulsetime=" + pulsetime;
    if (!queued) {
      post(endpoint, io.akka.activitywatch.api.Json.event(event));
      return;
    }

    Event held = lastHeartbeat.get(bucketId);
    if (held == null) {
      lastHeartbeat.put(bucketId, event);
      return;
    }

    Optional<Event> merged = Heartbeats.merge(held, event, pulsetime);
    if (merged.isPresent()) {
      // The threshold is the run's length *after* the merge. The original reads the same
      // field, and reads it through the object the merge changed underneath it -- so the
      // heartbeat that takes a run past the interval is the one that sends it, and the run
      // sent includes that heartbeat.
      if (merged.get().durationSeconds() >= commitIntervalSeconds) {
        queue.add(endpoint, io.akka.activitywatch.api.Json.event(merged.get()));
        lastHeartbeat.put(bucketId, event);
      } else {
        lastHeartbeat.put(bucketId, merged.get());
      }
    } else {
      queue.add(endpoint, io.akka.activitywatch.api.Json.event(held));
      lastHeartbeat.put(bucketId, event);
    }
  }

  /** What is being held back, for anything that wants to look without sending. */
  public Optional<Event> held(String bucketId) {
    return Optional.ofNullable(lastHeartbeat.get(bucketId));
  }

  // ----------------------------------------------------------- lifecycle

  public void connect() {
    queue.start();
  }

  @Override
  public void close() {
    queue.stop();
  }

  /** Waits for the server to answer, doubling the wait between tries. */
  public void waitForStart(Duration timeout) {
    Instant deadline = Instant.now().plus(timeout);
    long sleep = 100;
    while (Instant.now().isBefore(deadline)) {
      try {
        info();
        return;
      } catch (RuntimeException e) {
        try {
          Thread.sleep(sleep);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
        sleep *= 2;
      }
    }
    throw new IllegalStateException("Server at " + serverAddress + " did not start in time");
  }

  // ------------------------------------------------------------- plumbing

  String get(String endpoint, Map<String, String> params) {
    StringBuilder url = new StringBuilder(serverAddress).append("/api/0/").append(endpoint);
    if (!params.isEmpty()) {
      url.append('?');
      boolean first = true;
      for (Map.Entry<String, String> entry : params.entrySet()) {
        if (!first) {
          url.append('&');
        }
        first = false;
        url.append(java.net.URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
            .append('=')
            .append(java.net.URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
      }
    }
    return exchange(HttpRequest.newBuilder(URI.create(url.toString())).GET());
  }

  String post(String endpoint, Object body) {
    return send("POST", endpoint, io.akka.activitywatch.api.Json.write(body));
  }

  String send(String method, String endpoint, String body) {
    HttpRequest.Builder request = HttpRequest.newBuilder(
        URI.create(serverAddress + "/api/0/" + endpoint));
    HttpRequest.BodyPublisher publisher = body == null
        ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
    request.method(method, publisher);
    if (body != null) {
      request.header("Content-Type", "application/json");
    }
    return exchange(request);
  }

  private String exchange(HttpRequest.Builder request) {
    // The `Host` header is set by the HTTP client from the address being called and cannot
    // be set here; the server's host check reads exactly that value, so there is nothing to
    // add. Setting it explicitly is refused by the JDK's client as a restricted header.
    if (apiKey != null && !apiKey.isEmpty()) {
      request.header("Authorization", "Bearer " + apiKey);
    }
    try {
      HttpResponse<String> response = http.send(request.build(),
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() >= 400) {
        throw new ServerError(response.statusCode(), response.body());
      }
      return response.body();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while talking to the server", e);
    }
  }

  /** A status the server refused with, kept so the retry queue can branch on it — R82. */
  public static class ServerError extends RuntimeException {
    private final int status;

    public ServerError(int status, String body) {
      super("the server answered " + status + ": " + body);
      this.status = status;
    }

    public int status() {
      return status;
    }
  }

  @SuppressWarnings("unchecked")
  static Event toEvent(Map<String, Object> row) {
    Event event = Event.of(
        java.time.OffsetDateTime.parse(String.valueOf(row.get("timestamp"))).toInstant(),
        row.get("duration") == null ? 0d : ((Number) row.get("duration")).doubleValue(),
        (Map<String, Object>) row.getOrDefault("data", Map.of()));
    Object id = row.get("id");
    return id == null ? event : event.withId(((Number) id).longValue());
  }

  private static String localHostname() {
    try {
      return java.net.InetAddress.getLocalHost().getHostName();
    } catch (java.net.UnknownHostException e) {
      return "localhost";
    }
  }
}
