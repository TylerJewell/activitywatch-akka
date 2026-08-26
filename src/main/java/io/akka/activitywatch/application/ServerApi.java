package io.akka.activitywatch.application;

import akka.javasdk.client.ComponentClient;
import io.akka.activitywatch.domain.Event;
import io.akka.activitywatch.domain.EventSelection;
import io.akka.activitywatch.domain.query.QueryEngine;
import io.akka.activitywatch.domain.query.QueryException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What the routes do, kept out of the routes themselves —
 * SPEC-001 §3 R42–R60.
 *
 * <p>The split follows the original's: `rest.py` decides status codes and reads parameters,
 * `api.py` decides what happens. Keeping it means a rule can be checked without a request, and
 * the two systems' error messages can be compared without going through HTTP twice.
 */
public class ServerApi {

  private final ComponentClient componentClient;
  private final String hostname;
  private final boolean testing;
  private final int retainedEvents;
  private final String version;
  private final String deviceId;

  public ServerApi(ComponentClient componentClient, String hostname, boolean testing,
      int retainedEvents, String version, String deviceId) {
    this.componentClient = componentClient;
    this.hostname = hostname;
    this.testing = testing;
    this.retainedEvents = retainedEvents;
    this.version = version;
    this.deviceId = deviceId;
  }

  public boolean testing() {
    return testing;
  }

