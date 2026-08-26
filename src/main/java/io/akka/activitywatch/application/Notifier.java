package io.akka.activitywatch.application;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Where a notification goes, and how fast — SPEC-001 §3 R141–R143.
 *
 * <p>One queue, one at a time, at least a second apart. The spacing is the whole reason the
 * queue exists: several alerts can trip on the same pass, and a desktop that is handed four
 * of them at once shows one and drops the rest.
 *
 * <p>The last step — putting a notification on the screen — is an operating-system call and
 * is out of scope by the same rule as reading the foreground window. What is in scope is
 * everything up to it: what is queued, in what order, how far apart, what it says, and what
 * `--output-only` prints instead. So the sink is injected, the port ships one that writes the
 * `--output-only` line, and a test can hold one that records.
 */
public final class Notifier {

  /**
   * @param title what the notification is called
   * @param message its body
   * @param sender the module that asked for it, where one did — R142
   */
  public record Notification(String title, String message, String sender) {

    /** R142: a notification posted by another module is attributed in its title. */
    public String displayTitle() {
      return sender == null ? title : title + " (" + sender + ")";
    }
  }

  /** R142: the depth at which a caller over HTTP is told to back off. */
  public static final int HTTP_QUEUE_LIMIT = 10;

  /** R141. */
  public static final java.time.Duration MINIMUM_GAP = java.time.Duration.ofSeconds(1);

  private final Deque<Notification> queue = new ArrayDeque<>();
  private final Consumer<Notification> sink;
  private Instant lastShown = Instant.EPOCH;

  public Notifier(Consumer<Notification> sink) {
    this.sink = sink;
  }

  /**
   * R142: a notification from another module, which may be refused.
   *
   * @return false when ten are already waiting
   */
  public synchronized boolean offer(Notification notification) {
    if (queue.size() >= HTTP_QUEUE_LIMIT) {
      return false;
    }
    queue.add(notification);
    return true;
  }

  /**
   * The service's own notifications, which are never refused.
   *
   * <p>An alert that tripped has already changed the state that says it tripped, so dropping
   * it would lose it for good; a module posting over HTTP still has the request to retry.
   */
  public synchronized void enqueue(String title, String message) {
    queue.add(new Notification(title, message, null));
  }

  public synchronized int waiting() {
    return queue.size();
  }

  /**
   * Show everything waiting, a second apart.
   *
   * @param now the clock, and what the gap is measured against
   * @param sleep how to wait; a test passes one that advances its own clock instead
   */
  public void drain(java.util.function.Supplier<Instant> now,
      Consumer<java.time.Duration> sleep) {
    while (true) {
      Notification next;
      synchronized (this) {
        next = queue.poll();
      }
      if (next == null) {
        return;
      }
      Instant due = lastShown.plus(MINIMUM_GAP);
      Instant at = now.get();
      if (due.isAfter(at)) {
        sleep.accept(java.time.Duration.between(at, due));
        lastShown = due;
      } else {
        lastShown = at;
      }
      sink.accept(next);
    }
  }

  /** R143: what `--output-only` prints, one object per line. */
  public static String outputLine(Notification notification, Instant at) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("timestamp", at.toString());
    out.put("title", notification.title());
    out.put("message", notification.message());
    out.put("app", "ActivityWatch");
    if (notification.sender() != null) {
      out.put("sender", notification.sender());
    }
    return io.akka.activitywatch.api.Json.write(out);
  }

  /** R143: the pair sent at start-up is written as one write, not two. */
  public static String outputLines(List<Notification> notifications, Instant at) {
    List<String> lines = new ArrayList<>(notifications.size());
    for (Notification notification : notifications) {
      lines.add(outputLine(notification, at));
    }
    return lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
  }
}
