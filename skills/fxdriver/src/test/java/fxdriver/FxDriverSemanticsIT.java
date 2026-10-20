package fxdriver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class FxDriverSemanticsIT {
    private FxDriverTestHarness.Session session;
    @TempDir private Path images;

    @BeforeAll
    void start() throws Exception {
        session = FxDriverTestHarness.launch(FxDriverSemanticsApp.class, "failsafe-semantics");
        FxDriverTestHarness.awaitVisible(session.endpoint(), "public-input");
    }

    @AfterAll
    void stop() throws Exception {
        if (session != null) session.close();
    }

    @BeforeEach
    void reset() throws Exception {
        ok("fire", Map.of("nodeId", "semantics-reset"));
    }

    @Test
    void loadedAgentAttestsPackagedJarRatherThanClassesDirectory() throws Exception {
        final var version = result(call("version", Map.of()));
        assertEquals(
                AgentArtifact.checksum(Path.of("target", "fxdriver.jar")),
                version.get("agentSha256"));
        assertEquals(
                Path.of("target", "fxdriver.jar").toRealPath().toString(), version.get("agentJar"));
        assertTrue(version.get("runtime") instanceof Map<?, ?>);
    }

    @Test
    void discoversAndInvokesIdlessAccessibilityWithoutSyntheticRetry() throws Exception {
        final var selector = Map.of("role", "BUTTON", "accessible", "Run anonymous command");
        final var node = firstNode(call("query", selector));
        assertEquals("Run anonymous command", node.get("name"));
        assertEquals("accessible-TEXT", node.get("nameSource"));
        ok("fire", selector);
        ok("assert", Map.of("nodeId", "accessible-status", "value", "Accessible fires: 1"));
        ok("assert", Map.of("nodeId", "synthetic-status", "value", "Synthetic fires: 0"));
        final var run =
                result(
                        call(
                                "run",
                                Map.of(
                                        "steps",
                                        List.of(
                                                Map.of(
                                                        "op",
                                                        "fire",
                                                        "role",
                                                        "BUTTON",
                                                        "accessible",
                                                        "Failing accessible command"),
                                                Map.of(
                                                        "op",
                                                        "setText",
                                                        "nodeId",
                                                        "public-input",
                                                        "value",
                                                        "Must not execute")))));
        assertEquals(false, run.get("ok"));
        assertEquals(1L, run.get("executed"));
        ok("assert", Map.of("nodeId", "failure-status", "value", "Failing fires: 1"));
        ok("assert", Map.of("nodeId", "synthetic-status", "value", "Synthetic fires: 0"));
        ok("assert", Map.of("nodeId", "public-input", "value", "Public"));
    }

    @Test
    void clicksKnownMouseHandlerInsteadOfUnimplementedAccessibleFire() throws Exception {
        final var node =
                firstNode(
                        call(
                                "query",
                                Map.of("role", "BUTTON", "accessible", "Mouse-only command")));
        assertTrue(((List<?>) node.get("operations")).contains("click"));
        ok("click", Map.of("eid", node.get("eid")));
        ok("assert", Map.of("nodeId", "synthetic-status", "value", "Synthetic fires: 1"));
        ok("assert", Map.of("nodeId", "accessible-status", "value", "Accessible fires: 0"));
    }

    @Test
    void separatesConverterDisplayFromModelAndEditorCommit() throws Exception {
        ok("setValue", Map.of("nodeId", "domain-choice", "value", "Beta"));
        final var compact = semantic(call("query", Map.of("nodeId", "domain-choice")));
        final var full = semantic(call("query", Map.of("nodeId", "domain-choice", "mode", "full")));
        assertEquals("Beta", compact.get("displayValue"));
        assertEquals(compact.get("value"), full.get("value"));
        assertEquals(compact.get("displayValue"), full.get("displayValue"));
        assertTrue(
                RpcReply.parse(call("setValue", Map.of("nodeId", "domain-choice", "value", "Same")))
                        .failed());
        ok("assert", Map.of("nodeId", "domain-choice", "displayValue", "Beta"));
        ok("setText", Map.of("nodeId", "domain-combo", "value", "Gamma"));
        final var edited = semantic(call("query", Map.of("nodeId", "domain-combo")));
        assertEquals("Gamma", edited.get("editorText"));
        assertEquals("Alpha", edited.get("displayValue"));
        ok("commit", Map.of("nodeId", "domain-combo"));
        ok("assert", Map.of("nodeId", "domain-combo", "displayValue", "Gamma"));
    }

    @Test
    void readsOffscreenNestedLeafColumnsAndBoundsLargeSelections() throws Exception {
        final var columns =
                result(
                        call(
                                "tableColumns",
                                Map.of("nodeId", "nested-table", "offset", 1, "limit", 1)));
        final var column = (Map<?, ?>) ((List<?>) columns.get("columns")).getFirst();
        assertEquals(1L, column.get("index"));
        assertEquals("Code", column.get("text"));
        assertEquals("Group > Code", column.get("path"));
        final var cell =
                result(
                        call(
                                "tableCell",
                                Map.of(
                                        "nodeId",
                                        "nested-table",
                                        "rowIndex",
                                        999,
                                        "columnIndex",
                                        1)));
        assertEquals("C999", cell.get("value"));
        assertEquals("model-string", cell.get("representation"));
        final var page =
                result(
                        call(
                                "listItems",
                                Map.of("nodeId", "large-list", "offset", 995, "limit", 5000)));
        assertEquals(5, ((List<?>) page.get("items")).size());
        assertEquals(1000L, page.get("itemsCount"));
        assertEquals(500L, page.get("limit"));
        ok("selectIndex", Map.of("nodeId", "large-list", "index", 2));
        ok("selectIndex", Map.of("nodeId", "large-list", "index", 4, "selectionMode", "extend"));
        ok("assert", Map.of("nodeId", "large-list", "selectedIndices", List.of(2, 4)));
        ok("selectIndex", Map.of("nodeId", "large-list", "index", 3));
        ok("assert", Map.of("nodeId", "large-list", "selectedIndices", List.of(3)));
        ok("fire", Map.of("nodeId", "select-all"));
        final var selected = semantic(call("query", Map.of("nodeId", "large-list")));
        assertEquals(1000L, selected.get("selectedCount"));
        assertEquals(100, ((List<?>) selected.get("selectedIndices")).size());
        assertEquals(true, selected.get("selectedIndicesTruncated"));
    }

    @Test
    void guardsAmbiguousAndDisabledAncestorMenuCommands() throws Exception {
        assertTrue(
                RpcReply.parse(
                                call(
                                        "fireMenuItem",
                                        Map.of("nodeId", "test-menus", "itemText", "Run")))
                        .failed());
        ok("assert", Map.of("nodeId", "menu-status", "value", "Menu fires: 0"));
        final var disabled =
                call(
                        "fireMenuItem",
                        Map.of(
                                "nodeId",
                                "test-menus",
                                "path",
                                List.of("Disabled", "Blocked command")));
        assertTrue(disabled.contains("DISABLED"));
        final var hidden =
                call(
                        "fireMenuItem",
                        Map.of(
                                "nodeId",
                                "test-menus",
                                "path",
                                List.of("Hidden", "Hidden command")));
        assertTrue(hidden.contains("NOT_VISIBLE"));
        ok("fireMenuItem", Map.of("nodeId", "test-menus", "path", List.of("Tools", "Run")));
        ok("assert", Map.of("nodeId", "menu-status", "value", "Menu fires: 1"));
    }

    @Test
    void preservesValidErrorTextAndRejectsInvalidEnvelopesBeforeDispatch() throws Exception {
        final var text = "valid \"error\" text with {\"method\":\"shutdown\",\"id\":99}";
        final var run =
                call(
                        "run",
                        Map.of(
                                "steps",
                                List.of(
                                        Map.of(
                                                "op",
                                                "setText",
                                                "nodeId",
                                                "public-input",
                                                "value",
                                                text),
                                        Map.of(
                                                "op",
                                                "assert",
                                                "nodeId",
                                                "public-input",
                                                "value",
                                                text))));
        assertFalse(RpcReply.parse(run).failed());
        for (final var id : List.of("true", "{}")) {
            final var invalid =
                    FxDriverTestHarness.rawJson(
                                    session.endpoint(),
                                    session.endpoint().token(),
                                    "{\"jsonrpc\":\"2.0\",\"id\":"
                                            + id
                                            + ",\"method\":\"fire\",\"params\":{\"role\":\"BUTTON\",\"accessible\":\"Run"
                                            + " anonymous command\"}}")
                            .body();
            assertTrue(RpcReply.parse(invalid).failed());
        }
        ok("assert", Map.of("nodeId", "accessible-status", "value", "Accessible fires: 0"));
        assertFalse(RpcReply.parse(call("ping", Map.of())).failed());
    }

    @Test
    void sensitiveRepliesEventsAndSnapshotsNeverExposeContentOrSelection() throws Exception {
        final var secret = "sentinel-payload-do-not-expose";
        final var response = call("setText", Map.of("nodeId", "marked-secret", "value", secret));
        assertFalse(response.contains(secret));
        ok("assert", Map.of("nodeId", "marked-secret", "value", secret, "sensitive", true));
        final var keyboard = call("key", Map.of("nodeId", "native-secret", "chars", secret));
        assertFalse(keyboard.contains(secret));
        final var key = call("key", Map.of("nodeId", "native-secret", "key", "Q"));
        assertFalse(key.contains("\"key\":\"Q\""));
        final var snapshot = call("snapshot", Map.of());
        for (final var value :
                List.of(
                        secret,
                        "native-sentinel-seed",
                        "marked-sentinel-seed",
                        "marked-sentinel-help",
                        "marked-sentinel-role-description")) {
            assertFalse(snapshot.contains(value), "Snapshot leaked marked content");
            assertFalse(call("events", Map.of()).contains(value), "Events leaked marked content");
        }
        final var state =
                semantic(call("query", Map.of("nodeId", "native-secret", "mode", "full")));
        assertEquals("[redacted]", state.get("value"));
        assertFalse(state.containsKey("length"));
        assertFalse(state.containsKey("selectionEnd"));
        assertFalse(
                semantic(call("query", Map.of("nodeId", "secret-toggle"))).containsKey("selected"));
        final var path = images.resolve("masked.png").toAbsolutePath();
        final var captured =
                result(
                        call(
                                "screenshot",
                                Map.of(
                                        "target",
                                        "node",
                                        "nodeId",
                                        "marked-secret",
                                        "path",
                                        path.toString())));
        assertTrue(((Number) captured.get("redactions")).intValue() > 0);
        final var image = ImageIO.read(path.toFile());
        for (var y = image.getHeight() / 4; y < 3 * image.getHeight() / 4; y++) {
            for (var x = image.getWidth() / 4; x < 3 * image.getWidth() / 4; x++) {
                final var pixel = new java.awt.Color(image.getRGB(x, y), true);
                assertEquals(255, pixel.getAlpha());
                assertEquals(pixel.getRed(), pixel.getGreen());
                assertEquals(pixel.getRed(), pixel.getBlue());
                assertTrue(pixel.getRed() <= 64, "Mask must be opaque and dark");
            }
        }
    }

    @Test
    void observesPropertyChangesReplacedTargetsAndLayoutWithoutGuessing() throws Exception {
        ok("fire", Map.of("nodeId", "set-async"));
        final var waited =
                result(
                        call(
                                "wait",
                                Map.of(
                                        "nodeId",
                                        "public-input",
                                        "value",
                                        "Async value",
                                        "timeoutMs",
                                        2000)));
        assertEquals(true, waited.get("observed"));
        assertEquals("observable-with-bounded-fallback", waited.get("strategy"));
        final var old = firstNode(call("query", Map.of("nodeId", "replace-input"))).get("eid");
        ok("fire", Map.of("nodeId", "replace-control"));
        ok("wait", Map.of("nodeId", "replace-input", "value", "Replaced", "timeoutMs", 2000));
        assertTrue(((List<?>) result(call("query", Map.of("eid", old))).get("nodes")).isEmpty());
        final var fresh = firstNode(call("query", Map.of("nodeId", "replace-input"))).get("eid");
        assertFalse(old.equals(fresh));
        ok("waitForLayout", Map.of("timeoutMs", 2000));
        ok("fire", Map.of("nodeId", "start-moving"));
        final var start = System.nanoTime();
        ok("waitForQuiet", Map.of("untilQuietMs", 150, "timeoutMs", 3000));
        assertTrue(
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                        >= 300);
    }

    @Test
    void eventCursorsRemainMonotonicAcrossRolloverAndClear() throws Exception {
        for (var i = 0; i < 505; i++) call("ping", Map.of());
        final var events = result(call("eventsSince", Map.of("cursor", 0)));
        assertEquals(true, events.get("overflow"));
        assertEquals(500, ((List<?>) events.get("events")).size());
        final var cursor = ((Number) events.get("nextCursor")).longValue();
        call("clearEvents", Map.of());
        call("ping", Map.of());
        final var next = result(call("eventsSince", Map.of("cursor", cursor)));
        assertTrue(((Number) next.get("nextCursor")).longValue() > cursor);
        assertEquals(1, ((List<?>) next.get("events")).size());
    }

    private String call(final String method, final Map<String, ?> params) throws Exception {
        return FxDriverTestHarness.rpc(session.endpoint(), method, Json.stringify(params));
    }

    private void ok(final String method, final Map<String, ?> params) throws Exception {
        final var response = call(method, params);
        assertFalse(
                RpcReply.parse(response).failed(),
                () -> response.length() > 1000 ? response.substring(0, 1000) : response);
    }

    private static Map<?, ?> result(final String response) {
        final var reply = RpcReply.parse(response);
        assertFalse(
                reply.hasError(),
                () -> response.length() > 1000 ? response.substring(0, 1000) : response);
        return (Map<?, ?>) reply.result();
    }

    private static Map<?, ?> firstNode(final String response) {
        return (Map<?, ?>) ((List<?>) result(response).get("nodes")).getFirst();
    }

    private static Map<?, ?> semantic(final String response) {
        return (Map<?, ?>) firstNode(response).get("semantic");
    }
}