  /** R42. */
  public Map<String, Object> info() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("hostname", hostname);
    out.put("version", version);
    out.put("testing", testing);
    out.put("device_id", deviceId);
    return out;
  }

  /** R43: every bucket's metadata, with the end of its most recent event where it has one. */
  public Map<String, Object> buckets() {
    Map<String, Object> out = new LinkedHashMap<>();
    for (String bucketId : bucketIds()) {
      BucketEntity.Info info = info(bucketId);
      if (!info.exists()) {
        continue;
      }
      Map<String, Object> metadata = new LinkedHashMap<>(info.metadata());
      if (info.lastUpdated() != null) {
        metadata.put("last_updated", info.lastUpdated());
      }
      out.put(bucketId, metadata);
    }
    return out;
  }

  /**
   * Which buckets exist, in the order they were made.
   *
   * <p>Read from {@link BucketRegistry} rather than from {@link BucketsView}: a caller that
   * has just made a bucket asks for it in the next request, and a projection has not
   * necessarily run by then.
   */
  public List<String> bucketIds() {
    return componentClient.forKeyValueEntity(Registry.BUCKETS)
        .method(Registry::all).invoke().ids();
  }


  public boolean bucketExists(String bucketId) {
    return info(bucketId).exists();
  }

  public BucketEntity.Info info(String bucketId) {
    return componentClient.forEventSourcedEntity(bucketId).method(BucketEntity::info).invoke();
  }

  public Map<String, Object> bucketMetadata(String bucketId) {
    return info(bucketId).metadata();
  }

  /**
   * R44: a bucket that already exists is left alone and the caller told so.
   *
   * <p>A hostname of `!local` means "wherever the server is", which is how a browser extension
   * that cannot know the machine's name still gets a correctly named bucket.
   */
  public boolean createBucket(String bucketId, String type, String client, String hostnameGiven,
      Instant created, Map<String, Object> data) {
    String host = hostnameGiven;
    Map<String, Object> bucketData = data == null ? new LinkedHashMap<>()
        : new LinkedHashMap<>(data);
    if ("!local".equals(hostnameGiven)) {
      host = hostname;
      bucketData.put("device_id", deviceId);
    }
    boolean made = componentClient.forEventSourcedEntity(bucketId)
        .method(BucketEntity::create)
        .invoke(new BucketEntity.Create(null, type, client, host,
            io.akka.activitywatch.api.Json.instant(
                created == null ? Instant.now() : created),
            bucketData, retainedEvents));
    if (made) {
      componentClient.forKeyValueEntity(Registry.BUCKETS)
          .method(Registry::add).invoke(bucketId);
    }
    return made;
  }

  public void updateBucket(String bucketId, String type, String client, String hostnameGiven,
      Map<String, Object> data) {
    componentClient.forEventSourcedEntity(bucketId)
        .method(BucketEntity::update)
        .invoke(new BucketEntity.Update(null, type, client, hostnameGiven, data));
  }

  public boolean deleteBucket(String bucketId) {
    boolean deleted = componentClient.forEventSourcedEntity(bucketId)
        .method(BucketEntity::delete).invoke();
    componentClient.forKeyValueEntity(Registry.BUCKETS)
        .method(Registry::remove).invoke(bucketId);
    return deleted;
  }

  /**
   * R33–R36, R49: the events a range asks for.
   *
   * <p>Read from two places and put together once. A bucket keeps what it wrote recently, so
   * a caller reading back what it has just written finds it whatever order the page consumer
   * got to; the pages keep the history. An event that is in both arrives twice and is counted
   * once, by its identity.
   */
  public List<Event> events(String bucketId, int limit, Instant start, Instant end) {
    return EventSelection.answer(gather(bucketId, start, end), start, end, limit);
  }

  public List<Event> allEvents(String bucketId) {
    return EventSelection.newestFirst(gather(bucketId, null, null));
  }

  public Optional<Event> event(String bucketId, long eventId) {
    BucketEntity.Found found = componentClient.forEventSourcedEntity(bucketId)
        .method(BucketEntity::event).invoke(eventId);
    if (found.found()) {
      return Optional.of(found.event());
    }
    // Not among what the bucket still holds, so it is in one of its pages.
    for (Event event : gather(bucketId, null, null)) {
      if (Long.valueOf(eventId).equals(event.id())) {
        return Optional.of(event);
      }
    }
    return Optional.empty();
  }

  /** R37: the same selection, without the rounding and without a limit. */
  public long eventCount(String bucketId, Instant start, Instant end) {
    return EventSelection.count(gather(bucketId, start, end), start, end);
  }

  /**
   * Everything overlapping the range from both places, uncut and unordered.
   *
   * <p>The bucket's own events come first so that, where the same identity is in both, the
   * bucket's is the one kept: it is the newer of the two whenever the page has not caught up.
   */
  private List<Event> gather(String bucketId, Instant start, Instant end) {
    BucketEntity.Range range = new BucketEntity.Range(
        start == null ? null : start.toEpochMilli(),
        end == null ? null : end.toEpochMilli(), -1);
    BucketEntity.Events recent = componentClient.forEventSourcedEntity(bucketId)
        .method(BucketEntity::overlapping).invoke(range);
    List<Event> all = new ArrayList<>(recent.events());

    BucketEntity.Info info = info(bucketId);
    for (String page : EventSelection.pagesFor(bucketId, start, end, info.pages())) {
      all.addAll(componentClient.forEventSourcedEntity(page)
          .method(EventPageEntity::overlapping)
          .invoke(new EventPageEntity.Range(range.fromMillis(), range.toMillis()))
          .events());
    }
    return all;
  }

  /** R48. */
  public List<Event> createEvents(String bucketId, List<Event> events) {
    return componentClient.forEventSourcedEntity(bucketId)
        .method(BucketEntity::insert).invoke(events).inserted();
  }

  public boolean deleteEvent(String bucketId, long eventId) {
    return componentClient.forEventSourcedEntity(bucketId)
        .method(BucketEntity::deleteEvent).invoke(eventId);
  }

  /** R4–R9, R53. */
  public Event heartbeat(String bucketId, Event heartbeat, double pulsetime) {
    BucketEntity.HeartbeatResult result = componentClient.forEventSourcedEntity(bucketId)
        .method(BucketEntity::heartbeat)
        .invoke(new BucketEntity.Heartbeat(heartbeat, pulsetime));
    return result.event().withId(result.id());
  }

  /** R55: one answer per timeperiod, in the order they were asked for. */
  public List<Object> query2(String name, List<String> query, List<String> timeperiods) {
    List<Object> result = new ArrayList<>();
    String text = QueryEngine.join(query);
    for (String timeperiod : timeperiods) {
      String[] halves = timeperiod.split("/");
      if (halves.length < 2) {
        throw new QueryException("Invalid timeperiod '" + timeperiod + "': expected two ISO8601 "
            + "datetimes separated by a slash (start/end)");
      }
      Instant start;
      Instant end;
      try {
        start = OffsetDateTime.parse(halves[0]).toInstant();
        end = OffsetDateTime.parse(halves[1]).toInstant();
      } catch (DateTimeParseException e) {
        // The original's parser names the text it could not read; the platform's names an
        // index into it, which is a fact about the parser rather than about the request.
        throw new QueryException("Invalid timeperiod '" + timeperiod
            + "': Unable to parse date string '" + halves[0] + "'");
      }
      result.add(QueryEngine.query(name, text, start, end, new EntityDatastore(componentClient, this)));
    }
    return result;
  }

  /** R58: an export scrubs the identities, so importing it somewhere else assigns new ones. */
  public Map<String, Object> exportBucket(String bucketId) {
    Map<String, Object> bucket = new LinkedHashMap<>(bucketMetadata(bucketId));
    List<Object> events = new ArrayList<>();
    for (Event event : allEvents(bucketId)) {
      Map<String, Object> row = io.akka.activitywatch.api.Json.event(event);
      row.remove("id");
      events.add(row);
    }
    bucket.put("events", events);
    return bucket;
  }

  public Map<String, Object> exportAll() {
    Map<String, Object> out = new LinkedHashMap<>();
    for (String bucketId : bucketIds()) {
      out.put(bucketId, exportBucket(bucketId));
    }
    return out;
  }

  /** R59. */
  @SuppressWarnings("unchecked")
  public void importAll(Map<String, Object> buckets) {
    for (Map.Entry<String, Object> entry : buckets.entrySet()) {
      importBucket((Map<String, Object>) entry.getValue());
    }
  }

  @SuppressWarnings("unchecked")
  public void importBucket(Map<String, Object> bucket) {
    String bucketId = String.valueOf(bucket.get("id"));
    Object created = bucket.get("created");
    createBucket(bucketId,
        String.valueOf(bucket.get("type")),
        String.valueOf(bucket.get("client")),
        String.valueOf(bucket.get("hostname")),
        created == null ? null : OffsetDateTime.parse(String.valueOf(created)).toInstant(),
        null);
    List<Object> raw = (List<Object>) bucket.getOrDefault("events", List.of());
    List<Event> events = new ArrayList<>(raw.size());
    for (Object item : raw) {
      Map<String, Object> row = (Map<String, Object>) item;
      // Identities are scrubbed on the way in as well as on the way out: an export taken from
      // another server carries its ids, and keeping them would overwrite whatever holds them
      // here.
      events.add(Event.of(
          OffsetDateTime.parse(String.valueOf(row.get("timestamp"))).toInstant(),
          row.get("duration") == null ? 0d : ((Number) row.get("duration")).doubleValue(),
          (Map<String, Object>) row.getOrDefault("data", Map.of())));
    }
    if (!events.isEmpty()) {
      createEvents(bucketId, events);
    }
  }

  /** R57. */
  public Object setting(String key) {
    SettingEntity.Setting setting = componentClient.forKeyValueEntity(key)
        .method(SettingEntity::get).invoke();
    return setting.valueJson() == null ? null
        : io.akka.activitywatch.api.Json.read(setting.valueJson());
  }

  public Map<String, Object> settings() {
    Map<String, Object> out = new LinkedHashMap<>();
    for (String key : componentClient.forKeyValueEntity(Registry.SETTINGS)
        .method(Registry::all).invoke().ids()) {
      Object value = setting(key);
      if (value != null) {
        out.put(key, value);
      }
    }
    return out;
  }

  /**
   * R57: a falsy value deletes the key.
   *
   * <p>Falsy is Python's: null, false, zero, the empty string, the empty list and the empty
   * object. A setting written to nothing is a setting removed, and that is the only way the
   * original offers to remove one.
   */
  public Object setSetting(String key, Object value) {
    if (truthy(value)) {
      componentClient.forKeyValueEntity(key).method(SettingEntity::set)
          .invoke(io.akka.activitywatch.api.Json.write(value));
      componentClient.forKeyValueEntity(Registry.SETTINGS).method(Registry::add).invoke(key);
    } else {
      componentClient.forKeyValueEntity(key).method(SettingEntity::clear).invoke();
      componentClient.forKeyValueEntity(Registry.SETTINGS).method(Registry::remove)
          .invoke(key);
    }
    return value;
  }

  static boolean truthy(Object value) {
    if (value == null || Boolean.FALSE.equals(value)) {
      return false;
    }
    if (value instanceof String text) {
      return !text.isEmpty();
    }
    if (value instanceof Number number) {
      return number.doubleValue() != 0;
    }
    if (value instanceof List<?> list) {
      return !list.isEmpty();
    }
    if (value instanceof Map<?, ?> map) {
      return !map.isEmpty();
    }
    return true;
  }


  /** A stable identity for this installation, made once and kept in the data directory. */
  public static String deviceId() {
    java.nio.file.Path path = io.akka.activitywatch.domain.Dirs.dataDir("aw-server")
        .resolve("device_id");
    try {
      if (java.nio.file.Files.isRegularFile(path)) {
        return java.nio.file.Files.readString(path).strip();
      }
      String uuid = UUID.randomUUID().toString();
      java.nio.file.Files.writeString(path, uuid);
      return uuid;
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
