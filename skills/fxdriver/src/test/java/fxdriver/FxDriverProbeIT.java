package fxdriver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
                    "query",
                    "press",
                    "fire",
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
                    "value-controls",
                    "tree-table-actions",
                    "screenshot",
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
                                    Long.toString(app.pid()))
                            .redirectOutput(out.resolve("attach.out").toFile())
                            .redirectError(out.resolve("attach.err").toFile())
                            .start();
            assertTrue(attach.waitFor(20, TimeUnit.SECONDS));
            assertEquals(0, attach.exitValue(), Files.readString(out.resolve("attach.err")));
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

            final var unauthorized = rpc(endpoint.port(), "", "ping", "{}");
            assertTrue(unauthorized.contains("\"UNAUTHORIZED\""));

            assertTrue(
                    rpc(endpoint.port(), endpoint.token(), "ping", "{}").contains("\"ok\":true"));
            assertTrue(
                    rpc(endpoint.port(), endpoint.token(), "version", "{}")
                            .contains("\"protocolVersion\":1"));
            assertAdvertisedCapabilities(
                    rpc(endpoint.port(), endpoint.token(), "capabilities", "{}"));
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
            assertTrue(
                    rpc(endpoint.port(), endpoint.token(), "fire", "{\"nodeId\":\"probe-button\"}")
                            .contains("\"ok\":true"));
            final var clickState =
                    rpc(
                            endpoint.port(),
                            endpoint.token(),
                            "click",
                            "{\"nodeId\":\"probe-secondary-button\",\"returnState\":true}");
            assertTrue(clickState.contains("\"ok\":true"));
            assertTrue(clickState.contains("\"windows\""));
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
                                    "{\"nodeId\":\"probe-list\",\"index\":0}")
                            .contains("\"ok\":true"));
            assertTrue(
                    rpc(endpoint.port(), endpoint.token(), "click", "{\"textExact\":\"charlie\"}")
                            .contains("\"ok\":true"));
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

    private static Process startProbe(final Path out) throws IOException {
        final var command = new java.util.ArrayList<String>();
        command.add(java());
        command.add("-jar");
        command.add(Path.of("target", "fxdriver.jar").toString());
        command.add("launch");
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
        final var command = new ArrayList<String>();
        command.add(java());
        addProbeJvmArgs(command);
        command.add("--enable-native-access=javafx.graphics");
        if (Runtime.version().feature() >= 24) {
            command.add("--sun-misc-unsafe-memory-access=allow");
        }
        command.add("--module-path");
        command.add(javafxModulePath());
        command.add("--add-modules");
        command.add("javafx.controls,javafx.web,jdk.jsobject");
        command.add("-cp");
        command.add(testClasspath());
        command.add("fxdriver.FxDriverProbeApp");
        return command;
    }

    private static Endpoint waitForEndpoint(final Path output) throws Exception {
        final var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(output)) {
                final var text = Files.readString(output);
                final var port = PORT.matcher(text);
                final var token = TOKEN.matcher(text);
                if (port.find() && token.find()) {
                    return new Endpoint(Integer.parseInt(port.group(1)), token.group(1));
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError(
                "fxdriver endpoint not printed; output=" + Files.readString(output));
    }

    private static void assertAdvertisedCapabilities(final String capabilities) {
        for (final var method : ADVERTISED_METHODS) {
            assertTrue(
                    capabilities.contains("\"" + method + "\""),
                    () -> "capabilities missing method " + method + ": " + capabilities);
        }
        for (final var feature : ADVERTISED_FEATURES) {
            assertTrue(
                    capabilities.contains("\"" + feature + "\""),
                    () -> "capabilities missing feature " + feature + ": " + capabilities);
        }
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
        final var request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/rpc"))
                        .timeout(Duration.ofSeconds(10))
                        .header("content-type", "application/json")
                        .header("fxdriver-token", token)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
        final var response =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
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
        process.descendants().forEach(child -> child.destroyForcibly());
        process.destroy();
        process.waitFor(5, TimeUnit.SECONDS);
        if (process.isAlive()) {
            process.descendants().forEach(child -> child.destroyForcibly());
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
        process.descendants().forEach(child -> child.destroyForcibly());
        assertFalse(process.isAlive());
    }

    private static Optional<Endpoint> maybeEndpoint(final Path output) throws IOException {
        if (!Files.isRegularFile(output)) {
            return Optional.empty();
        }
        final var text = Files.readString(output);
        final var port = PORT.matcher(text);
        final var token = TOKEN.matcher(text);
        if (!port.find() || !token.find()) {
            return Optional.empty();
        }
        return Optional.of(new Endpoint(Integer.parseInt(port.group(1)), token.group(1)));
    }

    private static String testClasspath() {
        final var separator = System.getProperty("path.separator");
        return Path.of("target", "test-classes") + separator + Path.of("target", "classes");
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
        return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
                .toString();
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
}
