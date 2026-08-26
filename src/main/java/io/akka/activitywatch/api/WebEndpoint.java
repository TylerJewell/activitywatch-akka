package io.akka.activitywatch.api;

import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCode;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpResponses;
import com.typesafe.config.Config;
import java.util.List;
import java.util.Map;

/**
 * The web interface, served from where the original serves it.
 *
 * <p>The interface itself is ActivityWatch's own — RENDERING.md R3 — vendored under
 * `webui/`, built, and copied into `src/main/resources/static`. Nothing here renders anything:
 * these are the four routes the original's Flask application declares for its own static
 * build, plus the custom-static routes a third-party watcher registers pages under.
 *
 * <p>The build is a single-page application with hash routing, so every path after the `#` is
 * the browser's business and never reaches a server. That is why there is no catch-all here
 * and why one would be a mistake: a catch-all would answer the runtime's own health check.
 */
@HttpEndpoint
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class WebEndpoint extends AbstractHttpEndpoint {

  private final Map<String, String> customStatic;
  private final Config config;

  public WebEndpoint(Config config) {
    this.config = config;
    this.customStatic = io.akka.activitywatch.domain.AwConfig.parseKeyValuePairs(
        config.hasPath("activitywatch.custom-static")
            ? config.getString("activitywatch.custom-static") : "");
  }

  @Get("/")
  public HttpResponse index() {
    return HttpResponses.staticResource("index.html");
  }

  /**
   * R142: a notification another module wants shown.
   *
   * <p>The original listens for this on a port of its own, which is off unless a port is set
   * in the notification service's configuration. Here it is a route on the server the module
   * is already talking to, and it answers 404 until the service is running — which is the
   * same answer the original's router gives for a path it does not serve, and what a client
   * that cannot reach it sees either way is a request that did not get through.
   */
  @akka.javasdk.annotations.http.Post("/notify")
  public HttpResponse notify(akka.http.javadsl.model.HttpEntity.Strict raw) {
    io.akka.activitywatch.application.NotifyDaemon daemon =
        io.akka.activitywatch.application.NotifyDaemon.instance(config);
    io.akka.activitywatch.application.Notifier notifier = daemon.notifier();
    if (notifier == null) {
      return text(StatusCodes.NOT_FOUND, "Not Found");
    }
    byte[] body = raw.getData().toArray();
    if (body.length > MAX_NOTIFY_BODY) {
      return text(StatusCodes.BAD_REQUEST, "Invalid JSON");
    }
    Map<String, Object> parsed;
    try {
      parsed = Json.readObject(new String(body, java.nio.charset.StandardCharsets.UTF_8));
    } catch (RuntimeException e) {
      return text(StatusCodes.BAD_REQUEST, "Invalid JSON");
    }
    Object title = parsed.get("title");
    Object message = parsed.get("message");
    if (!(title instanceof String) || !(message instanceof String)) {
      return text(StatusCodes.BAD_REQUEST, "Invalid JSON");
    }
    Object sender = parsed.get("sender") != null ? parsed.get("sender") : parsed.get("watcher");
    boolean queued = notifier.offer(new io.akka.activitywatch.application.Notifier.Notification(
        (String) title, (String) message, sender == null ? null : String.valueOf(sender)));
    return queued
        ? text(StatusCodes.OK, "OK")
        : text(StatusCodes.TOO_MANY_REQUESTS, "Too Many Requests (Buffer Full)");
  }

  /** R142: the body is capped so a local caller cannot grow the queue's memory without limit. */
  static final int MAX_NOTIFY_BODY = 64 * 1024;

  private static HttpResponse text(StatusCode status, String body) {
    return HttpResponses.of(status, akka.http.javadsl.model.ContentTypes.TEXT_PLAIN_UTF8,
        body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  @Get("/{file}")
  public HttpResponse topLevel(String file) {
    return serve(file);
  }

  @Get("/css/{file}")
  public HttpResponse css(String file) {
    return serve("css/" + file);
  }

  @Get("/js/{file}")
  public HttpResponse js(String file) {
    return serve("js/" + file);
  }

  @Get("/fonts/{file}")
  public HttpResponse fonts(String file) {
    return serve("fonts/" + file);
  }

  /** R64: which watchers registered pages. */
  @Get("/pages/")
  public HttpResponse pages() {
    return Json.ok(List.copyOf(customStatic.keySet()));
  }

  @Get("/pages/{name}/")
  public HttpResponse page(String name) {
    return customPage(name, "index.html");
  }

  @Get("/pages/{name}/{file}")
  public HttpResponse pageFile(String name, String file) {
    return customPage(name, file);
  }

  private HttpResponse customPage(String name, String file) {
    String directory = customStatic.get(name);
    if (directory == null) {
      return notFound(name, file);
    }
    java.nio.file.Path root = java.nio.file.Paths.get(directory).toAbsolutePath().normalize();
    java.nio.file.Path target = root.resolve(file).normalize();
    if (!target.startsWith(root) || !java.nio.file.Files.isRegularFile(target)) {
      return notFound(name, file);
    }
    try {
      return akka.javasdk.http.HttpResponses.of(StatusCodes.OK,
          contentType(file), java.nio.file.Files.readAllBytes(target));
    } catch (java.io.IOException e) {
      return notFound(name, file);
    }
  }

  /**
   * R64: the sentence, as text.
   *
   * <p>Not JSON. This route serves whatever a watcher put in its own directory, so its
   * failure is a page rather than a document, and the original answers `text/html` with the
   * bare sentence in it — which is what a browser following a link from a watcher's own page
   * shows the reader.
   */
  private static HttpResponse notFound(String name, String file) {
    return akka.javasdk.http.HttpResponses.of(StatusCodes.NOT_FOUND,
        akka.http.javadsl.model.ContentTypes.TEXT_HTML_UTF8,
        ("Static content: " + file + " of watcher: " + name + " not found!")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static HttpResponse serve(String path) {
    if (path.contains("..")) {
      return HttpResponses.notFound();
    }
    return HttpResponses.staticResource(path);
  }

  private static akka.http.javadsl.model.ContentType contentType(String file) {
    String lower = file.toLowerCase(java.util.Locale.ROOT);
    if (lower.endsWith(".html")) {
      return akka.http.javadsl.model.ContentTypes.TEXT_HTML_UTF8;
    }
    if (lower.endsWith(".json")) {
      return akka.http.javadsl.model.ContentTypes.APPLICATION_JSON;
    }
    return akka.http.javadsl.model.ContentTypes.APPLICATION_OCTET_STREAM;
  }
}
