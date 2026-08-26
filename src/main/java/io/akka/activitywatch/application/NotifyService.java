package io.akka.activitywatch.application;

import io.akka.activitywatch.domain.CategoryAlert;
import io.akka.activitywatch.domain.DefaultClasses;
import io.akka.activitywatch.domain.NotifyConfig;
import io.akka.activitywatch.domain.NotifyRules;
import io.akka.activitywatch.domain.Queries;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The notification service — SPEC-001 §3 R118–R121, R126–R140.
 *
 * <p>It asks the server what the day went on, decides what is worth saying, and hands it to
 * {@link Notifier}. Everything it decides is in {@code domain/NotifyRules} and
 * {@code domain/CategoryAlert}; what is here is when to ask, what to ask for, and what to do
 * with the answer.
 *
 * <p>Not a component. It is one long-running process alongside a server, the way the module
 * it is a port of is, and it reaches its server over the same HTTP a watcher does — so it
 * works against this port and against the original without knowing which it is talking to.
 */
public final class NotifyService {

  /** R120. */
  public static final Duration TIME_CACHE_TTL = Duration.ofSeconds(60);

  /** R121: five times the other, because the categories change when a person edits them. */
  public static final Duration CLASSES_CACHE_TTL = Duration.ofSeconds(300);

  /** R138: how far back activity is looked for, and how much of it counts. */
  public static final Duration ACTIVITY_WINDOW = Duration.ofMinutes(3);
  public static final Duration ACTIVITY_THRESHOLD = Duration.ofSeconds(10);

  private final NotifySource source;
  private final NotifyConfig config;
  private final Notifier notifier;
  private final ZoneId zone;
  private final java.util.function.Supplier<Instant> clock;

  private final Map<String, Cached<Map<String, Double>>> timeCache = new LinkedHashMap<>();
  private Cached<List<Object>> classesCache;

  private record Cached<T>(Instant at, T value) {}

  public NotifyService(NotifySource source, NotifyConfig config, Notifier notifier,
      ZoneId zone, java.util.function.Supplier<Instant> clock) {
    this.source = source;
    this.config = config;
    this.notifier = notifier;
    this.zone = zone;
    this.clock = clock;
  }

  public Notifier notifier() {
    return notifier;
  }

  public NotifyConfig config() {
    return config;
  }

  // ------------------------------------------------------------------ reading the day

  /**
   * R121: the categories a person set, or the ones every client falls back to.
   *
   * <p>An empty setting and a server that cannot be reached both give the defaults, which is
   * what makes a summary say something on a machine nobody has configured.
   */
  @SuppressWarnings("unchecked")
  public List<Object> classes() {
    Instant now = clock.get();
    if (classesCache != null
        && Duration.between(classesCache.at(), now).compareTo(CLASSES_CACHE_TTL) < 0) {
      return classesCache.value();
    }
    Object setting = source.setting("classes");
    List<Object> classes =
        setting instanceof List<?> list ? List.copyOf((List<Object>) list) : List.of();
    classesCache = new Cached<>(now, classes);
    return classes;
  }

  private List<Object> classesOrDefaults() {
    List<Object> classes = classes();
    return classes.isEmpty() ? DefaultClasses.defaults() : classes;
  }

  private String alwaysActivePattern() {
    return source.setting("always_active_pattern") instanceof String text ? text : null;
  }

  /** R118: the query the service asks, for one day. */
  public String timeQuery() {
    Queries.Params params = Queries.Params
        .desktop("aw-watcher-window_" + source.hostname(),
            "aw-watcher-afk_" + source.hostname(), classesOrDefaults())
        .withAlwaysActivePattern(alwaysActivePattern());
    return Queries.canonicalEvents(params)
        + "\nduration = sum_durations(events);\n"
        + "cat_events = sort_by_duration(merge_events_by_keys(events, [\"$category\"]));\n"
        + "RETURN = {\"duration\": duration, \"cat_events\": cat_events};";
  }

  /**
   * R119, R120: how long each category had, on the day the instant falls in.
   *
   * @param date null for today
   */
  @SuppressWarnings("unchecked")
  public Map<String, Double> categoryTime(Instant date, NotifyRules.Aggregation aggregation) {
    String key = date == null ? "today" : date.toString();
    Instant now = clock.get();
    Cached<Map<String, Double>> hit = timeCache.get(key);
    Map<String, Double> raw;
    if (hit != null && Duration.between(hit.at(), now).compareTo(TIME_CACHE_TTL) < 0) {
      raw = hit.value();
    } else {
      raw = readCategoryTime(date == null ? now : date);
      timeCache.put(key, new Cached<>(now, raw));
    }
    return switch (aggregation) {
      case NONE -> raw;
      case TOP_LEVEL -> NotifyRules.aggregateTopLevel(raw);
      case ALL_LEVELS -> NotifyRules.aggregateAllLevels(raw);
    };
  }

