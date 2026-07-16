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
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

final class FxDriverTestHarness {
    private static final Pattern PORT = Pattern.compile("rpc=http://127\\.0\\.0\\.1:(\\d+)/rpc");
    private static final Pattern TOKEN = Pattern.compile("FXDRIVER_TOKEN=([0-9a-f]+)");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private FxDriverTestHarness() {}

    static Session launch(
            final Class<?> application, final String artifactName, final String... args)
            throws Exception {
        final var out = artifacts(artifactName);
        final var command = new ArrayList<String>();
        command.add(java());
        command.add("-jar");
        command.add(Path.of("target", "fxdriver.jar").toString());
        command.add("launch");
        command.add("--quiet");
        command.add("--");
        command.addAll(applicationCommand(application, args));
        final var process =
                new ProcessBuilder(command)
                        .redirectOutput(out.resolve("launch.out").toFile())
                        .redirectError(out.resolve("launch.err").toFile())
                        .start();
        final var endpoint = awaitEndpoint(out.resolve("launch.out"));
        assertPureEndpointOutput(out.resolve("launch.out"));
        return new Session(process, endpoint, out);
    }

    static Process startDirect(final Class<?> application, final Path out, final String... args)
            throws IOException {
        return new ProcessBuilder(applicationCommand(application, args))
                .redirectOutput(out.resolve("app.out").toFile())
                .redirectError(out.resolve("app.err").toFile())
                .start();
    }

    static Endpoint attach(final Process application, final Path out) throws Exception {
        final var process =
                new ProcessBuilder(
                                java(),
                                "-jar",
                                Path.of("target", "fxdriver.jar").toString(),
                                "attach",
                                "--json",
                                Long.toString(application.pid()))
                        .redirectOutput(out.resolve("attach.out").toFile())
                        .redirectError(out.resolve("attach.err").toFile())
                        .start();
        assertTrue(process.waitFor(20, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), Files.readString(out.resolve("attach.err")));
        assertPureEndpointOutput(out.resolve("attach.out"));
        return awaitEndpoint(out.resolve("attach.out"));
    }

    static Path artifacts(final String name) throws IOException {
        final var out = Path.of("target", name);
        Files.createDirectories(out);
        return out;
    }

    static List<String> applicationCommand(
            final Class<?> application, final String... applicationArgs) {
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
        command.add(application.getName());
        command.addAll(List.of(applicationArgs));
        return command;
    }

    static Endpoint awaitEndpoint(final Path output) throws Exception {
        final var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            final var endpoint = maybeEndpoint(output);
            if (endpoint.isPresent()) {
                return endpoint.get();
            }
            Thread.sleep(100);
        }
        throw new AssertionError(
                "fxdriver endpoint not printed; output="
                        + (Files.isRegularFile(output) ? Files.readString(output) : "<missing>"));
    }

    static void awaitVisible(final Endpoint endpoint, final String nodeId) throws Exception {
        final var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            final var response = rpc(endpoint, "query", "{\"nodeId\":\"" + nodeId + "\"}");
            if (response.contains("\"id\":\"" + nodeId + "\"")) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("JavaFX fixture never exposed #" + nodeId);
    }

    static String rpc(final Endpoint endpoint, final String method, final String params)
            throws Exception {
        return rpc(endpoint, endpoint.token(), method, params);
    }

    static String rpc(
            final Endpoint endpoint, final String token, final String method, final String params)
            throws Exception {
        return rawJson(
                        endpoint,
                        token,
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\""
                                + method
                                + "\",\"params\":"
                                + params
                                + "}")
                .body();
    }

    static Response rawJson(final Endpoint endpoint, final String token, final String body)
            throws Exception {
        final var request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + endpoint.port() + "/rpc"))
                        .timeout(Duration.ofSeconds(60))
                        .header("content-type", "application/json")
                        .header("fxdriver-token", token)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
        final var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    static Response rawRpc(final Endpoint endpoint, final String method, final String params)
            throws Exception {
        final var response =
                rawJson(
                        endpoint,
                        endpoint.token(),
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\""
                                + method
                                + "\",\"params\":"
                                + params
                                + "}");
        assertEquals(200, response.status());
        return response;
    }

    static void assertPureEndpointOutput(final Path output) throws IOException {
        final var lines = Files.readAllLines(output);
        assertEquals(1, lines.size(), Files.readString(output));
        final var line = lines.getFirst();
        assertTrue(Json.hasKey(line, "pid"), line);
        assertTrue(Json.hasKey(line, "port"), line);
        assertTrue(Json.hasKey(line, "token"), line);
        assertTrue(Json.hasKey(line, "endpoint"), line);
        assertTrue(Json.hasKey(line, "endpointFile"), line);
    }

    static void destroy(final Process process) throws InterruptedException {
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

    static String java() {
        return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
                .toString();
    }

    static Optional<Endpoint> maybeEndpoint(final Path output) throws IOException {
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
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    record Endpoint(int port, String token) {}

    record Response(int status, String body) {
        int bytes() {
            return body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
    }

    record Session(Process process, Endpoint endpoint, Path artifacts) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            try {
                rpc(endpoint, "shutdown", "{}");
            } finally {
                destroy(process);
            }
        }
    }
}
