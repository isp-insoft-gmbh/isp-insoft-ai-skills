package fxdriver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class FxDriverBenchmarkIT {
    private static final Pattern PORT =
            Pattern.compile("fxdriver listening http://127\\.0\\.0\\.1:(\\d+)/rpc");
    private static final Pattern TOKEN = Pattern.compile("FXDRIVER_TOKEN=([0-9a-f]+)");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    void actionStateSpeedupBenchmark() throws Exception {
        assumeTrue(Boolean.getBoolean("fxdriver.benchmark"));

        final var out = Path.of("target", "fxdriver-benchmark");
        Files.createDirectories(out);
        final var process = startProbe(out);
        try {
            final var endpoint = waitForEndpoint(out.resolve("launch.log"));
            waitForProbe(endpoint);

            final var iterations =
                    Math.max(3, Integer.getInteger("fxdriver.benchmark.iterations", 10));
            final var threshold =
                    Double.parseDouble(System.getProperty("fxdriver.benchmark.minSpeedup", "2.0"));
            final var realisticThreshold =
                    Double.parseDouble(
                            System.getProperty("fxdriver.benchmark.minRealisticSpeedup", "5.0"));
            final var old = new ArrayList<Sample>();
            final var state = new ArrayList<Sample>();
            final var run = new ArrayList<Sample>();
            final var oldRealistic = new ArrayList<Sample>();
            final var realisticRun = new ArrayList<Sample>();

            for (var i = 0; i < iterations; i++) {
                final var index = i;
                old.add(
                        measure(
                                () -> {
                                    final var set =
                                            rpc(
                                                    endpoint,
                                                    "setText",
                                                    "{\"nodeId\":\"probe-field\",\"value\":\"old-"
                                                            + index
                                                            + "\"}");
                                    final var snapshot = rpc(endpoint, "snapshot", "{}");
                                    return set.bytes() + snapshot.bytes();
                                }));
                state.add(
                        measure(
                                () ->
                                        rpc(
                                                        endpoint,
                                                        "setText",
                                                        "{\"nodeId\":\"probe-field\",\"value\":\"state-"
                                                                + index
                                                                + "\",\"returnState\":\"compact\"}")
                                                .bytes()));
                run.add(
                        measure(
                                () ->
                                        rpc(
                                                        endpoint,
                                                        "run",
                                                        "{\"steps\":[{\"op\":\"setText\",\"nodeId\":\"probe-field\",\"value\":\"run-"
                                                                + index
                                                                + "\"},{\"op\":\"wait\",\"text\":\"run-"
                                                                + index
                                                                + "\"}],\"returnState\":\"compact\"}")
                                                .bytes()));
                oldRealistic.add(measure(() -> oldRealisticFlow(endpoint, index)));
                realisticRun.add(measure(() -> realisticRunFlow(endpoint, index)));
            }

            final var oldSummary = Summary.of(old);
            final var stateSummary = Summary.of(state);
            final var runSummary = Summary.of(run);
            final var oldRealisticSummary = Summary.of(oldRealistic);
            final var realisticRunSummary = Summary.of(realisticRun);
            final var stateSpeedup = oldSummary.medianMs() / stateSummary.medianMs();
            final var runSpeedup = oldSummary.medianMs() / runSummary.medianMs();
            final var realisticRunSpeedup =
                    oldRealisticSummary.medianMs() / realisticRunSummary.medianMs();
            final var json =
                    "{\n"
                            + "  \"environment\": "
                            + environmentJson()
                            + ",\n"
                            + "  \"iterations\": "
                            + iterations
                            + ",\n"
                            + "  \"threshold\": "
                            + threshold
                            + ",\n"
                            + "  \"realisticThreshold\": "
                            + realisticThreshold
                            + ",\n"
                            + "  \"oldActionSnapshot\": "
                            + oldSummary.json()
                            + ",\n"
                            + "  \"returnState\": "
                            + stateSummary.json(stateSpeedup)
                            + ",\n"
                            + "  \"run\": "
                            + runSummary.json(runSpeedup)
                            + ",\n"
                            + "  \"oldRealisticActionSnapshots\": "
                            + oldRealisticSummary.json()
                            + ",\n"
                            + "  \"realisticRun\": "
                            + realisticRunSummary.json(realisticRunSpeedup)
                            + ",\n"
                            + "  \"coveredMethods\": "
                            + jsonStrings(coveredMethods())
                            + ",\n"
                            + "  \"samples\": {\n"
                            + "    \"oldActionSnapshot\": "
                            + samplesJson(old)
                            + ",\n"
                            + "    \"returnState\": "
                            + samplesJson(state)
                            + ",\n"
                            + "    \"run\": "
                            + samplesJson(run)
                            + ",\n"
                            + "    \"oldRealisticActionSnapshots\": "
                            + samplesJson(oldRealistic)
                            + ",\n"
                            + "    \"realisticRun\": "
                            + samplesJson(realisticRun)
                            + "\n  }\n}"
                            + System.lineSeparator();
            Files.writeString(out.resolve("speedup.json"), json);

            assertTrue(
                    stateSpeedup >= threshold,
                    () -> "returnState speedup " + stateSpeedup + " below " + threshold);
            assertTrue(
                    runSpeedup >= threshold,
                    () -> "run speedup " + runSpeedup + " below " + threshold);
            assertTrue(
                    realisticRunSpeedup >= realisticThreshold,
                    () ->
                            "realistic run speedup "
                                    + realisticRunSpeedup
                                    + " below "
                                    + realisticThreshold);
        } finally {
            shutdown(process, out.resolve("launch.log"));
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
                .redirectErrorStream(true)
                .redirectOutput(out.resolve("launch.log").toFile())
                .start();
    }

    private static List<String> probeCommand() {
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
        command.add(javafxModules());
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
            Thread.sleep(50);
        }
        throw new AssertionError(
                "fxdriver endpoint not printed; output=" + Files.readString(output));
    }

    private static void waitForProbe(final Endpoint endpoint) throws Exception {
        final var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (rpc(endpoint, "query", "{\"limit\":80}").body().contains("probe-field")) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("probe field did not appear");
    }

    private static int oldRealisticFlow(final Endpoint endpoint, final int index) throws Exception {
        var bytes = 0;
        bytes +=
                actionThenSnapshot(
                        endpoint,
                        "setText",
                        "{\"nodeId\":\"probe-field\",\"value\":\"realistic-old-" + index + "\"}");
        bytes +=
                actionThenSnapshot(
                        endpoint,
                        "selectIndex",
                        "{\"nodeId\":\"probe-list\",\"index\":" + (index % 3) + "}");
        bytes +=
                actionThenSnapshot(
                        endpoint,
                        "selectIndex",
                        "{\"nodeId\":\"probe-table\",\"index\":" + (index % 2) + "}");
        bytes +=
                actionThenSnapshot(
                        endpoint, "scrollToIndex", "{\"nodeId\":\"probe-tree\",\"index\":1}");
        bytes += actionThenSnapshot(endpoint, "expand", "{\"nodeId\":\"probe-titled\"}");
        bytes += actionThenSnapshot(endpoint, "collapse", "{\"nodeId\":\"probe-titled\"}");
        bytes +=
                actionThenSnapshot(
                        endpoint,
                        "setValue",
                        "{\"nodeId\":\"probe-choice\",\"value\":\"" + choiceValue(index) + "\"}");
        bytes +=
                actionThenSnapshot(
                        endpoint,
                        "setValue",
                        "{\"nodeId\":\"probe-slider\",\"value\":\"" + (20 + index) + "\"}");
        bytes += actionThenSnapshot(endpoint, "increment", "{\"nodeId\":\"probe-spinner\"}");
        bytes += actionThenSnapshot(endpoint, "decrement", "{\"nodeId\":\"probe-spinner\"}");
        bytes += actionThenSnapshot(endpoint, "showPopup", "{\"nodeId\":\"probe-combo\"}");
        bytes += actionThenSnapshot(endpoint, "hidePopup", "{\"nodeId\":\"probe-combo\"}");
        bytes +=
                actionThenSnapshot(
                        endpoint,
                        "webExecuteScript",
                        "{\"nodeId\":\"probe-web\",\"script\":\"document.title\"}");
        return bytes;
    }

    private static int realisticRunFlow(final Endpoint endpoint, final int index) throws Exception {
        return rpc(endpoint, "run", realisticRunParams(index)).bytes();
    }

    private static int actionThenSnapshot(
            final Endpoint endpoint, final String method, final String params) throws Exception {
        return rpc(endpoint, method, params).bytes() + rpc(endpoint, "snapshot", "{}").bytes();
    }

    private static String realisticRunParams(final int index) {
        return "{\"steps\":["
                + "{\"op\":\"setText\",\"nodeId\":\"probe-field\",\"value\":\"realistic-run-"
                + index
                + "\"},"
                + "{\"op\":\"selectIndex\",\"nodeId\":\"probe-list\",\"index\":"
                + (index % 3)
                + "},"
                + "{\"op\":\"selectIndex\",\"nodeId\":\"probe-table\",\"index\":"
                + (index % 2)
                + "},"
                + "{\"op\":\"scrollToIndex\",\"nodeId\":\"probe-tree\",\"index\":1},"
                + "{\"op\":\"expand\",\"nodeId\":\"probe-titled\"},"
                + "{\"op\":\"collapse\",\"nodeId\":\"probe-titled\"},"
                + "{\"op\":\"setValue\",\"nodeId\":\"probe-choice\",\"value\":\""
                + choiceValue(index)
                + "\"},"
                + "{\"op\":\"setValue\",\"nodeId\":\"probe-slider\",\"value\":\""
                + (20 + index)
                + "\"},{\"op\":\"increment\",\"nodeId\":\"probe-spinner\"},"
                + "{\"op\":\"decrement\",\"nodeId\":\"probe-spinner\"},"
                + "{\"op\":\"showPopup\",\"nodeId\":\"probe-combo\"},"
                + "{\"op\":\"hidePopup\",\"nodeId\":\"probe-combo\"},"
                + "{\"op\":\"webExecuteScript\",\"nodeId\":\"probe-web\",\"script\":\"document.title\"}"
                + "],\"returnState\":\"compact\"}";
    }

    private static String choiceValue(final int index) {
        return List.of("red", "green", "blue").get(index % 3);
    }

    private static List<String> coveredMethods() {
        return List.of(
                "query",
                "setText",
                "snapshot",
                "selectIndex",
                "scrollToIndex",
                "expand",
                "collapse",
                "setValue",
                "increment",
                "decrement",
                "showPopup",
                "hidePopup",
                "webExecuteScript",
                "run",
                "shutdown");
    }

    private static RpcResult rpc(final Endpoint endpoint, final String method, final String params)
            throws Exception {
        final var body =
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\""
                        + method
                        + "\",\"params\":"
                        + params
                        + "}";
        final var request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + endpoint.port() + "/rpc"))
                        .timeout(Duration.ofSeconds(60))
                        .header("content-type", "application/json")
                        .header("fxdriver-token", endpoint.token())
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
        final var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertFalse(response.body().contains("\"error\""), response.body());
        return new RpcResult(
                response.body(),
                response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    private static Sample measure(final ThrowingIntSupplier supplier) throws Exception {
        final var start = System.nanoTime();
        final var bytes = supplier.getAsInt();
        return new Sample((System.nanoTime() - start) / 1_000_000.0, bytes);
    }

    private static void shutdown(final Process process, final Path output) throws Exception {
        try {
            if (Files.isRegularFile(output)) {
                final var text = Files.readString(output);
                final var port = PORT.matcher(text);
                final var token = TOKEN.matcher(text);
                if (port.find() && token.find()) {
                    try {
                        rpc(
                                new Endpoint(Integer.parseInt(port.group(1)), token.group(1)),
                                "shutdown",
                                "{}");
                    } catch (final Exception ignored) {
                        // Process cleanup below is authoritative for benchmark runs.
                    }
                }
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

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
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

    private static String environmentJson() {
        return "{\"osName\":"
                + quote(System.getProperty("os.name"))
                + ",\"osVersion\":"
                + quote(System.getProperty("os.version"))
                + ",\"osArch\":"
                + quote(System.getProperty("os.arch"))
                + ",\"javaVersion\":"
                + quote(System.getProperty("java.version"))
                + ",\"javaVmName\":"
                + quote(System.getProperty("java.vm.name"))
                + ",\"javafxVersion\":"
                + quote(System.getProperty("javafx.version", ""))
                + "}";
    }

    private static String samplesJson(final List<Sample> samples) {
        final var out = new StringBuilder("[");
        for (var i = 0; i < samples.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            final var sample = samples.get(i);
            out.append("{\"ms\":")
                    .append(number(sample.ms()))
                    .append(",\"bytes\":")
                    .append(sample.bytes())
                    .append('}');
        }
        return out.append(']').toString();
    }

    private static String jsonStrings(final List<String> values) {
        final var out = new StringBuilder("[");
        for (var i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(quote(values.get(i)));
        }
        return out.append(']').toString();
    }

    private static String quote(final String value) {
        final var out = new StringBuilder("\"");
        for (var i = 0; i < value.length(); i++) {
            final var c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('\"').toString();
    }

    private record Endpoint(int port, String token) {}

    private record RpcResult(String body, int bytes) {}

    private record Sample(double ms, int bytes) {}

    private record Summary(
            double medianMs, double p95Ms, double minMs, double maxMs, double medianBytes) {
        static Summary of(final List<Sample> samples) {
            final var times = samples.stream().map(Sample::ms).sorted().toList();
            final var bytes =
                    samples.stream().map(sample -> (double) sample.bytes()).sorted().toList();
            return new Summary(
                    median(times),
                    times.get(Math.max(0, (int) Math.ceil(times.size() * 0.95) - 1)),
                    times.getFirst(),
                    times.getLast(),
                    median(bytes));
        }

        String json() {
            return json(Double.NaN);
        }

        String json(final double speedup) {
            return "{\"medianMs\":"
                    + number(medianMs)
                    + ",\"p95Ms\":"
                    + number(p95Ms)
                    + ",\"minMs\":"
                    + number(minMs)
                    + ",\"maxMs\":"
                    + number(maxMs)
                    + ",\"medianBytes\":"
                    + number(medianBytes)
                    + (Double.isNaN(speedup) ? "" : ",\"speedupVsOldMedian\":" + number(speedup))
                    + "}";
        }
    }

    private static double median(final List<Double> values) {
        final var sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        final var middle = sorted.size() / 2;
        return sorted.size() % 2 == 0
                ? (sorted.get(middle - 1) + sorted.get(middle)) / 2.0
                : sorted.get(middle);
    }

    private static String number(final double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }

    @FunctionalInterface
    private interface ThrowingIntSupplier {
        int getAsInt() throws Exception;
    }
}
