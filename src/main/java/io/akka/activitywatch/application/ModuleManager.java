package io.akka.activitywatch.application;

import io.akka.activitywatch.domain.Dirs;
import io.akka.activitywatch.domain.ModuleRules;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finding the pieces of the system and running them — SPEC-001 §3 R100–R106.
 *
 * <p>The rules about what counts as a module and in what order they start are in
 * {@link ModuleRules}, where they can be checked without a filesystem. This is the part that
 * touches one: it walks directories, spawns processes and watches them.
 *
 * <p>The probe before starting a server is the one behaviour here worth reading twice. A
 * server may already be running — started by the operating system, by a container, by a
 * person — and starting a second one only produces a bound-port error and a confusing state.
 * So a server module that answers on its port is treated as started and, importantly, is
 * never stopped: this did not start it and does not get to end it.
 */
public class ModuleManager {

  private static final long PROBE_CACHE_MILLIS = 1000;

  private final boolean testing;
  private final int serverPort;
  private final List<Path> bundledPaths;
  private final Map<String, Process> processes = new ConcurrentHashMap<>();
  private final Map<String, Boolean> externalServers = new ConcurrentHashMap<>();
  private final Map<String, Boolean> started = new ConcurrentHashMap<>();
  private final Map<String, long[]> probeCache = new ConcurrentHashMap<>();
  private volatile List<ModuleRules.Module> discovered = List.of();

  public ModuleManager(boolean testing, int serverPort, List<Path> bundledPaths) {
    this.testing = testing;
    this.serverPort = serverPort;
    this.bundledPaths = List.copyOf(bundledPaths);
  }

  /** What a caller sees about one module. */
  public record Status(String name, String path, String origin, boolean started,
      boolean alive, boolean external) {}

  public List<ModuleRules.Module> discover() {
    List<ModuleRules.Module> found = new ArrayList<>();
    for (Path directory : bundledPaths) {
      found.addAll(walk(directory, ModuleRules.Origin.BUNDLED));
    }
    for (Path directory : executablePath()) {
      for (ModuleRules.Module candidate : walk(directory, ModuleRules.Origin.SYSTEM)) {
        // R101: the first match on the search path wins for a given name.
        boolean known = found.stream().anyMatch(m -> m.name().equals(candidate.name())
            && m.origin() == ModuleRules.Origin.SYSTEM);
        if (!known) {
          found.add(candidate);
        }
      }
    }
    discovered = ModuleRules.filterModules(found);
    return discovered;
  }

  public List<ModuleRules.Module> modules() {
    if (discovered.isEmpty()) {
      discover();
    }
    return discovered;
  }

  public List<Status> statuses() {
    List<Status> out = new ArrayList<>();
    for (ModuleRules.Module module : modules()) {
      out.add(new Status(module.name(), module.path(), module.origin().name().toLowerCase(
          Locale.ROOT), Boolean.TRUE.equals(started.get(module.name())), alive(module.name()),
          Boolean.TRUE.equals(externalServers.get(module.name()))));
    }
    return out;
  }

  /** R102, R103. */
  public List<String> autostart(List<String> requested) {
    List<String> names = new ArrayList<>(ModuleRules.DEFAULT_AUTOSTART);
    if (requested != null && !requested.isEmpty()) {
      names = requested;
    }
    List<String> discoveredNames = modules().stream().map(ModuleRules.Module::name).toList();
    List<String> order = ModuleRules.autostartOrder(names, discoveredNames);
    for (String name : order) {
      start(name);
    }
    return order;
  }

