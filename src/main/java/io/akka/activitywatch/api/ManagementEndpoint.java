package io.akka.activitywatch.api;

import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.http.AbstractHttpEndpoint;
import com.typesafe.config.Config;
import io.akka.activitywatch.application.ModuleManager;
import io.akka.activitywatch.application.Watchers;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Running the pieces: the module manager and the watchers.
 *
 * <p>The original reaches both of these through a tray menu and a command line. The tray is
 * out of scope — its only check is a person looking at it — so the capability underneath it
 * needs a surface a caller can reach, and this is it. The command-line tool answers the same
 * questions through the same code; the README lists the HTTP surface as a difference, since
 * the original has no equivalent route.
 */
@HttpEndpoint("/api/0")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class ManagementEndpoint extends AbstractHttpEndpoint {

  private final Config config;
  private final Guards guards;
  private final io.akka.activitywatch.application.ServerApi api;

  public ManagementEndpoint(akka.javasdk.client.ComponentClient componentClient, Config config) {
    this.config = config;
    this.guards = ServiceWiring.guards(config);
    this.api = ServiceWiring.serverApi(componentClient, config);
  }

  // ---------------------------------------------------- the notification service

  /** R135–R140: whether it is running, and what it has said. */
  @Get("/notify")
  public HttpResponse notifyStatus() {
    return guard("GET", Guards.segments("notify")).orElseGet(() -> {
      var daemon = io.akka.activitywatch.application.NotifyDaemon.instance(config);
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("running", daemon.running());
      out.put("shown", daemon.recent());
      return Json.ok(out);
    });
  }

  @Post("/notify/start")
  public HttpResponse notifyStart() {
    return guard("POST", Guards.segments("notify", "start")).orElseGet(() -> {
      io.akka.activitywatch.application.NotifyDaemon.instance(config).start(api);
      return Json.ok(Map.of("running", true));
    });
  }

  @Post("/notify/stop")
  public HttpResponse notifyStop() {
    return guard("POST", Guards.segments("notify", "stop")).orElseGet(() -> {
      boolean stopped = io.akka.activitywatch.application.NotifyDaemon.instance(config).stop();
      return Json.ok(Map.of("running", false, "stopped", stopped));
    });
  }

  // --------------------------------------------------------------- modules

  @Get("/modules")
  public HttpResponse modules() {
    return guard("GET", Guards.segments("modules")).orElseGet(() -> {
      List<Object> out = new ArrayList<>();
      for (ModuleManager.Status status : manager().statuses()) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", status.name());
        row.put("path", status.path());
        row.put("type", status.origin());
        row.put("started", status.started());
        row.put("alive", status.alive());
        row.put("external", status.external());
        out.add(row);
      }
      return Json.ok(out);
    });
  }

  @Post("/modules/{name}/start")
  public HttpResponse startModule(String name, HttpEntity.Strict raw) {
    return guard("POST", Guards.segments("modules", name, "start")).orElseGet(() -> {
      if (!manager().start(name)) {
        return Json.message(StatusCodes.NOT_FOUND, "There's no module named " + name);
      }
      return Json.ok(Map.of("started", true));
    });
  }

  @Post("/modules/{name}/stop")
  public HttpResponse stopModule(String name, HttpEntity.Strict raw) {
    return guard("POST", Guards.segments("modules", name, "stop")).orElseGet(() ->
        Json.ok(Map.of("stopped", manager().stop(name))));
  }

  @Post("/modules/autostart")
  public HttpResponse autostart(HttpEntity.Strict raw) {
    return guard("POST", Guards.segments("modules", "autostart")).orElseGet(() ->
        Json.ok(Map.of("started", manager().autostart(configuredAutostart()))));
  }

  @Get("/modules/{name}/log")
  public HttpResponse moduleLog(String name) {
    return guard("GET", Guards.segments("modules", name, "log")).orElseGet(() ->
        Json.ok(Map.of("log", manager().log(name))));
  }

  @Get("/modules/unexpected-stops")
  public HttpResponse unexpectedStops() {
    return guard("GET", Guards.segments("modules", "unexpected-stops")).orElseGet(() ->
        Json.ok(manager().unexpectedStops()));
  }

  // -------------------------------------------------------------- watchers

  @Get("/watchers")
  public HttpResponse watchers() {
    return guard("GET", Guards.segments("watchers")).orElseGet(() ->
        Json.ok(Watchers.instance(config).statuses()));
  }

  @Post("/watchers/{name}/start")
  public HttpResponse startWatcher(String name, HttpEntity.Strict raw) {
    return guard("POST", Guards.segments("watchers", name, "start")).orElseGet(() -> {
      try {
        return Json.ok(Map.of("started", Watchers.instance(config).start(name)));
      } catch (IllegalArgumentException e) {
        return Json.message(StatusCodes.BAD_REQUEST, e.getMessage());
      }
    });
  }

  @Post("/watchers/{name}/stop")
  public HttpResponse stopWatcher(String name, HttpEntity.Strict raw) {
    return guard("POST", Guards.segments("watchers", name, "stop")).orElseGet(() ->
        Json.ok(Map.of("stopped", Watchers.instance(config).stop(name))));
  }

  private List<String> configuredAutostart() {
    return List.of();
  }

  private ModuleManager manager() {
    return Managers.forConfig(config);
  }

  private java.util.Optional<HttpResponse> guard(String method, List<String> segments) {
    return guards.check(
        requestContext().requestHeader("Host").map(h -> h.value()).orElse(null),
        requestContext().requestHeader("Origin").map(h -> h.value()).orElse(""),
        method, segments);
  }

  /**
   * The one manager the service has.
   *
   * <p>An endpoint is built per request and a process manager is not: it owns child processes
   * whose lifetime is the service's. Held here rather than in the endpoint for that reason.
   */
  static final class Managers {
    private static volatile ModuleManager instance;

    private Managers() {}

    static ModuleManager forConfig(Config config) {
      ModuleManager known = instance;
      if (known == null) {
        synchronized (Managers.class) {
          if (instance == null) {
            instance = new ModuleManager(ServiceWiring.testing(config),
                config.getInt("akka.javasdk.dev-mode.http-port"),
                ModuleManager.defaultBundledPaths());
          }
          known = instance;
        }
      }
      return known;
    }
  }
}
