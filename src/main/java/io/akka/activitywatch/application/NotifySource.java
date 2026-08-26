package io.akka.activitywatch.application;

import io.akka.activitywatch.client.ActivityWatchClient;
import java.time.Instant;
import java.util.List;

/**
 * The four things the notification service asks a server for.
 *
 * <p>It exists because the service runs in two places. Started from the command line it is a
 * program beside a server and reaches it over HTTP, the way the module it is a port of does —
 * and that is what lets the same code be pointed at the original. Started inside this service
 * it is already there, and a component calling its own HTTP port would be a round trip to
 * itself, through a port number it does not know when the runtime chose one for it.
 */
public interface NotifySource {

  String hostname();

  /** @return the setting's value, or null when it is not set or cannot be read */
  Object setting(String key);

  /** @return one answer per timeperiod */
  Object query(String query, List<Instant[]> timeperiods);

  /** @return whether the server answered at all */
  boolean available();

  /** Over HTTP, the way a module beside the server does it. */
  static NotifySource of(ActivityWatchClient client) {
    return new NotifySource() {
      @Override
      public String hostname() {
        return client.hostname();
      }

      @Override
      public Object setting(String key) {
        try {
          return client.setting(key);
        } catch (RuntimeException e) {
          return null;
        }
      }

      @Override
      public Object query(String query, List<Instant[]> timeperiods) {
        return client.query(query, timeperiods, "");
      }

      @Override
      public boolean available() {
        try {
          client.info();
          return true;
        } catch (RuntimeException e) {
          return false;
        }
      }
    };
  }

  /** Inside the service, without going back out through its own front door. */
  static NotifySource of(ServerApi api, String hostname) {
    return new NotifySource() {
      @Override
      public String hostname() {
        return hostname;
      }

      @Override
      public Object setting(String key) {
        try {
          return api.setting(key);
        } catch (RuntimeException e) {
          return null;
        }
      }

      @Override
      public Object query(String query, List<Instant[]> timeperiods) {
        List<String> periods = new java.util.ArrayList<>(timeperiods.size());
        for (Instant[] period : timeperiods) {
          periods.add(io.akka.activitywatch.api.Json.instant(period[0]) + "/"
              + io.akka.activitywatch.api.Json.instant(period[1]));
        }
        return api.query2("", List.of(query), periods);
      }

      @Override
      public boolean available() {
        return true;
      }
    };
  }
}