  /** R101, R103. */
  public boolean start(String name) {
    ModuleRules.Module module = ModuleRules.preferred(modules(), name);
    if (module == null) {
      return false;
    }
    if (isServer(name) && probe(name, 1000)) {
      externalServers.put(name, true);
      started.put(name, true);
      return true;
    }
    if (alive(name)) {
      return true;
    }
    List<String> command = new ArrayList<>();
    command.add(module.path());
    if (testing) {
      command.add("--testing");
    }
    try {
      // Output is inherited rather than piped: a pipe nobody reads fills and the child
      // blocks writing to it, which is a hang with no message anywhere.
      Process process = new ProcessBuilder(command)
          .inheritIO()
          .start();
      processes.put(name, process);
      started.put(name, true);
      return true;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public boolean stop(String name) {
    if (Boolean.TRUE.equals(externalServers.remove(name))) {
      // R103: this did not start it, so it does not stop it.
      started.put(name, false);
      return true;
    }
    Process process = processes.remove(name);
    started.put(name, false);
    if (process == null) {
      return false;
    }
    process.destroy();
    try {
      process.waitFor();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return true;
  }

  public void stopAll() {
    for (String name : new ArrayList<>(processes.keySet())) {
      stop(name);
    }
    for (String name : new ArrayList<>(externalServers.keySet())) {
      stop(name);
    }
  }

  public boolean alive(String name) {
    if (Boolean.TRUE.equals(externalServers.get(name))) {
      boolean answering = probeCached(name);
      if (!answering) {
        externalServers.remove(name);
      }
      return answering;
    }
    Process process = processes.get(name);
    return process != null && process.isAlive();
  }

  /** R106. */
  public List<String> unexpectedStops() {
    List<String> out = new ArrayList<>();
    for (Map.Entry<String, Boolean> entry : started.entrySet()) {
      if (Boolean.TRUE.equals(entry.getValue()) && !alive(entry.getKey())) {
        out.add(entry.getKey());
      }
    }
    return out;
  }

  /** The newest log file a module wrote, or a note that it has none. */
  public String log(String name) {
    Path directory = Dirs.logDir(name);
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
      Path newest = null;
      for (Path entry : entries) {
        String filename = entry.getFileName().toString();
        if (!filename.contains(name)) {
          continue;
        }
        if (testing != filename.contains("testing")) {
          continue;
        }
        if (newest == null || filename.compareTo(newest.getFileName().toString()) > 0) {
          newest = entry;
        }
      }
      return newest == null ? "No logfile found" : Files.readString(newest);
    } catch (IOException e) {
      return "No logfile found";
    }
  }

  private static boolean isServer(String name) {
    return name.equals("aw-server") || name.equals("aw-server-rust");
  }

  private boolean probeCached(String name) {
    long now = System.currentTimeMillis();
    long[] cached = probeCache.get(name);
    if (cached != null && now - cached[0] < PROBE_CACHE_MILLIS) {
      return cached[1] == 1;
    }
    boolean answering = probe(name, 200);
    probeCache.put(name, new long[] {now, answering ? 1 : 0});
    return answering;
  }

  /** R103, R104. */
  private boolean probe(String name, int timeoutMillis) {
    if (!isServer(name)) {
      return false;
    }
    try {
      HttpURLConnection connection = (HttpURLConnection) URI
          .create("http://localhost:" + serverPort + "/api/0/info").toURL().openConnection();
      connection.setConnectTimeout(timeoutMillis);
      connection.setReadTimeout(timeoutMillis);
      connection.setRequestMethod("GET");
      int status = connection.getResponseCode();
      connection.disconnect();
      return status > 0;
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }

  private List<ModuleRules.Module> walk(Path directory, ModuleRules.Origin origin) {
    List<ModuleRules.Module> out = new ArrayList<>();
    if (directory == null || !Files.isDirectory(directory)) {
      return out;
    }
    boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "aw-*")) {
      for (Path entry : entries) {
        String filename = entry.getFileName().toString();
        String name = ModuleRules.filenameToName(filename, windows);
        if (ModuleRules.IGNORED.contains(name)) {
          continue;
        }
        if (ModuleRules.isExecutable(filename, windows, Files.isRegularFile(entry),
            Files.isExecutable(entry))) {
          out.add(new ModuleRules.Module(name, entry.toAbsolutePath().toString(), origin));
        } else if (Files.isDirectory(entry) && origin == ModuleRules.Origin.BUNDLED) {
          out.addAll(walk(entry, origin));
        }
      }
    } catch (IOException e) {
      return out;
    }
    return out;
  }

  private static List<Path> executablePath() {
    String path = System.getenv("PATH");
    if (path == null || path.isBlank()) {
      return List.of();
    }
    List<Path> out = new ArrayList<>();
    for (String entry : path.split(java.io.File.pathSeparator)) {
      if (!entry.isBlank()) {
        out.add(Paths.get(entry));
      }
    }
    return out;
  }

  /** Where a bundled module would be: beside this build, and one level up. */
  public static List<Path> defaultBundledPaths() {
    List<Path> out = new ArrayList<>();
    Optional<Path> here = ownDirectory();
    here.ifPresent(out::add);
    here.map(Path::getParent).ifPresent(out::add);
    return out;
  }

  private static Optional<Path> ownDirectory() {
    try {
      Path source = Paths.get(ModuleManager.class.getProtectionDomain().getCodeSource()
          .getLocation().toURI());
      return Optional.ofNullable(Files.isDirectory(source) ? source : source.getParent());
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  /** The settings a manager is built from, read the way the original reads them. */
  public static Map<String, Object> defaults() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("autostart_modules", ModuleRules.DEFAULT_AUTOSTART);
    return out;
  }
}
