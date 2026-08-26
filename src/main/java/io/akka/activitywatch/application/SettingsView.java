package io.akka.activitywatch.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import java.util.List;

/**
 * Every setting at once, which is what `GET /api/0/settings` answers — SPEC-001 §3 R57.
 *
 * <p>A setting whose value was cleared keeps no row: the original deletes the key from its
 * file rather than storing a null, and a caller asking for everything must not see it.
 */
@Component(id = "settings")
public class SettingsView extends View {

  public record SettingRow(String key, String valueJson) {}

  public record Settings(List<SettingRow> settings) {}

  @Consume.FromKeyValueEntity(SettingEntity.class)
  public static class SettingsUpdater extends TableUpdater<SettingRow> {

    public Effect<SettingRow> onChange(SettingEntity.Setting setting) {
      if (setting.valueJson() == null) {
        return effects().deleteRow();
      }
      return effects().updateRow(new SettingRow(setting.key(), setting.valueJson()));
    }
  }

  @Query("SELECT * AS settings FROM settings ORDER BY key")
  public QueryEffect<Settings> all() {
    return queryResult();
  }
}