  @SuppressWarnings("unchecked")
  private Map<String, Double> readCategoryTime(Instant date) {
    Instant start = NotifyRules.dayStart(date, zone);
    Object answer = source.query(timeQuery(),
        List.<Instant[]>of(new Instant[] {start, start.plus(Duration.ofDays(1))}));
    Map<String, Double> out = new LinkedHashMap<>();
    Map<String, Object> first = firstAnswer(answer);
    if (first == null) {
      out.put(NotifyRules.ALL, 0.0);
      return out;
    }
    if (first.get("cat_events") instanceof List<?> events) {
      for (Object item : events) {
        if (!(item instanceof Map<?, ?> event)) {
          continue;
        }
        Object data = event.get("data");
        Object duration = event.get("duration");
        if (!(data instanceof Map<?, ?> values) || !(duration instanceof Number seconds)) {
          continue;
        }
        Object category = values.get("$category");
        if (category == null) {
          continue;
        }
        out.merge(NotifyRules.categoryNameOf(category), seconds.doubleValue(), Double::sum);
      }
    }
    if (first.get("duration") instanceof Number total) {
      out.put(NotifyRules.ALL, total.doubleValue());
    } else if (!out.isEmpty()) {
      out.put(NotifyRules.ALL, out.values().stream().mapToDouble(Double::doubleValue).sum());
    }
    if (out.isEmpty()) {
      out.put(NotifyRules.ALL, 0.0);
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> firstAnswer(Object answer) {
    if (answer instanceof List<?> list && !list.isEmpty()
        && list.get(0) instanceof Map<?, ?> first) {
      return (Map<String, Object>) first;
    }
    return null;
  }

  // ------------------------------------------------------------------ what it says

  /** R126. */
  public void sendCheckin(String title, Instant date) {
    List<NotifyRules.Line> lines = NotifyRules.topCategories(
        categoryTime(date, NotifyRules.Aggregation.TOP_LEVEL), 0.02, 4);
    if (!lines.isEmpty()) {
      notifier.enqueue(title, NotifyRules.summaryMessage(lines));
    }
  }

  /** R127. */
  public void sendDetailedCheckin(String title, Instant date) {
    List<NotifyRules.Line> lines = NotifyRules.topCategories(
        categoryTime(date, NotifyRules.Aggregation.ALL_LEVELS), 0.02, 10);
    if (!lines.isEmpty()) {
      notifier.enqueue(title, NotifyRules.summaryMessage(lines));
    }
  }

  /** R129, R130. */
  public void sendProductivityScoreYesterday() {
    Instant yesterday = clock.get().minus(Duration.ofDays(1));
    NotifyRules.Productivity productivity = NotifyRules.productivity(
        categoryTime(yesterday, NotifyRules.Aggregation.NONE), classes());
    if (productivity != null) {
      notifier.enqueue("Productivity Score", NotifyRules.productivityMessage(productivity));
    }
  }

  /** R136: yesterday, then today, and the score a moment later. */
  public void sendInitialCheckins() {
    sendCheckin("Time yesterday", clock.get().minus(Duration.ofDays(1)));
    sendCheckin("Time today", null);
  }

  /** R138. */
  public Boolean active() {
    Instant now = clock.get();
    Object answer = source.query(activeQuery(),
        List.<Instant[]>of(new Instant[] {now.minus(ACTIVITY_WINDOW), now}));
    Map<String, Object> first = firstAnswer(answer);
    if (first == null || !(first.get("duration") instanceof Number duration)) {
      return null;
    }
    return duration.doubleValue() > ACTIVITY_THRESHOLD.getSeconds();
  }

  private String activeQuery() {
    Queries.Params params = Queries.Params
        .desktop("aw-watcher-window_" + source.hostname(),
            "aw-watcher-afk_" + source.hostname(), classesOrDefaults())
        .withAlwaysActivePattern(alwaysActivePattern());
    return Queries.canonicalEvents(params)
        + "\nduration = sum_durations(events);\nRETURN = {\"duration\": duration};";
  }

  /** R137. */
  public void hourlyCheckin() {
    if (Boolean.TRUE.equals(active())) {
      sendCheckin("Hourly summary", null);
    }
  }

  /** R139: the message a new day is greeted with. */
  public static String newDayMessage(LocalDate day) {
    return "It is " + day.format(DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH))
        + ", " + day;
  }

  /** R139: the day the service considers it to be, which turns over four hours after midnight. */
  public LocalDate reportingDay(Instant now) {
    return now.minus(NotifyRules.DAY_OFFSET).atZone(zone).toLocalDate();
  }

  /** R140. */
  public boolean serverAvailable() {
    return source.available();
  }

  /** R137: the next whole hour, local. */
  public Instant topOfNextHour(Instant now) {
    return now.atZone(zone).plusHours(1).withMinute(0).withSecond(0).withNano(0).toInstant();
  }

  /**
   * R135–R140: the service, running, on one schedule.
   *
   * <p>The original gives each of these five a thread of its own. One loop that wakes every
   * ten seconds and asks each in turn whether it is due says the same thing in one place, and
   * puts two schedules falling due together in a fixed order rather than in whichever order
   * the operating system woke their threads.
   *
   * @param stopped asked once a pass; true ends the loop
   * @param sleep how to wait, so a test can drive the loop without one
   */
  public void run(java.util.function.BooleanSupplier stopped,
      java.util.function.Consumer<Duration> sleep) {
    List<CategoryAlert> alerts = alerts();
    try {
      sendInitialCheckins();
      passOverAlerts(alerts, true);
    } catch (RuntimeException e) {
      // A server that is not ready yet is not a reason to give up: the loop below reads it
      // again in ten seconds, and the monitor is what says it was down.
    }

    Instant scoreDue = clock.get().plusSeconds(5);
    Instant nextHour = topOfNextHour(clock.get());
    Instant nextDayCheck = clock.get();
    LocalDate lastDay = reportingDay(clock.get());
    boolean available = true;

    while (!stopped.getAsBoolean()) {
      Instant now = clock.get();
      try {
        if (scoreDue != null && !now.isBefore(scoreDue)) {
          scoreDue = null;
          if (config.productivityScore()) {
            sendProductivityScoreYesterday();
          }
        }
        passOverAlerts(alerts, false);

        if (config.hourlyCheckins() && !now.isBefore(nextHour)) {
          hourlyCheckin();
          nextHour = topOfNextHour(now);
        }

        if (config.newDayGreetings() && !now.isBefore(nextDayCheck)) {
          nextDayCheck = now.plus(Duration.ofMinutes(5));
          LocalDate day = reportingDay(now);
          if (!day.equals(lastDay) && Boolean.TRUE.equals(active())) {
            notifier.enqueue("New day", newDayMessage(day));
            scoreDue = now.plusSeconds(5);
            lastDay = day;
          }
        }

        if (config.serverMonitoring()) {
          boolean nowAvailable = serverAvailable();
          if (nowAvailable != available) {
            notifier.enqueue(nowAvailable ? "Server Available" : "Server Unavailable",
                nowAvailable ? "ActivityWatch server is back online."
                    : "ActivityWatch server is down. Data may not be saved!");
            available = nowAvailable;
          }
        }

      } catch (RuntimeException e) {
        // A pass that failed is a pass. The next one is ten seconds away, and the server it
        // reads may well be back by then — which is what the monitor exists to say.
        available = false;
      }
      // The schedules are ten seconds apart; the queue is not. A module posting over HTTP
      // is waiting for its notification to appear, not for the next time an alert is read,
      // so the wait is spent draining rather than sleeping through it.
      for (int slice = 0; slice < 10 && !stopped.getAsBoolean(); slice++) {
        notifier.drain(clock, sleep);
        sleep.accept(Duration.ofSeconds(1));
      }
    }
  }

  /** R131–R135: one alert per configured category, in the order they were declared. */
  public List<CategoryAlert> alerts() {
    List<CategoryAlert> alerts = new ArrayList<>(config.alerts().size());
    for (NotifyConfig.Alert alert : config.alerts()) {
      alerts.add(new CategoryAlert(alert, zone));
    }
    return alerts;
  }

  /**
   * R133–R135: one pass over the alerts.
   *
   * @param silent true for the pass at start-up, which records what has already been passed
   *     without announcing it — otherwise starting the service after lunch announces lunch
   */
  public void passOverAlerts(List<CategoryAlert> alerts, boolean silent) {
    for (CategoryAlert alert : alerts) {
      alert.update(clock.get(),
          () -> categoryTime(null, NotifyRules.Aggregation.ALL_LEVELS));
      CategoryAlert.Announcement announcement = alert.check();
      if (announcement != null && !silent) {
        notifier.enqueue(announcement.title(), announcement.message());
      }
    }
  }
}
