package io.akka.activitywatch.api;

import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.annotations.http.Put;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import com.typesafe.config.Config;
import io.akka.activitywatch.application.BucketEntity;
import io.akka.activitywatch.application.BucketsView;
import io.akka.activitywatch.application.ServerApi;
import io.akka.activitywatch.domain.Event;
import io.akka.activitywatch.domain.query.QueryException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The API the original publishes, route for route — SPEC-001 §3 R42–R64.
 *
 * <p>Three routes answer 500 for inputs a caller can send, and they do so on purpose: the
 * original's do, and a port that answered better would give a different answer to the same
 * call. §4 OD-3 says which three and why. Each is marked where it happens.
 *
 * <p>Every route is guarded by the host-header check (R61) and the extension-origin narrowing
 * (R62), which run first because the original's run before its handlers do.
 */
@HttpEndpoint("/api/0")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class ApiEndpoint extends AbstractHttpEndpoint {

  private final ComponentClient componentClient;
  private final ServerApi api;
  private final Guards guards;

  public ApiEndpoint(ComponentClient componentClient, Config config) {
    this.componentClient = componentClient;
    this.api = ServiceWiring.serverApi(componentClient, config);
    this.guards = ServiceWiring.guards(config);
  }

  // ------------------------------------------------------------------ info

  @Get("/info")
  public HttpResponse info() {
    return guard("GET", Guards.segments("info")).orElseGet(() -> Json.ok(api.info()));
  }

  // --------------------------------------------------------------- buckets

  @Get("/buckets/")
  public HttpResponse buckets() {
    return guard("GET", Guards.segments("buckets")).orElseGet(() -> Json.ok(api.buckets()));
  }

  @Get("/buckets/{bucketId}")
  public HttpResponse bucket(String bucketId) {
    return guard("GET", Guards.segments("buckets", bucketId)).orElseGet(() -> {
      HttpResponse missing = requireBucket(bucketId);
      return missing != null ? missing : Json.ok(api.bucketMetadata(bucketId));
    });
  }

  /** R44. A body without `type`, `client` or `hostname` answers 500, as the original's does. */
  @Post("/buckets/{bucketId}")
  public HttpResponse createBucket(String bucketId, HttpEntity.Strict raw) {
    return guard("POST", Guards.segments("buckets", bucketId)).orElseGet(() -> {
      Map<String, Object> data = object(raw);
      // OD-3: the original reads these three keys without a guard and lets the KeyError out.
      String type = required(data, "type");
      String client = required(data, "client");
      String hostname = required(data, "hostname");
      boolean created = api.createBucket(bucketId, type, client, hostname, null, null);
      return created
          ? Json.ok(Map.of())
          : akka.javasdk.http.HttpResponses.of(StatusCodes.NOT_MODIFIED,
              akka.http.javadsl.model.ContentTypes.APPLICATION_JSON, new byte[0]);
    });
  }

  /**
   * R45. This route cannot succeed on the original: its handler calls the storage layer with a
   * parameter name no backend accepts, and every call raises. The same input gets the same
   * answer here — §4 OD-3.
   */
  @Put("/buckets/{bucketId}")
  public HttpResponse updateBucket(String bucketId, HttpEntity.Strict raw) {
    return guard("PUT", Guards.segments("buckets", bucketId)).orElseGet(() -> {
      HttpResponse missing = requireBucket(bucketId);
      if (missing != null) {
        return missing;
      }
      Map<String, Object> data = cast(Json.readObject(raw.getData().utf8String()));
      for (String key : List.of("type", "client", "hostname", "data")) {
        if (!data.containsKey(key)) {
          throw new IllegalStateException("KeyError: '" + key + "'");
        }
      }
      throw new IllegalStateException(
          "PeeweeStorage.update_bucket() got an unexpected keyword argument 'type'");
    });
  }

  /** R46. */
  @Delete("/buckets/{bucketId}")
  public HttpResponse deleteBucket(String bucketId) {
    return guard("DELETE", Guards.segments("buckets", bucketId)).orElseGet(() -> {
      HttpResponse missing = requireBucket(bucketId);
      if (missing != null) {
        return missing;
      }
      if (!api.testing()
          && !"1".equals(requestContext().queryParams().getString("force").orElse(null))) {
        return Json.respond(StatusCodes.UNAUTHORIZED, ordered(
            "type", "DeleteBucketUnauthorized",
            "message", "Deleting buckets is only permitted if aw-server is running in testing "
                + "mode or if ?force=1"));
      }
      api.deleteBucket(bucketId);
      return Json.ok(Map.of());
    });
  }

  // ---------------------------------------------------------------- events

  /** R49. An unparseable instant answers 500, as the original's does — §4 OD-3. */
  @Get("/buckets/{bucketId}/events")
  public HttpResponse events(String bucketId) {
    return guard("GET", Guards.segments("buckets", bucketId, "events")).orElseGet(() -> {
      HttpResponse missing = requireBucket(bucketId);
      if (missing != null) {
        return missing;
      }
      int limit = requestContext().queryParams().getInteger("limit").orElse(-1);
      return Json.ok(Json.events(api.events(bucketId, limit,
          instantParam("start"), instantParam("end"))));
    });
  }

  /** R48. */
  @Post("/buckets/{bucketId}/events")
  public HttpResponse createEvents(String bucketId, HttpEntity.Strict raw) {
    return guard("POST", Guards.segments("buckets", bucketId, "events")).orElseGet(() -> {
      HttpResponse missing = requireBucket(bucketId);
      if (missing != null) {
        return missing;
      }
      Object body = value(raw);
      List<Event> events = new ArrayList<>();
      if (body instanceof Map<?, ?> one) {
        events.add(toEvent(cast(one)));
      } else if (body instanceof List<?> many) {
        for (Object item : many) {
          if (!(item instanceof Map<?, ?> row)) {
            return Json.message(StatusCodes.BAD_REQUEST, "Bad Request");
          }
          events.add(toEvent(cast(row)));
        }
      } else {
        return Json.message(StatusCodes.BAD_REQUEST, "Bad Request");
      }
      return Json.ok(Json.events(api.createEvents(bucketId, events)));
    });
  }

  /** R50: a bare integer, not an object. */
  @Get("/buckets/{bucketId}/events/count")
  public HttpResponse eventCount(String bucketId) {
    return guard("GET", Guards.segments("buckets", bucketId, "events", "count")).orElseGet(() -> {
      HttpResponse missing = requireBucket(bucketId);
      if (missing != null) {
        return missing;
      }
      return Json.ok(api.eventCount(bucketId, instantParam("start"), instantParam("end")));
    });
  }

  /** R51: an event that is not there answers 404 with the body `null`. */
  @Get("/buckets/{bucketId}/events/{eventId}")
  public HttpResponse event(String bucketId, long eventId) {
    return guard("GET", Guards.segments("buckets", bucketId, "events", String.valueOf(eventId))).orElseGet(() -> {
      HttpResponse missing = requireBucket(bucketId);
      if (missing != null) {
        return missing;
      }
      Optional<Event> event = api.event(bucketId, eventId);
      return event.map(value -> Json.ok(Json.event(value)))
          .orElseGet(() -> Json.respond(StatusCodes.NOT_FOUND, null));
    });
  }

  /** R52: deleting an event that is not there is not an error. */
  @Delete("/buckets/{bucketId}/events/{eventId}")
  public HttpResponse deleteEvent(String bucketId, long eventId) {
    return guard("DELETE", Guards.segments("buckets", bucketId, "events", String.valueOf(eventId))).orElseGet(() -> {
      HttpResponse missing = requireBucket(bucketId);
      if (missing != null) {
        return missing;
      }
      // A row count, not a boolean: the default backend answers with what its
      // DELETE returned, so nothing deleted is 0 and one deleted is 1.
      return Json.ok(Map.of("success", api.deleteEvent(bucketId, eventId) ? 1 : 0));
    });
  }

  /** R53. A pulsetime that is not a number answers 500, as the original's does — §4 OD-3. */
  @Post("/buckets/{bucketId}/heartbeat")
  public HttpResponse heartbeat(String bucketId, HttpEntity.Strict raw) {
    return guard("POST", Guards.segments("buckets", bucketId, "heartbeat")).orElseGet(() -> {
      HttpResponse missing = requireBucket(bucketId);
      if (missing != null) {
        return missing;
      }
      Optional<String> pulse = requestContext().queryParams().getString("pulsetime");
      if (pulse.isEmpty()) {
        return Json.message(StatusCodes.BAD_REQUEST, "Missing required parameter pulsetime");
      }
      Map<String, Object> body = object(raw);
      if (body.get("timestamp") == null) {
        return Json.respond(StatusCodes.BAD_REQUEST, ordered(
            "errors", Map.of("timestamp", "'timestamp' is a required property"),
            "message", "Input payload validation failed"));
      }
      double pulsetime;
      try {
        pulsetime = Double.parseDouble(pulse.get());
      } catch (NumberFormatException e) {
        // OD-3: the original's `float()` raises and the route answers 500. Rethrown as a
        // state error because the runtime turns an illegal *argument* into a 400, which
        // would be a different answer to the same call.
        throw new IllegalStateException("could not convert string to float: '"
            + pulse.get() + "'");
      }
      Event written = api.heartbeat(bucketId, toEvent(body), pulsetime);
      return Json.ok(Json.event(written));
    });
  }

  // ----------------------------------------------------------------- query

  /** R55, R56. */
  @Post("/query/")
  public HttpResponse query(HttpEntity.Strict raw) {
    return guard("POST", Guards.segments("query")).orElseGet(() -> {
      Map<String, Object> body = object(raw);
      if (!(body.get("timeperiods") instanceof List<?> periods)) {
        return Json.respond(StatusCodes.BAD_REQUEST, ordered(
            "errors", Map.of("timeperiods", "'timeperiods' is a required property"),
            "message", "Input payload validation failed"));
      }
      if (!(body.get("query") instanceof List<?> lines)) {
        return Json.respond(StatusCodes.BAD_REQUEST, ordered(
            "errors", Map.of("query", "'query' is a required property"),
            "message", "Input payload validation failed"));
      }
      String name = requestContext().queryParams().getString("name").orElse("");
      List<String> timeperiods = periods.stream().map(String::valueOf).toList();
      List<String> query = lines.stream().map(String::valueOf).toList();
      try {
        List<Object> answers = api.query2(name, query, timeperiods);
        return Json.ok(Json.sortKeys(
            answers.stream().map(ApiEndpoint::renderQueryValue).toList()));
      } catch (QueryException e) {
        return Json.respond(StatusCodes.BAD_REQUEST,
            ordered("type", e.type(), "message", e.getMessage()));
      }
    });
  }

  // -------------------------------------------------------- export, import

  /** R58. */
  @Get("/export")
  public HttpResponse exportAll() {
    Optional<HttpResponse> refused = guard("GET", Guards.segments("export"));
    if (refused.isPresent()) {
      return refused.get();
    }
    return attachment(Map.of("buckets", api.exportAll()), "aw-buckets-export.json");
  }

  @Get("/buckets/{bucketId}/export")
  public HttpResponse exportBucket(String bucketId) {
    Optional<HttpResponse> refused = guard("GET", Guards.segments("buckets", bucketId, "export"));
    if (refused.isPresent()) {
      return refused.get();
    }
    HttpResponse missing = requireBucket(bucketId);
    if (missing != null) {
      return missing;
    }
    Map<String, Object> bucket = api.exportBucket(bucketId);
    return attachment(Map.of("buckets", Map.of(bucketId, bucket)),
        "aw-bucket-export_" + bucketId + ".json");
  }

  /** R59. */
  @Post("/import")
  @SuppressWarnings("unchecked")
  public HttpResponse importAll(HttpEntity.Strict raw) {
    return guard("POST", Guards.segments("import")).orElseGet(() -> {
      Object buckets = object(raw).get("buckets");
      if (!(buckets instanceof Map<?, ?> table)) {
        return Json.message(StatusCodes.BAD_REQUEST, "Bad Request");
      }
      api.importAll((Map<String, Object>) table);
      return Json.respond(StatusCodes.OK, null);
    });
  }

  // ------------------------------------------------------------------- log

  /**
   * R60. The original's handler parses its log file as JSON and the file is not JSON, so this
   * route answers 500 there for every caller. It answers 500 here for the same reason it is
   * useless there: there is nothing it could return that the original would have — §4 OD-3.
   */
  @Get("/log")
  public HttpResponse log() {
    return guard("GET", Guards.segments("log")).orElseGet(() -> {
      throw new IllegalStateException("Extra data: line 1 column 5 (char 4)");
    });
  }

  // -------------------------------------------------------------- settings

  /** R57. */
  @Get("/settings")
  public HttpResponse settings() {
    return guard("GET", Guards.segments("settings"))
        .orElseGet(() -> Json.ok(Json.sortKeys(api.settings())));
  }

  @Get("/settings/{key}")
  public HttpResponse setting(String key) {
    return guard("GET", Guards.segments("settings", key))
        .orElseGet(() -> Json.ok(Json.sortKeys(api.setting(key))));
  }

  @Post("/settings")
  public HttpResponse setSettingWithNoKey(HttpEntity.Strict raw) {
    return guard("POST", Guards.segments("settings")).orElseGet(() ->
        Json.message(StatusCodes.BAD_REQUEST, "Missing required parameter key"));
  }

  @Post("/settings/{key}")
  public HttpResponse setSetting(String key, HttpEntity.Strict raw) {
    return guard("POST", Guards.segments("settings", key))
        .orElseGet(() -> Json.ok(api.setSetting(key, value(raw))));
  }

  // ---------------------------------------------------------------- stream

  /**
   * Every change to every bucket, as it happens — SPEC-001 §3 R112, RENDERING.md R1.
   *
   * <p>This is the port's own surface: the original has none, and a client of it asks again on
   * a timer. §4 OD-6 records what a subscriber sees across a dropped connection.
   */
  /**
   * One bucket's changes, pushed the moment they happen — RENDERING.md R1.2.
   *
   * <p>The whole-server stream below is complete and resumable and a few seconds behind,
   * because a projection is read by polling. A view showing one bucket live follows this
   * one instead, which arrives in about a millisecond and carries the event itself.
   */
  @Get("/buckets/{bucketId}/stream")
  public HttpResponse bucketStream(String bucketId) {
    Optional<HttpResponse> refused =
        guard("GET", Guards.segments("buckets", bucketId, "stream"));
    if (refused.isPresent()) {
      return refused.get();
    }
    var source = componentClient
        .forEventSourcedEntity(bucketId)
        .notificationStream(BucketEntity::updates)
        .source()
        .map(ApiEndpoint::renderChange);
    return akka.javasdk.http.HttpResponses.serverSentEvents(source);
  }

  @Get("/stream")
  public HttpResponse stream() {
    Optional<HttpResponse> refused = guard("GET", Guards.segments("stream"));
    if (refused.isPresent()) {
      return refused.get();
    }
    // A client that reconnects sends back the last event id it saw, which is the instant the
    // row it last applied was written; the view resumes from there. Nothing is replayed that
    // the client already applied, and nothing between the two is skipped.
    Optional<java.time.Instant> since = requestContext().lastSeenSseEventId()
        .map(java.time.Instant::parse);
    var source = componentClient.forView()
        .stream(BucketsView::live)
        .entriesSource(since);
    return akka.javasdk.http.HttpResponses.serverSentEventsForView(source);
  }

  // --------------------------------------------------------------- helpers

  private static Map<String, Object> renderChange(BucketEntity.Change change) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("bucket", change.bucket());
    out.put("kind", change.kind());
    out.put("id", change.id());
    out.put("event", change.event() == null ? null : Json.event(change.event()));
    out.put("events", change.count());
    return out;
  }

  /** A query answers with events, durations, numbers and containers of those. */
  private static Object renderQueryValue(Object value) {
    if (value instanceof Event event) {
      return Json.event(event);
    }
    if (value instanceof java.time.Duration duration) {
      return Json.seconds(duration);
    }
    if (value instanceof List<?> list) {
      List<Object> out = new ArrayList<>(list.size());
      for (Object item : list) {
        out.add(renderQueryValue(item));
      }
      return out;
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        out.put(String.valueOf(entry.getKey()), renderQueryValue(entry.getValue()));
      }
      return out;
    }
    return value;
  }

  private HttpResponse attachment(Object body, String filename) {
    return akka.javasdk.http.HttpResponses
        .of(StatusCodes.OK, akka.http.javadsl.model.ContentTypes.APPLICATION_JSON,
            Json.write(body).getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .addHeader(akka.http.javadsl.model.headers.RawHeader.create(
            "Content-Disposition", "attachment; filename=" + filename));
  }

  /**
   * R61, R62: the two checks the original runs before any handler.
   *
   * <p>Each route names its own verb and path. The SDK routes by annotation and does not hand
   * a handler the request line, and a guard that guessed at either would be checking something
   * other than the request that arrived.
   */
  private Optional<HttpResponse> guard(String method, List<String> segments) {
    return guards.check(
        requestContext().requestHeader("Host").map(h -> h.value()).orElse(null),
        requestContext().requestHeader("Origin").map(h -> h.value()).orElse(""),
        method, segments);
  }

  private HttpResponse requireBucket(String bucketId) {
    if (api.bucketExists(bucketId)) {
      return null;
    }
    return Json.respond(StatusCodes.NOT_FOUND,
        Map.of("message", "There's no bucket named " + bucketId));
  }

  /** A request body read as a JSON object, or an empty one where there was no body. */
  private static Map<String, Object> object(HttpEntity.Strict raw) {
    Object value = value(raw);
    return value instanceof Map<?, ?> map ? cast(map) : new LinkedHashMap<>();
  }

  /**
   * A request body read as whatever JSON it is, or null where there was no body.
   *
   * <p>The body is taken raw rather than as a typed record because every one of these routes
   * accepts arbitrary JSON — an event's `data`, a setting's value and a bucket's `data` are
   * whatever the client that wrote them put there.
   */
  private static Object value(HttpEntity.Strict raw) {
    if (raw == null || raw.getData().isEmpty()) {
      return null;
    }
    return Json.read(raw.getData().utf8String());
  }

  private static String required(Map<String, Object> body, String key) {
    Object value = body.get(key);
    if (value == null) {
      throw new IllegalStateException("KeyError: '" + key + "'");
    }
    return String.valueOf(value);
  }

  @SuppressWarnings("unchecked")
  /**
   * A body whose keys stay in the order they were written.
   *
   * <p>`Map.of` does not: it is unordered, and a caller reading the raw text of a 400 from
   * the query route sees `type` before `message` on the original.
   */
  private static Map<String, Object> ordered(Object... pairs) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      out.put(String.valueOf(pairs[i]), pairs[i + 1]);
    }
    return out;
  }

  private static Map<String, Object> cast(Map<?, ?> map) {
    return (Map<String, Object>) map;
  }

  /** R48: a body with no timestamp is stamped now; a duration that is missing is zero. */
  @SuppressWarnings("unchecked")
  private static Event toEvent(Map<String, Object> body) {
    Object timestamp = body.get("timestamp");
    Object duration = body.get("duration");
    Object data = body.get("data");
    Instant at = timestamp == null ? Instant.now() : instant(String.valueOf(timestamp));
    Object id = body.get("id");
    Event event = Event.of(at,
        duration == null ? 0d : ((Number) duration).doubleValue(),
        data instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of());
    return id == null ? event : event.withId(((Number) id).longValue());
  }

  private Instant instantParam(String name) {
    String value = requestContext().queryParams().getString(name).orElse(null);
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return instant(value);
    } catch (DateTimeParseException e) {
      // OD-3: the original lets its parse error escape and answers 500.
      throw new IllegalStateException("Unable to parse date string '" + value + "'");
    }
  }

  /**
   * R1: an instant with no zone is read as UTC.
   *
   * <p>The original's parser fills in a zone when the text carries none, so a watcher that
   * stamps its events with a naive local time is accepted rather than refused. A bare date is
   * accepted for the same reason: it is what the parser does, and a range typed by hand into
   * a query string usually has that shape.
   */
  private static Instant instant(String text) {
    try {
      return OffsetDateTime.parse(text).toInstant();
    } catch (DateTimeParseException withoutZone) {
      try {
        return java.time.LocalDateTime.parse(text).toInstant(java.time.ZoneOffset.UTC);
      } catch (DateTimeParseException withoutTime) {
        return java.time.LocalDate.parse(text).atStartOfDay(java.time.ZoneOffset.UTC)
            .toInstant();
      }
    }
  }
}
