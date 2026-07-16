package fxdriver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class FxDriverFailureExamplesIT {
    private static final Pattern PORT = Pattern.compile("rpc=http://127\\.0\\.0\\.1:(\\d+)/rpc");
    private static final Pattern TOKEN = Pattern.compile("FXDRIVER_TOKEN=([0-9a-f]+)");

    @Test
    void verifiedFailureDiagnosticsForSkillExamples() throws Exception {
        final var out = Path.of("target", "failsafe-failure-examples");
        Files.createDirectories(out);
        final var process = startProbe(out);
        try {
            final var endpoint = waitForEndpoint(out.resolve("launch.out"));

            final var typoWait =
                    rpc(endpoint, "wait", "{\"text\":\"Refresh toolbr\",\"timeoutMs\":250}");
            assertTrue(typoWait.contains("\"ok\":false"), typoWait);
            assertTrue(typoWait.contains("\"TIMEOUT\""), typoWait);
            assertTrue(typoWait.contains("\"nearMatches\""), typoWait);
            assertTrue(typoWait.contains("Refresh toolbar"), typoWait);
            assertTrue(occurrences(typoWait, "\"selectorPath\"") <= 5, typoWait);
            final var typoFollowup = rpc(endpoint, "snapshot", "{}");
            assertTrue(typoFollowup.contains("Refresh toolbar"), typoFollowup);

            final var wrongPayload =
                    rpc(
                            endpoint,
                            "setText",
                            "{\"nodeId\":\"project-filter\",\"text\":\"wrong field\"}");
            assertTrue(wrongPayload.contains("setText/type require value"), wrongPayload);

            final var clickedChoice =
                    rpc(endpoint, "click", "{\"nodeId\":\"project-view\",\"timeoutMs\":1000}");
            assertTrue(clickedChoice.contains("\"ok\":true"), clickedChoice);
            final var blueStillMissing =
                    rpc(endpoint, "wait", "{\"text\":\"Archived only\",\"timeoutMs\":250}");
            assertTrue(blueStillMissing.contains("\"ok\":false"), blueStillMissing);
            final var selectedChoice =
                    rpc(
                            endpoint,
                            "setValue",
                            "{\"nodeId\":\"project-view\",\"value\":\"Archived only\"}");
            assertTrue(selectedChoice.contains("\"ok\":true"), selectedChoice);
            assertTrue(
                    rpc(endpoint, "wait", "{\"text\":\"Archived only\",\"timeoutMs\":1000}")
                            .contains("\"ok\":true"));

            final var snapshot = rpc(endpoint, "snapshot", "{}");
            assertTrue(
                    snapshot.contains(
                            "\"type\":\"Button\",\"id\":\"\",\"nodeId\":\"\",\"text\":\"Refresh"
                                    + " toolbar\""),
                    snapshot);
            assertFalse(snapshot.contains("\"id\":\"project-toolbar-refresh\""), snapshot);

            final var before = out.resolve("before.png").toAbsolutePath();
            final var after = out.resolve("after.png").toAbsolutePath();
            final var diff = out.resolve("diff.png").toAbsolutePath();
            assertTrue(
                    rpc(endpoint, "screenshot", "{\"path\":\"" + jsonPath(before) + "\"}")
                            .contains("\"source\":"));
            assertTrue(Files.isRegularFile(before));
            assertTrue(
                    rpc(endpoint, "scrollToIndex", "{\"nodeId\":\"recent-projects\",\"index\":2}")
                            .contains("\"ok\":true"));
            final var listClick = rpc(endpoint, "click", "{\"textExact\":\"Cygnus\"}");
            assertTrue(listClick.contains("\"ok\":true"), listClick);
            assertTrue(
                    rpc(endpoint, "wait", "{\"text\":\"Opened Cygnus\",\"timeoutMs\":2000}")
                            .contains("\"ok\":true"));
            assertTrue(
                    rpc(endpoint, "screenshot", "{\"path\":\"" + jsonPath(after) + "\"}")
                            .contains("\"source\":"));
            assertTrue(Files.isRegularFile(after));
            final var imageDiff =
                    new ProcessBuilder(
                                    java(),
                                    "-jar",
                                    Path.of("target", "fxdriver.jar").toString(),
                                    "image-diff",
                                    before.toString(),
                                    after.toString(),
                                    diff.toString())
                            .redirectErrorStream(true)
                            .start();
            assertTrue(imageDiff.waitFor(10, TimeUnit.SECONDS));
            final var diffOut = new String(imageDiff.getInputStream().readAllBytes());
            assertEquals(0, imageDiff.exitValue(), diffOut);
            assertTrue(diffOut.contains("\"changedPixels\":"), diffOut);
            assertTrue(Files.isRegularFile(diff));

            final var events = rpc(endpoint, "events", "{}");
            assertTrue(events.contains("\"method\":\"wait\""), events);
            assertTrue(events.contains("\"ok\":false"), events);
            assertTrue(events.contains("\"method\":\"setText\""), events);
        } finally {
            shutdown(process, out.resolve("launch.out"));
        }
    }

    private static Process startProbe(final Path out) throws IOException {
        final var command = new ArrayList<String>();
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

    private static List<String> probeCommand() {
        return FxDriverTestHarness.applicationCommand(FxDriverDataApp.class);
    }

    private static Endpoint waitForEndpoint(final Path output) throws Exception {
        final var endpoint = FxDriverTestHarness.awaitEndpoint(output);
        return new Endpoint(endpoint.port(), endpoint.token());
    }

    private static String rpc(final Endpoint endpoint, final String method, final String params)
            throws Exception {
        return FxDriverTestHarness.rpc(
                new FxDriverTestHarness.Endpoint(endpoint.port(), endpoint.token()),
                method,
                params);
    }

    private static void shutdown(final Process process, final Path output) throws Exception {
        try {
            final var endpoint = maybeEndpoint(output);
            if (endpoint.isPresent()) {
                rpc(endpoint.get(), "shutdown", "{}");
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
        final var port = PORT.matcher(text);
        final var token = TOKEN.matcher(text);
        if (!port.find() || !token.find()) {
            return Optional.empty();
        }
        return Optional.of(new Endpoint(Integer.parseInt(port.group(1)), token.group(1)));
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

    private static String jsonPath(final Path path) {
        return path.toString().replace("\\", "\\\\");
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
}
