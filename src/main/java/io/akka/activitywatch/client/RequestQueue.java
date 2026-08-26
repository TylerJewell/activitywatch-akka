package io.akka.activitywatch.client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Heartbeats held on disk until the server takes them — SPEC-001 §3 R82, R83.
 *
 * <p>A watcher outlives the server it writes to: the server is restarted, the machine sleeps,
 * an upgrade happens. Everything the watcher recorded in that window is kept here and sent
 * when the server comes back, so a restart costs no data.
 *
 * <p>Only heartbeats. Anything else a client does is a question whose answer it is waiting
 * for, and queuing a question nobody will read the answer to is not a kindness.
 */
public class RequestQueue {

  /** Bumped when the file's format changes, because an old file cannot be read as a new one. */
  private static final int VERSION = 1;

  private static final long RECONNECT_INTERVAL_MILLIS = 10_000;
  private static final long RETRY_PAUSE_MILLIS = 500;
  private static final long IDLE_POLL_MILLIS = 200;

  private final ActivityWatchClient client;
  private final Path file;
  private final List<String[]> registeredBuckets = new CopyOnWriteArrayList<>();
  private final AtomicBoolean stopping = new AtomicBoolean();
  private final Object lock = new Object();
  private volatile Thread worker;
  private volatile boolean connected;

  public RequestQueue(ActivityWatchClient client, String clientName, boolean testing,
      Path directory) {
    this.client = client;
    Path queued = directory.resolve("queued");
    try {
      Files.createDirectories(queued);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    this.file = queued.resolve(
        clientName + (testing ? "-testing" : "") + ".v" + VERSION + ".queue");
  }

  public Path file() {
    return file;
  }

  /** R82: only heartbeats, and only ones that can be written down first. */
  public void add(String endpoint, Map<String, Object> body) {
    if (!endpoint.contains("/heartbeat")) {
      throw new IllegalArgumentException("only heartbeats are queued, not " + endpoint);
    }
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("endpoint", endpoint);
    row.put("data", body);
    synchronized (lock) {
      try {
        Files.writeString(file, io.akka.activitywatch.api.Json.write(row) + "\n",
            StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  public void registerBucket(String bucketId, String eventType) {
    registeredBuckets.add(new String[] {bucketId, eventType});
    if (connected) {
      try {
        client.createBucket(bucketId, eventType, false);
      } catch (RuntimeException e) {
        connected = false;
      }
    }
  }

  public int size() {
    synchronized (lock) {
      return readAll().size();
    }
  }

  public void start() {
    if (worker != null && worker.isAlive()) {
      return;
    }
    stopping.set(false);
    Thread thread = new Thread(this::run, "aw-request-queue");
    thread.setDaemon(true);
    worker = thread;
    thread.start();
  }

  public void stop() {
    stopping.set(true);
    Thread thread = worker;
    if (thread != null) {
      thread.interrupt();
    }
  }

  private void run() {
    while (!stopping.get()) {
      while (!stopping.get() && !tryConnect()) {
        sleep(RECONNECT_INTERVAL_MILLIS);
      }
      while (!stopping.get() && connected) {
        dispatchOne();
      }
    }
  }

  private boolean tryConnect() {
    try {
      for (String[] bucket : registeredBuckets) {
        client.createBucket(bucket[0], bucket[1], false);
      }
      connected = true;
    } catch (RuntimeException e) {
      connected = false;
    }
    return connected;
  }

  /**
   * R82: what a failure means.
   *
   * <p>A refused connection is worth retrying, and so is a server that broke — it may be
   * restarted into a state where the same request works. A 400 is not: the payload will be
   * just as bad next time, and retrying it forever would block every heartbeat behind it.
   */
  private void dispatchOne() {
    Map<String, Object> request;
    synchronized (lock) {
      List<Map<String, Object>> pending = readAll();
      if (pending.isEmpty()) {
        request = null;
      } else {
        request = pending.get(0);
      }
    }
    if (request == null) {
      sleep(IDLE_POLL_MILLIS);
      return;
    }

    try {
      client.post(String.valueOf(request.get("endpoint")), request.get("data"));
    } catch (ActivityWatchClient.ServerError e) {
      if (e.status() == 400) {
        drop();
        return;
      }
      if (e.status() >= 500) {
        sleep(RETRY_PAUSE_MILLIS);
        return;
      }
      drop();
      return;
    } catch (RuntimeException e) {
      connected = false;
      sleep(RETRY_PAUSE_MILLIS);
      return;
    }
    drop();
  }

  private void drop() {
    synchronized (lock) {
      List<Map<String, Object>> pending = readAll();
      if (pending.isEmpty()) {
        return;
      }
      pending.remove(0);
      StringBuilder rewritten = new StringBuilder();
      for (Map<String, Object> row : pending) {
        rewritten.append(io.akka.activitywatch.api.Json.write(row)).append('\n');
      }
      try {
        Files.writeString(file, rewritten.toString(), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  private List<Map<String, Object>> readAll() {
    if (!Files.isRegularFile(file)) {
      return new ArrayList<>();
    }
    try {
      List<Map<String, Object>> out = new ArrayList<>();
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        if (!line.isBlank()) {
          out.add(io.akka.activitywatch.api.Json.readObject(line));
        }
      }
      return out;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
