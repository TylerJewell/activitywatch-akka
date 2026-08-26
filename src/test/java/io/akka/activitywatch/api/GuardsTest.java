package io.akka.activitywatch.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The two checks that run before any handler — SPEC-001 §3 R61–R63.
 *
 * <p>Both are security rules, so the interesting cases are the ones that should be refused
 * rather than the ones that should pass. The path matching is on split segments rather than on
 * the raw path for one reason and it is worth stating: `%2f` decodes to a separator, and a
 * check that compared strings would let `/api/0/buckets/aw-watcher-web-x%2f..%2fexport` past.
 */
class GuardsTest {

  private static final Guards LOCALHOST = new Guards("localhost", List.of());

  @Test
  void aHostHeaderNamingSomethingElseIsRefused() {
    Optional<?> refused = LOCALHOST.checkHost("evil.example.com");
    assertTrue(refused.isPresent());
  }

  @Test
  void theThreeHostsThatAreAllowed() {
    assertTrue(LOCALHOST.checkHost("localhost").isEmpty());
    assertTrue(LOCALHOST.checkHost("localhost:5600").isEmpty());
    assertTrue(LOCALHOST.checkHost("127.0.0.1:5600").isEmpty());
  }

  @Test
  void aRequestWithNoHostHeaderIsRefused() {
    assertTrue(LOCALHOST.checkHost(null).isPresent());
  }

  @Test
  void bindingToEveryInterfaceTurnsTheCheckOff() {
    Guards everywhere = new Guards("0.0.0.0", List.of());
    assertTrue(everywhere.checkHost("evil.example.com").isEmpty(),
        "the header says nothing about who was addressed when every address answers");
  }

  @Test
  void theConfiguredHostIsAllowedToo() {
    Guards named = new Guards("my-desktop", List.of());
    assertTrue(named.checkHost("my-desktop:5600").isEmpty());
    assertTrue(named.checkHost("other-desktop:5600").isPresent());
  }

  @Test
  void anExtensionOriginReachesOnlyThreeEndpoints() {
    assertTrue(Guards.isAllowed("GET", List.of("api", "0", "info")));
    assertTrue(Guards.isAllowed("POST", List.of("api", "0", "buckets", "aw-watcher-web-abc")));
    assertTrue(Guards.isAllowed("POST",
        List.of("api", "0", "buckets", "aw-watcher-web-abc", "heartbeat")));
  }

  @Test
  void anExtensionOriginReachesNothingElse() {
    assertFalse(Guards.isAllowed("GET", List.of("api", "0", "export")));
    assertFalse(Guards.isAllowed("GET", List.of("api", "0", "buckets")));
    assertFalse(Guards.isAllowed("POST", List.of("api", "0", "import")));
    assertFalse(Guards.isAllowed("POST", List.of("api", "0", "query")));
    assertFalse(Guards.isAllowed("GET", List.of("api", "0", "settings")));
    assertFalse(Guards.isAllowed("GET",
        List.of("api", "0", "buckets", "aw-watcher-web-abc", "events")),
        "reading the extension's own bucket is not one of the three");
    assertFalse(Guards.isAllowed("POST", List.of("api", "0", "buckets", "aw-watcher-window-x")),
        "another watcher's bucket is not the extension's");
    assertFalse(Guards.isAllowed("POST",
        List.of("api", "0", "buckets", "aw-watcher-afk-x", "heartbeat")));
  }

  @Test
  void aPreflightIsJudgedOnThePathAlone() {
    assertTrue(Guards.isAllowed("OPTIONS", List.of("api", "0", "info")));
    assertTrue(Guards.isAllowed("OPTIONS",
        List.of("api", "0", "buckets", "aw-watcher-web-abc", "heartbeat")));
    assertFalse(Guards.isAllowed("OPTIONS", List.of("api", "0", "export")));
  }

  @Test
  void anOriginTheOperatorConfiguredIsExemptFromTheNarrowing() {
    Guards configured = new Guards("localhost", List.of("moz-extension://known"));
    assertTrue(configured.checkOrigin("moz-extension://known", "GET",
        List.of("api", "0", "export")).isEmpty());
    assertTrue(configured.checkOrigin("moz-extension://other", "GET",
        List.of("api", "0", "export")).isPresent());
  }

  @Test
  void aConfiguredOriginWithAMetacharacterIsAPattern() {
    assertTrue(Guards.matchesConfiguredOrigin("moz-extension://abc", "moz-extension://.*"));
    assertTrue(Guards.matchesConfiguredOrigin("MOZ-EXTENSION://ABC", "moz-extension://abc"),
        "a plain string compares without regard to case");
    assertFalse(Guards.matchesConfiguredOrigin("moz-extension://abc", "moz-extension://xyz"));
    assertFalse(Guards.matchesConfiguredOrigin("moz-extension://abc", "["),
        "a pattern that will not compile matches nothing rather than everything");
  }

  @Test
  void anOriginThatIsNotAnExtensionIsNotNarrowed() {
    assertTrue(LOCALHOST.checkOrigin("http://localhost:27180", "GET",
        List.of("api", "0", "export")).isEmpty());
    assertTrue(LOCALHOST.checkOrigin("", "GET", List.of("api", "0", "export")).isEmpty());
  }

  @Test
  void theCorsOriginsAreAppendedInTheOriginalsOrder() {
    assertEquals(List.of("http://example.com", "moz-extension://*"),
        Guards.corsOrigins(List.of("http://example.com"), false));
    assertEquals(List.of("http://127.0.0.1:27180/*", "moz-extension://*"),
        Guards.corsOrigins(List.of(), true),
        "testing mode allows the interface's own development server");
  }
}
