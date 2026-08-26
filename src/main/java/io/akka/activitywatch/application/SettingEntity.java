package io.akka.activitywatch.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import akka.javasdk.keyvalueentity.KeyValueEntityContext;

/**
 * One server-wide setting — SPEC-001 §3 R57.
 *
 * <p>The value travels and is stored as JSON text rather than as a parsed object: the original
 * keeps a JSON file and hands whatever is in it straight back, and a setting is written by the
 * web interface and read by clients that were not written alongside it.
 *
 * <p>Writing a falsy value deletes the key. That is the original's rule and it is the reason
 * this entity has no separate delete: there is one way to remove a setting and it is to write
 * nothing to it.
 */
@Component(id = "setting")
public class SettingEntity extends KeyValueEntity<SettingEntity.Setting> {

  /**
   * @param valueJson the value as JSON text, or null when the setting is not set
   */
  public record Setting(String key, String valueJson) {}

  private final String key;

  public SettingEntity(KeyValueEntityContext context) {
    this.key = context.entityId();
  }

  @Override
  public Setting emptyState() {
    return new Setting(key, null);
  }

  public Effect<Setting> set(String valueJson) {
    return effects().updateState(new Setting(key, valueJson)).thenReply(new Setting(key,
        valueJson));
  }

  public Effect<Setting> clear() {
    return effects().updateState(new Setting(key, null)).thenReply(new Setting(key, null));
  }

  public ReadOnlyEffect<Setting> get() {
    return effects().reply(currentState());
  }
}
