package fxdriver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class FxDriverProbeIT {
    private static final Pattern PORT = Pattern.compile("rpc=http://127\\.0\\.0\\.1:(\\d+)/rpc");
    private static final Pattern TOKEN = Pattern.compile("FXDRIVER_TOKEN=([0-9a-f]+)");
    private static final Pattern EID = Pattern.compile("\"eid\":\"(n\\d+)\"");
    private static final List<String> ADVERTISED_METHODS =
            List.of(
                    "ping",
                    "version",
                    "capabilities",
                    "configure",
                    "shutdown",
                    "batch",
                    "run",
                    "events",
                    "eventsSince",
                    "clearEvents",
                    "mark",
                    "highlight",
                    "snapshot",
                    "snapshotSummary",
                    "query",
                    "press",
                    "key",
                    "fire",
                    "fireMenuItem",
                    "tableCell",
                    "click",
                    "type",
                    "clear",
                    "setText",
                    "setValue",
                    "showPopup",
                    "hidePopup",
                    "increment",
                    "decrement",
                    "scroll",
                    "listItems",
                    "selectListItem",
                    "selectIndex",
                    "scrollToIndex",
                    "expand",
                    "collapse",
                    "wait",
                    "assert",
                    "screenshot",
                    "webExecuteScript");
    private static final List<String> ADVERTISED_FEATURES =
            List.of(
                    "attach",
                    "launch",
                    "batch",
                    "run",
                    "snapshot",
                    "query",
                    "returnState",
                    "actions",
                    "keyboard",
                    "menus",
                    "table-cell",
                    "value-controls",
                    "tree-table-actions",
                    "snapshot-summary",
                    "near-matches",
                    "screenshot",
                    "screenshot-metadata",
                    "image-summary",
                    "image-diff",
                    "events",
                    "eventsSince",
                    "visual-trace",
                    "webExecuteScript");

    @Test
    void attachToRunningProbeApp() throws Exception {
        final var out = Path.of("target", "failsafe-attach-probe");
        Files.createDirectories(out);
        final var app = startDirectProbe(out);
        try {
            final var attach =
                    new ProcessBuilder(
                                    java(),
                                    "-jar",
                                    Path.of("target", "fxdriver.jar").toString(),
                                    "attach",
                                    "--json",
                                    Long.toString(app.pid()))
                            .redirectOutput(out.resolve("attach.out").toFile())
                            .redirectError(out.resolve("attach.err").toFile())
                            .start();
            assertTrue(attach.waitFor(20, TimeUnit.SECONDS));
            assertEquals(0, attach.exitValue(), Files.readString(out.resolve("attach.err")));
            assertPureEndpointOutput(out.resolve("attach.out"));
            final var endpoint = waitForEndpoint(out.resolve("attach.out"));
            assertTrue(
                    rpc(endpoint.port(), endpoint.token(), "ping", "{}").contains("\"ok\":true"));
            assertTrue(waitForRpcContains(endpoint, "snapshot", "{}", "\"probe-button\""));
            assertTrue(
                    rpc(
                                    endpoint.port(),
                                    endpoint.token(),
                                    "screenshot",
                                    "{\"path\":\"target/failsafe-attach-probe/window.png\"}")
                            .contains("\"scale\":"));
            assertTrue(Files.isRegularFile(out.resolve("window.png")));
            rpc(endpoint.port(), endpoint.token(), "shutdown", "{}");
            assertTrue(app.isAlive());
        } finally {
            destroy(app);
        }
    }

    @Test
    void launchAndDriveProbeApp() throws Exception {
        final var out = Path.of("target", "failsafe-probe");
        Files.createDirectories(out);
        final var process = startProbe(out);
        try {
            final var endpoint = waitForEndpoint(out.resolve("launch.out"));
            assertPureEndpointOutput(out.resolve("launch.out"));

            final var unauthorized = rpc(endpoint.port(), "", "ping", "{}");
            assertTrue(unauthorized.contains("\"UNAUTHORIZED\""));

            assertTrue(
                    rpc(endpoint.port(), endpoint.token(), "ping", "{}").contains("\"ok\":true"));
            assertTrue(
                    rpc(endpoint.port(), endpoint.token(), "version", "{}")
                            .contains("\"protocolVersion\":1"));
            final var capabilities = rpc(endpoint.port(), endpoint.token(), "capabilities", "{}");
            assertAdvertisedCapabilities(capabilities);
            assertFalse(capabilities.contains("selectorPath"), capabilities);
            assertFalse(capabilities.contains("path=selectorPath"), capabilities);
            assertTrue(waitForRpcContains(endpoint, "snapshot", "{}", "\"probe-button\""));
            assertTrue(
                    rpc(
                                    endpoint.port(),
                                    endpoint.token(),
                                    "query",
                                    "{\"selector\":\"type=Button\"}")
                            .contains("\"probe-button\""));
            assertTrue(
                    rawRpc(
                                    endpoint.port(),
                                    endpoint.token(),
                                    "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{}},{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"query\",\"params\":{\"selector\":\"#probe-button\"}}]")
                            .contains("\"id\":2"));
            final var runState =
                    rpc(
                            endpoint.port(),
                            endpoint.token(),
                            "run",
                            "{\"steps\":[{\"op\":\"setText\",\"nodeId\":\"probe-field\",\"value\":\"run"
                                + " value\"},{\"op\":\"wait\",\"text\":\"run"
                                + " value\"}],\"returnState\":\"compact\"}");
            assertTrue(runState.contains("\"ok\":true"));
            assertTrue(runState.contains("\"state\""));
            assertTrue(runState.contains("\"target\":"), runState);
            assertTrue(
                    rpc(
                                    endpoint.port(),
                                    endpoint.token(),
                                    "assert",
                                    "{\"nodeId\":\"probe-button\"}")
                            .contains("\"ok\":true"));
            assertTrue(
                    rpc(
                                    endpoint.port(),
                                    endpoint.token(),
                                    "assert",
                                    "{\"textExact\":\"Probe button\"}")
                            .contains("\"ok\":true"));
            assertTrue(
                    rpc(
                                    endpoint.port(),
                                    endpoint.token(),
                                    "assert",
                                    "{\"regexText\":\"Probe.*button\"}")
                            .contains("\"ok\":true"));
            assertTrue(
                    rpc(endpoint.port(), endpoint.token(), "assert", "{\"role\":\"BUTTON\"}")
                            .contains("\"ok\":true"));
            assertTrue(
                    rpc(
                                    endpoint.port(),
                                    endpoint.token(),
                                    "assert",
                                    "{\"selector\":\"type=Button\"}")
                            .contains("\"ok\":true"));
            assertTrue(
                    rpc(endpoint.port(), endpoint.token(), "notAMethod", "{}")
                            .contains("\"METHOD_NOT_FOUND\""));
            assertTrue(
                    rpc(
                                    endpoint.port(),
                                    endpoint.token(),
                                    "setText",
                                    "{\"nodeId\":\"probe-field\",\"text\":\"legacy\"}")
                            .contains("setText/type require value"));
            assertTrue(
                    rpc(
                                    endpoint.port(),
                                    endpoint.token(),
                                    "configure",
                                    "{\"visualTrace\":true,\"highlightMs\":1}")
                            .contains("\"visualTrace\":true"));
            assertTrue(
                    rpc(endpoint.port(), endpoint.token(), "mark", "{}").contains("\"marked\":2"));
            assertTrue(
                    rpc(
                                    endpoint.port(),
                                    endpoint.token(),
                                    "highlight",
                                    "{\"selector\":\"type=Button\"}")
                            .contains("\"highlighted\":"));
            final var setTextState =
                    rpc(
                            endpoint.port(),
                            endpoint.token(),
                            "setText",
                            "{\"nodeId\":\"probe-field\",\"value\":\"integration\",\"returnState\":\"compact\"}");
            assertTrue(setTextState.contains("\"ok\":true"));
            assertTrue(setTextState.contains("\"target\":"), setTextState);
            assertTrue(setTextState.contains("\"state\""));
            assertTrue(setTextState.contains("\"probe-field\""));
            assertTrue(
                    rpc(
                                    endpoint.port(),
                                    endpoint.token(),
                                    "query",
                                    "{\"selector\":\"type=TextField\"}")
                            .contains("probe-field"));
            final var fieldQuery =
                    rpc(
                            endpoint.port(),
                            endpoint.token(),
                            "query",
                            "{\"selector\":\"#probe-field\"}");
            final var fieldEid = firstEid(fieldQuery);
            assertTrue(
                    rpc(
                                    endpoint.port(),
                                    endpoint.token(),
                                    "setText",
                                    "{\"eid\":\"" + fieldEid + "\",\"value\":\"eid value\"}")
                            .contains("\"ok\":true"));
            assertTrue(
                    rpc(
                                    endpoint.port(),
                                    endpoint.token(),
                                    "type",
                                    "{\"nodeId\":\"probe-field\",\"value\":\""
                                            + " test\",\"replace\":false}")
                            .contains("\"ok\":true"));
            assertTrue(
                    rpc(endpoint.port(), endpoint.token(), "clear", "{\"nodeId\":\"probe-field\"}")
                            .contains("\"ok\":true"));
            final var stableTarget =
                    assertActionTarget(endpoint, "fire", "{\"nodeId\":\"probe-button\"}");
            assertTrue(stableTarget.contains("button#probe-button[0]"), stableTarget);
            assertFalse(stableTarget.contains("\"quiet\""), stableTarget);

            final var quietClick =
                    rpc(
                            endpoint.port(),
                            endpoint.token(),
                            "click",
                            "{\"nodeId\":\"probe-button\",\"untilQuietMs\":100,"
                                    + "\"timeoutMs\":2000}");
            assertTrue(quietClick.contains("\"quiet\":{\"ok\":true"), quietClick);
            assertTrue(quietClick.contains("\"target\":"), quietClick);
            assertFalse(quietClick.contains("\"polls\":0"), quietClick);
            assertFalse(quietClick.contains("\"signature\":\"\""), quietClick);

            final var started = System.nanoTime();
            final var unstable =
                    rpc(
                            endpoint.port(),
                            endpoint.token(),
                            "click",
                            "{\"nodeId\":\"probe-unstable\",\"untilQuietMs\":200,"
                                    + "\"timeoutMs\":450}");
            final var elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            assertTrue(unstable.contains("\"QUIET_TIMEOUT\""), unstable);
            assertTrue(unstable.contains("\"quiet\":{\"ok\":false"), unstable);
            assertTrue(elapsedMs >= 400 && elapsedMs < 1_000, "elapsed=" + elapsedMs);

            final var clickState =
                    rpc(
                            endpoint.port(),
                            endpoint.token(),
                            "click",
                            "{\"nodeId\":\"probe-secondary-button\",\"returnState\":true}");
            assertTrue(clickState.contains("\"ok\":true"));
            assertTrue(clickState.contains("\"target\":"), clickState);
            assertTrue(clickState.contains("\"windows\""));

            final var windowSnapshot =
                    rpc(endpoint.port(), endpoint.token(), "snapshotSummary", "{}");
            final var secondaryWindow = windowId(windowSnapshot, "fxdriver probe secondary");
            final var focusedNode =
                    windowSnapshot.substring(windowSnapshot.indexOf("\"focusedNode\":"));
            assertTrue(
                    focusedNode.contains("\"window\":\"" + focusedWindow(windowSnapshot) + "\""),
                    focusedNode);
            final var secondaryShot =
                    rpc(
                            endpoint.port(),
                            endpoint.token(),
                            "screenshot",
                            "{\"window\":\""
                                    + secondaryWindow
                                    + "\",\"path\":\"target/failsafe-probe/secondary.png\"}");
            assertTrue(secondaryShot.contains("\"activeWindow\":"), secondaryShot);
            assertTrue(secondaryShot.contains("fxdriver probe secondary"), secondaryShot);
            assertTrue(secondaryShot.contains("Secondary button"), secondaryShot);
            assertTrue(
                    secondaryShot.contains("\"image\":{\"x\":0,\"y\":0,\"w\":260,\"h\":120}"),
                    secondaryShot);
            assertFalse(secondaryShot.contains("\"w\":620,\"h\":900"), secondaryShot);
            assertTrue(secondaryShot.length() < 12_000, secondaryShot);

            final var fullSnapshot = rpc(endpoint.port(), endpoint.token(), "snapshot", "{}");
            final var popupWindow = nodeWindow(fullSnapshot, "probe-popup-button");
            final var popupShot =
                    rpc(
                            endpoint.port(),
                            endpoint.token(),
                            "screenshot",
                            "{\"window\":\""
                                    + popupWindow
                                    + "\",\"path\":\"target/failsafe-probe/popup.png\"}");
            assertTrue(popupShot.contains("unknown screenshot window"), popupShot);

            assertActionTarget(
                    endpoint,
                    "type",
                    "{\"nodeId\":\"probe-field\",\"value\":\"target\",\"replace\":true}");
            assertActionTarget(endpoint, "clear", "{\"nodeId\":\"probe-field\"}");
            assertActionTarget(
                    endpoint, "setText", "{\"nodeId\":\"probe-field\",\"value\":\"target\"}");
            assertActionTarget(
                    endpoint, "setValue", "{\"nodeId\":\"probe-choice\",\"value\":\"blue\"}");
            assertActionTarget(endpoint, "showPopup", "{\"nodeId\":\"probe-combo\"}");
            assertActionTarget(endpoint, "hidePopup", "{\"nodeId\":\"probe-combo\"}");
            assertActionTarget(endpoint, "increment", "{\"nodeId\":\"probe-spinner\",\"steps\":1}");
            assertActionTarget(endpoint, "decrement", "{\"nodeId\":\"probe-spinner\"}");
            assertActionTarget(endpoint, "scroll", "{\"nodeId\":\"probe-list\",\"amount\":1}");
            assertActionTarget(endpoint, "selectIndex", "{\"nodeId\":\"probe-list\",\"index\":0}");
            assertActionTarget(
                    endpoint, "scrollToIndex", "{\"nodeId\":\"probe-tree\",\"index\":1}");
            assertActionTarget(endpoint, "expand", "{\"nodeId\":\"probe-titled\"}");
            assertActionTarget(endpoint, "collapse", "{\"nodeId\":\"probe-titled\"}");

            final var duplicateClick =
                    assertActionTarget(endpoint, "click", "{\"textExact\":\"Duplicate action\"}");
            assertTrue(duplicateClick.contains("\"matches\":9"), duplicateClick);
            assertTrue(duplicateClick.contains("\"ambiguity\""), duplicateClick);
            assertEquals(9, occurrences(duplicateClick, "\"selectorPath\""), duplicateClick);

            final var escaped =
                    assertActionTarget(endpoint, "click", "{\"nodeId\":\"probe-escaped\"}");
            assertTrue(escaped.contains("\\\"quote\\\""), escaped);
            assertTrue(escaped.contains("\\\\ slash"), escaped);

            final var secondary =
                    assertActionTarget(
                            endpoint, "click", "{\"nodeId\":\"probe-secondary-button\"}");
            assertTrue(secondary.contains("window[1]"), secondary);
            final var subScene =
                    assertActionTarget(endpoint, "fire", "{\"nodeId\":\"probe-subscene-button\"}");
            assertTrue(subScene.contains("probe-subscene-button"), subScene);
            assertTrue(subScene.length() < 4_096, subScene);

            final var miss =
                    rpc(endpoint.port(), endpoint.token(), "click", "{\"nodeId\":\"missing\"}");
            assertTrue(miss.contains("\"ok\":false"), miss);
            assertTrue(miss.contains("\"target\":null"), miss);
            assertTrue(miss.contains("\"matches\":0"), miss);

            final var delayed =
                    rpc(
                            endpoint.port(),
                            endpoint.token(),
                            "fire",
                            "{\"nodeId\":\"probe-button\",\"highlightMs\":25}");
            assertTrue(delayed.contains("\"target\":"), delayed);

            assertTrue(
                    rpc(
                                    endpoint.port(),
                                    endpoint.token(),
                                    "scroll",
                                    "{\"nodeId\":\"probe-list\",\"amount\":3}")
                            .contains("\"ok\":true"));
            assertTrue(
                    rpc(
                                    endpoint.port(),
                                    endpoint.token(),
                                    "listItems",
                                    "{\"nodeId\":\"probe-list\"}")
                            .contains("charlie"));
            assertTrue(
                    rpc(
                                    endpoint.port(),
                                    endpoint.token(),
                                    "selectIndex",
                                    "{\"nodeId\":\"probe-list\",\"index\":2}")
                            .contains("\"ok\":true"));
            final var listClick =
                    rpc(endpoint.port(), endpoint.token(), "click", "{\"textExact\":\"charlie\"}");
            assertTrue(listClick.contains("\"ok\":true"), listClick);
            assertTrue(
                    rpc(endpoint.port(), endpoint.token(), "wait", "{\"text\":\"clicked charlie\"}")
                            .contains("\"ok\":true"));
            assertTrue(
                    rpc(
                                    endpoint.port(),
                                    endpoint.token(),
                                    "selectListItem",
                                    "{\"nodeId\":\"probe-list\",\"itemText\":\"charlie\",\"activate\":false}")
                            .contains("\"ok\":true"));
            assertTrue(
                    rpc(
                                    endpoint.port(),
                                    endpoint.token(),
                                    "selectIndex",
                                    "{\"nodeId\":\"probe-table\",\"index\":1}")
                            .contains("\"ok\":true"));
            assertTrue(
                    rpc(
                                    endpoint.port(),
                                    endpoint.token(),
                                    "scrollToIndex",
                                    "{\"nodeId\":\"probe-tree\",\"index\":1}")
                            .contains("\"ok\":true"));
            assertTrue(
                    rpc(
                                    endpoint.port(),
                                    endpoint.token(),
                                    "expand",
                                    "{\"nodeId\":\"probe-titled\"}")
                            .contains("\"ok\":true"));
            assertTrue(
                    rpc(
                                    endpoint.port(),
                                    endpoint.token(),
                                    "collapse",
                                    "{\"nodeId\":\"probe-titled\"}")
                            .contains("\"ok\":true"));
            assertTrue(
                    rpc(
                                    endpoint.port(),
                                    endpoint.token(),
                                    "setValue",
                                    "{\"nodeId\":\"probe-choice\",\"value\":\"blue\"}")
                            .contains("\"ok\":true"));
            assertTrue(
                    rpc(
                                    endpoint.port(),
                                    endpoint.token(),
                                    "setValue",
                                    "{\"nodeId\":\"probe-date\",\"value\":\"2026-06-10\"}")
                            .contains("\"ok\":true"));
            assertTrue(
                    rpc(
                                    endpoint.port(),
                                    endpoint.token(),
                                    "setValue",
                                    "{\"nodeId\":\"probe-slider\",\"value\":\"42\"}")
                            .contains("\"ok\":true"));
            assertTrue(
                    rpc(
                                    endpoint.port(),
                                    endpoint.token(),
                                    "increment",
                                    "{\"nodeId\":\"probe-spinner\",\"steps\":2}")
                            .contains("\"ok\":true"));
            assertTrue(
                    rpc(
                                    endpoint.port(),
                                    endpoint.token(),
                                    "decrement",
                                    "{\"nodeId\":\"probe-spinner\"}")
                            .contains("\"ok\":true"));
            assertTrue(
                    rpc(
                                    endpoint.port(),
                                    endpoint.token(),
                                    "showPopup",
                                    "{\"nodeId\":\"probe-combo\"}")
                            .contains("\"ok\":true"));
            assertTrue(
                    rpc(
                                    endpoint.port(),
                                    endpoint.token(),
                                    "hidePopup",
                                    "{\"nodeId\":\"probe-combo\"}")
                            .contains("\"ok\":true"));
            assertTrue(
                    rpc(
                                    endpoint.port(),
                                    endpoint.token(),
                                    "webExecuteScript",
                                    "{\"nodeId\":\"probe-web\",\"script\":\"document.title\"}")
                            .contains("Probe Web"));
            assertTrue(
                    rpc(endpoint.port(), endpoint.token(), "wait", "{\"nodeId\":\"probe-list\"}")
                            .contains("\"ok\":true"));
            assertTrue(
                    rpc(endpoint.port(), endpoint.token(), "press", "{\"key\":\"F1\"}")
                            .contains("\"acceleratorsFired\":1"));
            assertTrue(
                    rpc(
                                    endpoint.port(),
                                    endpoint.token(),
                                    "assert",
                                    "{\"textExact\":\"F1 pressed\",\"present\":true}")
                            .contains("\"ok\":true"));
            assertTrue(
                    rpc(
                                    endpoint.port(),
                                    endpoint.token(),
                                    "screenshot",
                                    "{\"target\":\"node\",\"nodeId\":\"probe-button\",\"path\":\"target/failsafe-probe/node.png\"}")
                            .contains("\"source\":"));
            assertTrue(Files.isRegularFile(out.resolve("node.png")));
            assertTrue(
                    rpc(
                                    endpoint.port(),
                                    endpoint.token(),
                                    "screenshot",
                                    "{\"target\":\"rect\",\"x\":0,\"y\":0,\"w\":120,\"h\":80,\"path\":\"target/failsafe-probe/rect.png\"}")
                            .contains("\"target\":\"rect\""));
            assertTrue(Files.isRegularFile(out.resolve("rect.png")));
            assertTrue(rpc(endpoint.port(), endpoint.token(), "events", "{}").contains("events"));
            assertTrue(
                    rpc(endpoint.port(), endpoint.token(), "eventsSince", "{\"cursor\":0}")
                            .contains("nextCursor"));
            assertTrue(
                    rpc(endpoint.port(), endpoint.token(), "clearEvents", "{}")
                            .contains("\"ok\":true"));
        } finally {
            shutdown(process, out.resolve("launch.out"));
        }
    }

    @Test
    void helpAndNoArgsExitSuccessfully() throws Exception {
        for (final var args : List.of(new String[0], new String[] {"--help"})) {
            final var help = runCli(args);
            assertEquals(0, help.exitCode(), help.output());
            assertTrue(help.output().contains("fxdriver launch"), help.output());
        }
    }

    @Test
    void shutdownExitsLaunchedApp() throws Exception {
        final var session =
                FxDriverTestHarness.launch(FxDriverDataApp.class, "failsafe-shutdown-exit");
        try {
            final var response =
                    rpc(session.endpoint().port(), session.endpoint().token(), "shutdown", "{}");
            assertTrue(response.contains("\"ok\":true"), response);
            assertTrue(session.process().waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, session.process().exitValue());
        } finally {
            if (session.process().isAlive()) {
                destroy(session.process());
            }
        }
    }

    @Test
    void snapshotCliSupportsFullAndSummaryModes() throws Exception {
        try (var session =
                FxDriverTestHarness.launch(FxDriverDataApp.class, "failsafe-snapshot-cli")) {
            FxDriverTestHarness.awaitVisible(session.endpoint(), "project-table");
            final var full =
                    runCli(
                            "snapshot",
                            Integer.toString(session.endpoint().port()),
                            session.endpoint().token());
            assertEquals(0, full.exitCode(), full.output());
            assertTrue(full.output().contains("\"windows\":"), full.output());

            final var summary =
                    runCli(
                            "snapshot",
                            Integer.toString(session.endpoint().port()),
                            "--summary",
                            session.endpoint().token());
            assertEquals(0, summary.exitCode(), summary.output());
            assertTrue(summary.output().contains("\"visibleTextSample\":"), summary.output());

            final var malformed = runCli("snapshot", "not-a-port");
            assertEquals(2, malformed.exitCode(), malformed.output());
        }
    }

    @Test
    void machineLaunchDrainsChildOutput() throws Exception {
        final var out = Path.of("target", "failsafe-output-probe");
        Files.createDirectories(out);
        final var command = new ArrayList<String>();
        command.add(java());
        command.add("-jar");
        command.add(Path.of("target", "fxdriver.jar").toString());
        command.add("launch");
        command.add("--quiet");
        command.add("--");
        command.addAll(probeCommand());
        command.add("--output-probe");
        final var process =
                new ProcessBuilder(command)
                        .redirectOutput(out.resolve("launch.out").toFile())
                        .redirectError(out.resolve("launch.err").toFile())
                        .start();

        assertTrue(process.waitFor(20, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), Files.readString(out.resolve("launch.err")));
        assertPureEndpointOutput(out.resolve("launch.out"));
        final var stderr = Files.readString(out.resolve("launch.err"));
        assertTrue(stderr.contains("FXDRIVER_STDOUT_DONE"), stderr);
        assertTrue(stderr.contains("FXDRIVER_STDERR_DONE"), stderr);
    }

    private static CliResult runCli(final String... args) throws Exception {
        final var command = new ArrayList<String>();
        command.add(java());
        command.add("-jar");
        command.add(Path.of("target", "fxdriver.jar").toString());
        command.addAll(List.of(args));
        final var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final var output = new String(process.getInputStream().readAllBytes());
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        return new CliResult(process.exitValue(), output);
    }

    private static Process startProbe(final Path out) throws IOException {
        final var command = new ArrayList<String>();
        command.add(java());
        command.add("-jar");
        command.add(Path.of("target", "fxdriver.jar").toString());
        command.add("launch");
        command.add("--quiet");
        command.add("--");
        command.addAll(probeCommand());
        return new ProcessBuilder(command)
                .redirectOutput(out.resolve("launch.out").toFile())
                .redirectError(out.resolve("launch.err").toFile())
                .start();
    }

    private static Process startDirectProbe(final Path out) throws IOException {
        return new ProcessBuilder(probeCommand())
                .redirectOutput(out.resolve("app.out").toFile())
                .redirectError(out.resolve("app.err").toFile())
                .start();
    }

    private static java.util.List<String> probeCommand() {
        return FxDriverTestHarness.applicationCommand(FxDriverProbeApp.class);
    }

    private static Endpoint waitForEndpoint(final Path output) throws Exception {
        final var endpoint = FxDriverTestHarness.awaitEndpoint(output);
        return new Endpoint(endpoint.port(), endpoint.token());
    }

    private static void assertAdvertisedCapabilities(final String capabilities) throws Exception {
        assertEquals(ADVERTISED_METHODS, Json.stringArray(capabilities, "methods"));
        assertEquals(
                Path.of("").toAbsolutePath().normalize().toString(),
                Json.requiredString(capabilities, "workingDirectory"));
        assertEquals(ADVERTISED_FEATURES, Json.stringArray(capabilities, "features"));
        assertFalse(Json.stringArray(capabilities, "methods").contains("select"), capabilities);
        assertFalse(
                Json.stringArray(capabilities, "features").contains("selector-path-selectors"),
                capabilities);
        for (final var laterMethod : List.of("videoStart", "videoStep", "videoStop")) {
            assertFalse(capabilities.contains("\"" + laterMethod + "\""), capabilities);
        }
    }

    private static String assertActionTarget(
            final Endpoint endpoint, final String method, final String params) throws Exception {
        final var response = rpc(endpoint.port(), endpoint.token(), method, params);
        assertTrue(response.contains("\"ok\":true"), response);
        assertTrue(response.contains("\"target\":{\"eid\":\"n"), response);
        assertTrue(response.contains("\"matches\":"), response);
        return response;
    }

    private static int occurrences(final String text, final String needle) {
        var count = 0;
        var at = 0;
        while ((at = text.indexOf(needle, at)) >= 0) {
            count++;
            at += needle.length();
        }
        return count;
    }

    private static String focusedWindow(final String json) {
        final var matcher =
                Pattern.compile("\\\"wid\\\":\\\"(w\\d+)\\\"[^}]*\\\"focused\\\":true")
                        .matcher(json);
        assertTrue(matcher.find(), json);
        return matcher.group(1);
    }

    private static String nodeWindow(final String json, final String nodeId) {
        final var matcher =
                Pattern.compile(
                                "\\\"window\\\":\\\"(w\\d+)\\\"[^}]*\\\"id\\\":\\\""
                                        + Pattern.quote(nodeId)
                                        + "\\\"")
                        .matcher(json);
        assertTrue(matcher.find(), json);
        return matcher.group(1);
    }

    private static String windowId(final String json, final String title) {
        final var matcher =
                Pattern.compile(
                                "\\\"wid\\\":\\\"([^\\\"]+)\\\",\\\"title\\\":\\\"[^\\\"]*"
                                        + Pattern.quote(title)
                                        + "\\\"")
                        .matcher(json);
        assertTrue(matcher.find(), json);
        return matcher.group(1);
    }

    private static String firstEid(final String json) {
        final var matcher = EID.matcher(json);
        assertTrue(matcher.find(), () -> "No eid in " + json);
        return matcher.group(1);
    }

    private static boolean waitForRpcContains(
            final Endpoint endpoint, final String method, final String params, final String needle)
            throws Exception {
        final var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (rpc(endpoint.port(), endpoint.token(), method, params).contains(needle)) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private static String rpc(
            final int port, final String token, final String method, final String params)
            throws Exception {
        return rawRpc(
                port,
                token,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\""
                        + method
                        + "\",\"params\":"
                        + params
                        + "}");
    }

    private static String rawRpc(final int port, final String token, final String body)
            throws Exception {
        final var response =
                FxDriverTestHarness.rawJson(
                        new FxDriverTestHarness.Endpoint(port, token), token, body);
        assertEquals(200, response.status());
        return response.body();
    }

    private static void shutdown(final Process process, final Path output) throws Exception {
        try {
            final var endpoint = maybeEndpoint(output);
            if (endpoint.isPresent()) {
                rpc(endpoint.get().port(), endpoint.get().token(), "shutdown", "{}");
            }
        } finally {
            destroy(process);
        }
    }

    private static void destroy(final Process process) throws InterruptedException {
        FxDriverTestHarness.destroy(process);
    }

    private static Optional<Endpoint> maybeEndpoint(final Path output) throws IOException {
        if (!Files.isRegularFile(output)) {
            return Optional.empty();
        }
        final var text = Files.readString(output);
        if (Json.hasKey(text, "port") && Json.hasKey(text, "token")) {
            return Optional.of(
                    new Endpoint(
                            Json.requiredInt(text, "port"), Json.requiredString(text, "token")));
        }
        final var port = PORT.matcher(text);
        final var token = TOKEN.matcher(text);
        if (!port.find() || !token.find()) {
            return Optional.empty();
        }
        return Optional.of(new Endpoint(Integer.parseInt(port.group(1)), token.group(1)));
    }

    private static void assertPureEndpointOutput(final Path output) throws IOException {
        FxDriverTestHarness.assertPureEndpointOutput(output);
    }

    private static String testClasspath() {
        final var separator = System.getProperty("path.separator");
        return Path.of("target", "test-classes") + separator + Path.of("target", "classes");
    }

    private static String javafxModules() {
        return ModuleLayer.boot().findModule("jdk.jsobject").isPresent()
                ? "javafx.controls,javafx.web"
                : "javafx.controls,javafx.web,jdk.jsobject";
    }

    private static String javafxModulePath() {
        final var separator = System.getProperty("path.separator");
        final var out = new StringBuilder();
        for (final var item :
                System.getProperty("java.class.path").split(Pattern.quote(separator))) {
            if (!item.contains("javafx-") && !item.contains("jdk-jsobject")) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(separator);
            }
            out.append(item);
        }
        return out.toString();
    }

    private static String java() {
        return FxDriverTestHarness.java();
    }

    private static void addProbeJvmArgs(final List<String> command) {
        final var args = System.getProperty("fxdriver.probe.jvmargs", "").strip();
        if (!args.isBlank()) {
            command.addAll(List.of(args.split("\\s+")));
        }
        addProbeSystemProperty(command, "glass.platform");
        addProbeSystemProperty(command, "java.awt.headless");
        addProbeSystemProperty(command, "prism.order");
    }

    private static void addProbeSystemProperty(final List<String> command, final String name) {
        final var value = System.getProperty(name, "").strip();
        if (!value.isBlank()) {
            command.add("-D" + name + "=" + value);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private record Endpoint(int port, String token) {}

    private record CliResult(int exitCode, String output) {}
}
