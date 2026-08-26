package io.akka.activitywatch.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.activitywatch.domain.NotifyConfig;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** `aw-notify`'s command line — SPEC-001 §3 R113, R144, R145. */
class AwNotifyCliTest {

  @Test
  void theDefaultCommandIsToStart() {
    assertEquals("start", AwNotifyCli.parse(new String[] {}).command());
    assertEquals("start", AwNotifyCli.parse(new String[] {"-v"}).command());
    assertEquals("checkin", AwNotifyCli.parse(new String[] {"checkin"}).command());
    assertEquals("checkin-detailed",
        AwNotifyCli.parse(new String[] {"--testing", "checkin-detailed"}).command());
  }

  @Test
  void everyFlagIsRead() {
    AwNotifyCli.Options options = AwNotifyCli.parse(
        new String[] {"-v", "--testing", "-o", "-c", "/tmp/x.toml", "--port", "5678",
            "checkin"});
    assertTrue(options.verbose());
    assertTrue(options.testing());
    assertTrue(options.outputOnly());
    assertEquals(Path.of("/tmp/x.toml"), options.config());
    assertEquals(5678, options.serverPort());
  }

  @Test
  void testingMovesTheServerPortAndAPortGivenOverridesIt() {
    assertEquals(5600, AwNotifyCli.parse(new String[] {}).serverPort());
    assertEquals(5666, AwNotifyCli.parse(new String[] {"--testing"}).serverPort());
    assertEquals(9150,
        AwNotifyCli.parse(new String[] {"--testing", "--port", "9150"}).serverPort());
  }

  @Test
  void aCommandThatDoesNotExistIsRefusedRatherThanRun() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int status = AwNotifyCli.run(new String[] {"nosuchcommand"},
        new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8));
    assertEquals(2, status);
    assertTrue(err.toString(StandardCharsets.UTF_8).contains("no such command"));
  }

  @Test
  void theUsageNamesEveryCommandAndFlag() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    AwNotifyCli.run(new String[] {"help"},
        new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    String text = out.toString(StandardCharsets.UTF_8);
    for (String named : new String[] {"start", "checkin", "checkin-detailed", "--verbose",
        "--config", "--testing", "--port", "--output-only"}) {
      assertTrue(text.contains(named), named + " is not in the usage");
    }
  }

  @Test
  void aConfigurationFileThatIsNotThereIsWrittenWithTheDefaultsInIt(@TempDir Path directory)
      throws Exception {
    Path path = directory.resolve("nested").resolve("config.toml");
    NotifyConfig config = AwNotifyCli.loadConfig(path);
    assertEquals(NotifyConfig.defaults(), config);
    assertTrue(Files.exists(path), "and the file is left behind for a person to edit");
    assertEquals(config, NotifyConfig.read(Files.readString(path, StandardCharsets.UTF_8)));
  }

  @Test
  void aConfigurationFileThatIsThereIsRead(@TempDir Path directory) throws Exception {
    Path path = directory.resolve("config.toml");
    Files.writeString(path, "hourly_checkins = false\n", StandardCharsets.UTF_8);
    assertFalse(AwNotifyCli.loadConfig(path).hourlyCheckins());
  }

  @Test
  void aConfigurationFileThatWillNotParseSaysWhichFile(@TempDir Path directory)
      throws Exception {
    Path path = directory.resolve("config.toml");
    Files.writeString(path, "[unterminated\n", StandardCharsets.UTF_8);
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int status = AwNotifyCli.run(
        new String[] {"-c", path.toString(), "checkin"},
        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8));
    assertEquals(1, status);
    assertTrue(err.toString(StandardCharsets.UTF_8).contains("Failed to parse config file"),
        err.toString(StandardCharsets.UTF_8));
  }
}
