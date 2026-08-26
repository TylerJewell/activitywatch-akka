package io.akka.activitywatch.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.activitywatch.domain.ModuleRules;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Finding the pieces of the system and running them — SPEC-001 §3 R100–R106.
 *
 * <p>Discovery is checked against a directory that really exists, because the rules are about
 * files: what a name ending in `.desktop` means, which suffixes are stripped, and which files
 * are executable. Starting a process is checked by starting one — a real one, that exits by
 * itself — because "the process is alive" is not a fact a stand-in can report.
 */
class ModuleManagerTest {

  private static boolean windows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  private static ModuleManager manager(Path bundled) {
    return new ModuleManager(false, 1, List.of(bundled));
  }

  private static Path executable(Path directory, String name) throws IOException {
    Path file = directory.resolve(name);
    Files.writeString(file, windows() ? "@echo off\r\nping -n 3 127.0.0.1 > nul\r\n"
        : "#!/bin/sh\nsleep 2\n");
    file.toFile().setExecutable(true);
    return file;
  }

  @Test
  void aFileThatIsNotExecutableIsNotAModule(@TempDir Path directory) throws IOException {
    Files.writeString(directory.resolve("aw-watcher-notes.txt"), "not a module");
    assertEquals(List.of(), manager(directory).discover().stream()
        .map(ModuleRules.Module::name).toList());
  }

  @Test
  void theToolsAreNotModules(@TempDir Path directory) throws IOException {
    for (String name : List.of("aw-cli", "aw-client", "aw-qt")) {
      executable(directory, name + (windows() ? ".bat" : ""));
    }
    executable(directory, "aw-watcher-real" + (windows() ? ".bat" : ""));
    assertEquals(List.of("aw-watcher-real"), manager(directory).discover().stream()
        .map(ModuleRules.Module::name).toList());
  }

  @Test
  void aDesktopEntryIsNotAModuleWhateverItsPermissions() {
    assertFalse(ModuleRules.isExecutable("aw-qt.desktop", false, true, true));
    assertEquals("aw-qt.desktop", ModuleRules.filenameToName("aw-qt.desktop", true),
        "the suffix is left on, which is how it stays in the ignore list");
  }

  @Test
  void aBundledModuleIsPreferredOverASystemOneOfTheSameName() {
    List<ModuleRules.Module> both = List.of(
        new ModuleRules.Module("aw-server", "/system/aw-server", ModuleRules.Origin.SYSTEM),
        new ModuleRules.Module("aw-server", "/bundled/aw-server", ModuleRules.Origin.BUNDLED));
    assertEquals("/bundled/aw-server", ModuleRules.preferred(both, "aw-server").path());
  }

  @Test
  void listingBothServersStartsOnlyTheRustOne() {
    assertEquals(List.of("aw-server-rust", "aw-watcher-afk"),
        ModuleRules.autostartOrder(
            List.of("aw-watcher-afk", "aw-server", "aw-server-rust"),
            List.of("aw-server", "aw-server-rust", "aw-watcher-afk")));
  }

  @Test
  void aServerAlwaysGoesFirst() {
    assertEquals("aw-server", ModuleRules.autostartOrder(
        List.of("aw-watcher-window", "aw-watcher-afk", "aw-server"),
        List.of("aw-server", "aw-watcher-afk", "aw-watcher-window")).get(0));
  }

  @Test
  void aNameThatIsNotAModuleIsSkippedRatherThanFailing() {
    assertEquals(List.of("aw-server"), ModuleRules.autostartOrder(
        List.of("aw-server", "not-a-module"), List.of("aw-server")));
    assertEquals(List.of("not-a-module"),
        ModuleRules.unknown(List.of("aw-server", "not-a-module"), List.of("aw-server")));
  }

  @Test
  void aModuleThatIsStartedIsAliveAndThenIsNot(@TempDir Path directory) throws Exception {
    executable(directory, "aw-watcher-short" + (windows() ? ".bat" : ""));
    ModuleManager manager = manager(directory);
    assertTrue(manager.start("aw-watcher-short"));
    assertTrue(manager.alive("aw-watcher-short"));
    assertTrue(manager.stop("aw-watcher-short"));
    assertFalse(manager.alive("aw-watcher-short"));
  }

  @Test
  void aModuleThatDiedOnItsOwnIsAnUnexpectedStop(@TempDir Path directory) throws Exception {
    Path file = directory.resolve("aw-watcher-brief" + (windows() ? ".bat" : ""));
    Files.writeString(file, windows() ? "@echo off\r\nexit\r\n" : "#!/bin/sh\nexit 0\n");
    file.toFile().setExecutable(true);

    ModuleManager manager = manager(directory);
    manager.start("aw-watcher-brief");
    for (int attempt = 0; attempt < 100 && manager.alive("aw-watcher-brief"); attempt++) {
      Thread.sleep(50);
    }
    assertEquals(List.of("aw-watcher-brief"), manager.unexpectedStops(),
        "a module the manager still thinks it is running, but is not");
  }

  @Test
  void startingSomethingThatIsNotThereSaysSo(@TempDir Path directory) {
    assertFalse(manager(directory).start("aw-watcher-imaginary"));
  }

  @Test
  void stoppingSomethingThatWasNotRunningSaysSo(@TempDir Path directory) {
    assertFalse(manager(directory).stop("aw-watcher-imaginary"));
  }

  @Test
  void theDefaultAutostartListIsTheOriginals() {
    assertEquals(List.of("aw-server", "aw-watcher-afk", "aw-watcher-window"),
        ModuleRules.DEFAULT_AUTOSTART);
  }
}
