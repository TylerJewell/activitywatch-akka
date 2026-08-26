package io.akka.activitywatch.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Reading a configuration file — SPEC-001 §3 R107–R111.
 *
 * <p>The reader accepts the subset of TOML the files in this system are written in and no
 * more. A reader that accepted more would accept more than there is anything to check it
 * against, and every file it would then read is one nobody writes.
 */
class ConfigTest {

  @Test
  void everyComponentsDefaultsParse() {
    for (String defaults : List.of(AwConfig.SERVER_DEFAULTS, AwConfig.CLIENT_DEFAULTS,
        AwConfig.AFK_DEFAULTS, AwConfig.WINDOW_DEFAULTS, AwConfig.QT_DEFAULTS)) {
      assertFalse(Toml.parse(defaults).isEmpty(), defaults);
    }
  }

  @Test
  void theServersDefaultsAreTheOriginals() {
    Map<String, Object> config = Toml.parse(AwConfig.SERVER_DEFAULTS);
    Map<String, Object> server = AwConfig.section(config, "server");
    assertEquals("localhost", server.get("host"));
    assertEquals("5600", server.get("port"));
    assertEquals("peewee", server.get("storage"));
    assertEquals("", server.get("cors_origins"));
    assertTrue(server.get("custom_static") instanceof Map);

    Map<String, Object> testing = AwConfig.section(config, "server-testing");
    assertEquals("5666", testing.get("port"));
  }

  @Test
  void theWatchersDefaultsAreTheOriginals() {
    Map<String, Object> afk = Toml.parse(AwConfig.AFK_DEFAULTS);
    assertEquals(180, AwConfig.integer(AwConfig.section(afk, "aw-watcher-afk"), "timeout", 0));
    assertEquals(5, AwConfig.integer(AwConfig.section(afk, "aw-watcher-afk"), "poll_time", 0));
    assertEquals(20,
        AwConfig.integer(AwConfig.section(afk, "aw-watcher-afk-testing"), "timeout", 0));
    assertEquals(1,
        AwConfig.integer(AwConfig.section(afk, "aw-watcher-afk-testing"), "poll_time", 0));

    Map<String, Object> window = AwConfig.section(Toml.parse(AwConfig.WINDOW_DEFAULTS),
        "aw-watcher-window");
    assertEquals(1.0, AwConfig.number(window, "poll_time", 0), 1e-9);
    assertFalse(AwConfig.flag(window, "exclude_title", true));
    assertEquals(List.of(), AwConfig.strings(window, "exclude_titles"));
    assertEquals("swift", AwConfig.string(window, "strategy_macos", ""));
    assertFalse(AwConfig.flag(window, "research_enabled", true));
  }

  @Test
  void theManagersDefaultAutostartIsTheOriginals() {
    Map<String, Object> qt = AwConfig.section(Toml.parse(AwConfig.QT_DEFAULTS), "aw-qt");
    assertEquals(List.of("aw-server", "aw-watcher-afk", "aw-watcher-window"),
        AwConfig.strings(qt, "autostart_modules"));
  }

  @Test
  void aCommentInsideAStringIsNotAComment() {
    Map<String, Object> config = Toml.parse("[a]\nb = \"x # y\"  # a real comment\n");
    assertEquals("x # y", AwConfig.section(config, "a").get("b"));
  }

  @Test
  void aDottedTableHeaderNests() {
    Map<String, Object> config = Toml.parse("[a.b]\nc = 1\n");
    assertEquals(1L, AwConfig.section(AwConfig.section(config, "a"), "b").get("c"));
  }

  @Test
  void anArrayHoldingCommasInItsStringsIsSplitCorrectly() {
    Map<String, Object> config = Toml.parse("[a]\nb = [\"x,y\", \"z\"]\n");
    assertEquals(List.of("x,y", "z"), AwConfig.strings(AwConfig.section(config, "a"), "b"));
  }

  @Test
  void anInlineTableIsATable() {
    Map<String, Object> config = Toml.parse("[a]\nb = { c = 1, d = \"two\" }\n");
    @SuppressWarnings("unchecked")
    Map<String, Object> inline = (Map<String, Object>) AwConfig.section(config, "a").get("b");
    assertEquals(1L, inline.get("c"));
    assertEquals("two", inline.get("d"));
  }

  @Test
  void aFileThatIsNotThisSubsetIsRefusedRatherThanGuessedAt() {
    assertThrows(Toml.TomlException.class, () -> Toml.parse("[a\nb = 1\n"));
    assertThrows(Toml.TomlException.class, () -> Toml.parse("[a]\nb\n"));
    assertThrows(Toml.TomlException.class, () -> Toml.parse("[a]\nb = notavalue\n"));
  }

  @Test
  void customStaticIsParsedFromPairsAndAMalformedOneIsRefused() {
    assertEquals(Map.of("aw-watcher-x", "/tmp/x", "aw-watcher-y", "/tmp/y"),
        AwConfig.parseKeyValuePairs("aw-watcher-x=/tmp/x,aw-watcher-y=/tmp/y"));
    assertEquals(Map.of(), AwConfig.parseKeyValuePairs(""));
    assertThrows(IllegalArgumentException.class,
        () -> AwConfig.parseKeyValuePairs("nothing-to-see-here"));
  }
}
