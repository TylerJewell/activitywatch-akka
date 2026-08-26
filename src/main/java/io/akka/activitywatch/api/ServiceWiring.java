package io.akka.activitywatch.api;

import akka.javasdk.client.ComponentClient;
import com.typesafe.config.Config;
import io.akka.activitywatch.application.ServerApi;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * What the endpoints are built from, read once from configuration.
 *
 * <p>An endpoint is constructed per request, so anything read here is read per request. The
 * two values that cost something — the machine's name and the installation's identity — are
 * worked out once and kept.
 */
public final class ServiceWiring {

  /** The version the API reports. It names the original it reproduces, not this build. */
  public static final String VERSION = "v0.13.2";

  private static volatile String hostname;
  private static volatile String deviceId;

  private ServiceWiring() {}

  public static ServerApi serverApi(ComponentClient componentClient, Config config) {
    return new ServerApi(componentClient, hostname(config), testing(config),
        config.getInt("activitywatch.retained-events"), VERSION, deviceId());
  }

  public static Guards guards(Config config) {
    return new Guards(config.getString("activitywatch.host"), userOrigins(config));
  }

  public static boolean testing(Config config) {
    return config.getBoolean("activitywatch.testing");
  }

  public static List<String> userOrigins(Config config) {
    List<String> origins = new ArrayList<>();
    for (String origin : config.getString("activitywatch.cors-origins").split(",")) {
      if (!origin.isBlank()) {
        origins.add(origin.strip());
      }
    }
    return origins;
  }

  public static String hostname(Config config) {
    String configured = config.getString("activitywatch.hostname");
    if (!configured.isBlank()) {
      return configured;
    }
    if (hostname == null) {
      try {
        hostname = InetAddress.getLocalHost().getHostName();
      } catch (UnknownHostException e) {
        hostname = "localhost";
      }
    }
    return hostname;
  }

  public static String deviceId() {
    if (deviceId == null) {
      deviceId = ServerApi.deviceId();
    }
    return deviceId;
  }
}
