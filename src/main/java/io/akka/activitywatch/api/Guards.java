package io.akka.activitywatch.api;

import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The two checks that run before any handler — SPEC-001 §3 R61, R62.
 *
 * <p>The first protects against DNS rebinding: a page on another site can make a browser send
 * a request to `localhost`, and the `Host` header is what says which name the browser thought
 * it was talking to.
 *
 * <p>The second narrows a wildcard. A browser extension's origin is random per installation,
 * so the server has to allow every extension origin for the one extension it ships with — and
 * that would otherwise let any installed extension read the whole history with no permission
 * prompt naming ActivityWatch. Three endpoints are enough for the extension it is for.
 */
public final class Guards {

  private static final String EXTENSION_SCHEME = "moz-extension://";
  private static final String WEB_WATCHER_PREFIX = "aw-watcher-web-";
  private static final String REGEX_CHARS = "*\\]?$^[()";

  private final String serverHost;
  private final List<String> userOrigins;

  public Guards(String serverHost, List<String> userOrigins) {
    this.serverHost = serverHost;
    this.userOrigins = List.copyOf(userOrigins);
  }

  /**
   * @param hostHeader the request's `Host`, or null when it had none
   * @param origin the request's `Origin`, or the empty string
   * @param method the verb the route is for
   * @param segments the path split on `/` with empty parts dropped
   * @return the refusal to answer with, or empty to carry on
   */
  public Optional<HttpResponse> check(String hostHeader, String origin, String method,
      List<String> segments) {
    Optional<HttpResponse> host = checkHost(hostHeader);
    if (host.isPresent()) {
      return host;
    }
    return checkOrigin(origin, method, segments);
  }

  /** R61. */
  public Optional<HttpResponse> checkHost(String hostHeader) {
    if ("0.0.0.0".equals(serverHost)) {
      // Listening on every interface, so the header says nothing about who was addressed.
      return Optional.empty();
    }
    if (hostHeader == null) {
      return Optional.of(Json.message(StatusCodes.BAD_REQUEST, "host header is missing"));
    }
    String name = hostHeader.split(":")[0];
    if (name.equals("localhost") || name.equals("127.0.0.1") || name.equals(serverHost)) {
      return Optional.empty();
    }
    return Optional.of(Json.message(StatusCodes.BAD_REQUEST,
        "host header is invalid (was " + hostHeader + ")"));
  }

  /** R62. */
  public Optional<HttpResponse> checkOrigin(String origin, String method,
      List<String> segments) {
    if (origin == null || !origin.toLowerCase(Locale.ROOT).startsWith(EXTENSION_SCHEME)) {
      return Optional.empty();
    }
    for (String pattern : userOrigins) {
      if (matchesConfiguredOrigin(origin, pattern)) {
        return Optional.empty();
      }
    }
    if (isAllowed(method, segments)) {
      return Optional.empty();
    }
    return Optional.of(Json.message(StatusCodes.FORBIDDEN,
        "You don't have the permission to access the requested resource. It is either "
            + "read-protected or not readable by the server."));
  }

  /**
   * flask-cors 4's rule: a pattern containing a regular-expression metacharacter is a regular
   * expression, and anything else is a case-insensitive string compare. The exemption has to
   * agree with the library that does the allowing, or an origin could be exempt here and
   * refused there.
   */
  static boolean matchesConfiguredOrigin(String origin, String pattern) {
    boolean looksLikeRegex = false;
    for (int i = 0; i < REGEX_CHARS.length(); i++) {
      if (pattern.indexOf(REGEX_CHARS.charAt(i)) >= 0) {
        looksLikeRegex = true;
        break;
      }
    }
    if (!looksLikeRegex) {
      return origin.equalsIgnoreCase(pattern);
    }
    try {
      return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(origin).lookingAt();
    } catch (PatternSyntaxException e) {
      return false;
    }
  }

  /**
   * The three endpoints the browser extension actually uses.
   *
   * <p>Matching is on the split segments rather than the raw path, so a percent-encoded
   * separator cannot smuggle a request past the check.
   */
  static boolean isAllowed(String method, List<String> segments) {
    if ("OPTIONS".equals(method)) {
      return isAllowedPath(segments);
    }
    if ("GET".equals(method) && segments.equals(List.of("api", "0", "info"))) {
      return true;
    }
    if ("POST".equals(method) && isWebWatcherBucket(segments)) {
      return true;
    }
    return "POST".equals(method) && isWebWatcherHeartbeat(segments);
  }

  static boolean isAllowedPath(List<String> segments) {
    return segments.equals(List.of("api", "0", "info"))
        || isWebWatcherBucket(segments)
        || isWebWatcherHeartbeat(segments);
  }

  private static boolean isWebWatcherBucket(List<String> segments) {
    return segments.size() == 4
        && segments.subList(0, 3).equals(List.of("api", "0", "buckets"))
        && segments.get(3).startsWith(WEB_WATCHER_PREFIX);
  }

  private static boolean isWebWatcherHeartbeat(List<String> segments) {
    return segments.size() == 5
        && segments.subList(0, 3).equals(List.of("api", "0", "buckets"))
        && segments.get(3).startsWith(WEB_WATCHER_PREFIX)
        && segments.get(4).equals("heartbeat");
  }

  /** R63: the origins CORS allows, in the order the original appends them. */
  public static List<String> corsOrigins(List<String> configured, boolean testing) {
    List<String> origins = new ArrayList<>(configured);
    if (testing) {
      origins.add("http://127.0.0.1:27180/*");
    }
    origins.add("moz-extension://*");
    return List.copyOf(origins);
  }

  /** The path a route is for, as segments. */
  public static List<String> segments(String... parts) {
    List<String> out = new ArrayList<>(parts.length + 2);
    out.add("api");
    out.add("0");
    for (String part : parts) {
      if (part != null && !part.isEmpty()) {
        out.add(part);
      }
    }
    return out;
  }

  /** Used by the tests to build a request's view of itself without an HTTP layer. */
  public static Map<String, Object> describe(String method, List<String> segments) {
    return Map.of("method", method, "segments", segments);
  }
}
