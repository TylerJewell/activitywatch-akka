package io.akka.activitywatch.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Selecting events by what is in them, and rewriting what is in them —
 * SPEC-001 §3 R25–R28.
 */
public final class Filters {

  private static final Pattern LEADING_DOT = Pattern.compile("^(●|\\*)\\s*");
  private static final Pattern PARENS_PREFIX = Pattern.compile("^\\([0-9]+\\)\\s*");
  private static final Pattern FPS = Pattern.compile("FPS:\\s+[0-9.]+");

  private Filters() {}

  /** R25. An event without the key never satisfies the predicate, either way round. */
  public static List<Event> filterKeyvals(List<Event> events, String key, List<Object> values,
      boolean exclude) {
    List<Event> out = new ArrayList<>();
    for (Event event : events) {
      boolean hit = event.data().containsKey(key) && values.contains(event.data().get(key));
      if (hit != exclude) {
        out.add(event);
      }
    }
    return List.copyOf(out);
  }

  /**
   * R26. The pattern is searched for anywhere in the value, not anchored, and a value that is
   * not a string is an error rather than a miss — which is what the original's `re.findall`
   * does when handed a null.
   */
  public static List<Event> filterKeyvalsRegex(List<Event> events, String key, String regex) {
    Pattern pattern = Pattern.compile(regex);
    List<Event> out = new ArrayList<>();
    for (Event event : events) {
      if (!event.data().containsKey(key)) {
        continue;
      }
      Object value = event.data().get(key);
      if (!(value instanceof String text)) {
        throw new IllegalArgumentException(
            "expected string or bytes-like object, got '"
                + (value == null ? "NoneType" : value.getClass().getSimpleName()) + "'");
      }
      if (pattern.matcher(text).find()) {
        out.add(event);
      }
    }
    return List.copyOf(out);
  }

  /**
   * R27. A leading {@code (n)} goes from any key; the other two rules only apply to a title on
   * an event that also names an app, which is how the original tells a window title from
   * anything else.
   */
  public static List<Event> simplifyString(List<Event> events, String key) {
    List<Event> out = new ArrayList<>(events.size());
    for (Event event : events) {
      Object value = event.data().get(key);
      if (!(value instanceof String text)) {
        out.add(event);
        continue;
      }
      String simplified = PARENS_PREFIX.matcher(text).replaceAll("");
      if ("title".equals(key) && event.data().containsKey("app")) {
        simplified = FPS.matcher(simplified).replaceAll(Matcher.quoteReplacement("FPS: ..."));
        simplified = LEADING_DOT.matcher(simplified).replaceAll("");
      }
      Map<String, Object> data = new LinkedHashMap<>(event.data());
      data.put(key, simplified);
      out.add(event.withData(data));
    }
    return List.copyOf(out);
  }

  /**
   * R28. Six keys derived from a URL.
   *
   * <p>A leading {@code www.} is stripped as a prefix only, so {@code sub.www.example.com}
   * keeps it. Where a URL has no host but does have a scheme the scheme stands in as the
   * domain, so {@code file://} and {@code about:} URLs do not all cluster as one empty domain.
   */
  public static List<Event> splitUrlEvents(List<Event> events) {
    List<Event> out = new ArrayList<>(events.size());
    for (Event event : events) {
      Object raw = event.data().get("url");
      if (!(raw instanceof String url)) {
        out.add(event);
        continue;
      }
      Parsed parsed = parse(url);
      Map<String, Object> data = new LinkedHashMap<>(event.data());
      data.put("$protocol", parsed.scheme);
      String domain;
      if (!parsed.netloc.isEmpty()) {
        domain = parsed.netloc.startsWith("www.") ? parsed.netloc.substring(4) : parsed.netloc;
      } else if (!parsed.scheme.isEmpty()) {
        domain = parsed.scheme;
      } else {
        domain = "";
      }
      data.put("$domain", domain);
      data.put("$path", parsed.path);
      data.put("$params", parsed.params);
      data.put("$options", parsed.query);
      data.put("$identifier", parsed.fragment);
      out.add(event.withData(data));
    }
    return List.copyOf(out);
  }

  /**
   * The pieces Python's `urllib.parse.urlparse` hands back.
   *
   * <p>Java's {@link URI} splits differently — it has no notion of the {@code ;params} segment
   * and refuses several strings `urlparse` accepts — so the split is done here against the
   * same rules the original's answers show.
   */
  static Parsed parse(String url) {
    String rest = url;
    String fragment = "";
    int hash = rest.indexOf('#');
    if (hash >= 0) {
      fragment = rest.substring(hash + 1);
      rest = rest.substring(0, hash);
    }
    String query = "";
    int question = rest.indexOf('?');
    if (question >= 0) {
      query = rest.substring(question + 1);
      rest = rest.substring(0, question);
    }
    String scheme = "";
    int colon = rest.indexOf(':');
    if (colon > 0 && isScheme(rest.substring(0, colon))) {
      scheme = rest.substring(0, colon).toLowerCase(java.util.Locale.ROOT);
      rest = rest.substring(colon + 1);
    }
    String netloc = "";
    if (rest.startsWith("//")) {
      int slash = indexOfAny(rest, 2, '/', '?', '#');
      netloc = slash < 0 ? rest.substring(2) : rest.substring(2, slash);
      rest = slash < 0 ? "" : rest.substring(slash);
    }
    String params = "";
    int semicolon = rest.lastIndexOf(';');
    // urlparse only treats a `;` in the last path segment as parameters.
    if (semicolon >= 0 && rest.indexOf('/', semicolon) < 0) {
      params = rest.substring(semicolon + 1);
      rest = rest.substring(0, semicolon);
    }
    return new Parsed(scheme, netloc, rest, params, query, fragment);
  }

  private static boolean isScheme(String candidate) {
    if (candidate.isEmpty() || !Character.isLetter(candidate.charAt(0))) {
      return false;
    }
    for (int i = 1; i < candidate.length(); i++) {
      char c = candidate.charAt(i);
      if (!Character.isLetterOrDigit(c) && c != '+' && c != '-' && c != '.') {
        return false;
      }
    }
    return true;
  }

  private static int indexOfAny(String text, int from, char... chars) {
    for (int i = from; i < text.length(); i++) {
      for (char c : chars) {
        if (text.charAt(i) == c) {
          return i;
        }
      }
    }
    return -1;
  }

  record Parsed(String scheme, String netloc, String path, String params, String query,
      String fragment) {}
}
