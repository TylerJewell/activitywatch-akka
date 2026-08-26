package io.akka.activitywatch.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import java.util.ArrayList;
import java.util.List;

/**
 * What exists, in the order it was made — SPEC-001 §3 R43, R57, R76.
 *
 * <p>There are two of these, {@link #BUCKETS} and {@link #SETTINGS}, and they hold nothing
 * but names. They are here because three rules read a *set* rather than any one member of it,
 * and all three are answered the instant a member is made: `GET /api/0/buckets/` lists every
 * bucket, `GET /api/0/settings` every setting, and `find_bucket` in the query language takes
 * the first bucket whose name contains a string. A watcher creates its bucket and immediately
 * runs a query naming it, so a listing that arrives a moment later is a query that fails, and
 * the events written in the meantime are events nothing can find.
 *
 * <p>{@link BucketsView} and {@link SettingsView} answer the same question and are not used
 * for it. A view is fed by a projection, which is eventually consistent by design; that is
 * right for the stream the interface listens to, where an update a moment late is still an
 * update, and wrong for a caller asking whether the thing it just made is there.
 *
 * <p>Every request touching one member goes to that member's own entity, so what passes
 * through here is creation and deletion — a handful of writes when a machine's watchers
 * start, and none after.
 */
@Component(id = "registry")
public class Registry extends KeyValueEntity<Registry.Names> {

  public static final String BUCKETS = "buckets";
  public static final String SETTINGS = "settings";

  public record Names(List<String> ids) {}

  @Override
  public Names emptyState() {
    return new Names(List.of());
  }

  /** Idempotent: adding a name that is already listed leaves the order alone. */
  public Effect<Boolean> add(String name) {
    if (currentState().ids().contains(name)) {
      return effects().reply(false);
    }
    List<String> next = new ArrayList<>(currentState().ids());
    next.add(name);
    return effects().updateState(new Names(List.copyOf(next))).thenReply(true);
  }

  public Effect<Boolean> remove(String name) {
    if (!currentState().ids().contains(name)) {
      return effects().reply(false);
    }
    List<String> next = new ArrayList<>(currentState().ids());
    next.remove(name);
    return effects().updateState(new Names(List.copyOf(next))).thenReply(true);
  }

  public ReadOnlyEffect<Names> all() {
    return effects().reply(currentState());
  }
}
