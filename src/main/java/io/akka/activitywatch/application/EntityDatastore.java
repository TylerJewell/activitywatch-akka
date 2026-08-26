package io.akka.activitywatch.application;

import akka.javasdk.client.ComponentClient;
import io.akka.activitywatch.domain.Event;
import io.akka.activitywatch.domain.query.QueryDatastore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The query language's view of storage, over the entities that hold it.
 *
 * <p>One instance answers one query. The bucket listing is read once and kept, because a query
 * calls `find_bucket` several times and the original reads a single database snapshot; a
 * listing that changed halfway through a query would let two statements in the same query
 * disagree about which buckets exist.
 */
public final class EntityDatastore implements QueryDatastore {

  private final ComponentClient componentClient;
  private final ServerApi api;
  private List<String> bucketIds;
  private final Map<String, Map<String, Object>> metadata = new LinkedHashMap<>();

  public EntityDatastore(ComponentClient componentClient, ServerApi api) {
    this.componentClient = componentClient;
    this.api = api;
  }

  @Override
  public List<String> buckets() {
    if (bucketIds == null) {
      // The registry rather than the view: `find_bucket` is usually the first thing a client
      // does after creating the bucket it is about to name.
      List<String> ids = new ArrayList<>();
      for (String bucketId : api.bucketIds()) {
        BucketEntity.Info info = api.info(bucketId);
        if (!info.exists()) {
          continue;
        }
        ids.add(bucketId);
        metadata.put(bucketId, new LinkedHashMap<>(info.metadata()));
      }
      bucketIds = List.copyOf(ids);
    }
    return bucketIds;
  }

  @Override
  public boolean exists(String bucketId) {
    return componentClient.forEventSourcedEntity(bucketId)
        .method(BucketEntity::info).invoke().exists();
  }

  @Override
  public Map<String, Object> metadata(String bucketId) {
    Map<String, Object> known = metadata.get(bucketId);
    if (known != null) {
      return known;
    }
    BucketEntity.Info info = componentClient.forEventSourcedEntity(bucketId)
        .method(BucketEntity::info).invoke();
    return info.exists() ? info.metadata() : null;
  }

  @Override
  public List<Event> events(String bucketId, Instant start, Instant end) {
    return api.events(bucketId, -1, start, end);
  }

  @Override
  public long eventCount(String bucketId, Instant start, Instant end) {
    return api.eventCount(bucketId, start, end);
  }
}
