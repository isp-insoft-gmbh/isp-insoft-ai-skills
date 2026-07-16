package fxdriver;

import com.sun.tools.attach.VirtualMachine;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;

public final class FxDriver {
    private static final SecureRandom RANDOM = new SecureRandom();

    private FxDriver() {}

    public static void main(final String... args) throws Exception {
        if (args.length < 1) {
            usage();
        }
        switch (args[0]) {
            case "attach" -> attach(args);
            case "launch" -> launch(args);
            case "rpc" -> rpc(args);
            case "screenshot" -> screenshot(args);
            case "image-summary" -> imageSummary(args);
            case "image-diff" -> imageDiff(args);
            default -> usage();
        }
    }

    private static void attach(final String... args) throws Exception {
        final var options = commandOptions(args, 1);
        if (options.positionals().isEmpty()) {
            System.err.println("missing pid");
            System.exit(2);
        }

        final var pid = options.positionals().getFirst();
        final var requestedPort =
                options.positionals().size() >= 2
                        ? Integer.parseInt(options.positionals().get(1))
                        : 0;
        final var agent = agentJar();
        final var token = token();
        final var endpointFile = endpointFile("attach-" + pid);

        final var vm = VirtualMachine.attach(pid);
        try {
            vm.loadAgent(
                    agent.toString(),
                    "port=" + requestedPort + ",token=" + token + ",endpointFile=" + endpointFile);
        } finally {
            vm.detach();
        }

        final var endpoint = waitForEndpoint(endpointFile, Duration.ofSeconds(10));
        waitForRpc(endpoint.port(), endpoint.token(), Duration.ofSeconds(10));
        printEndpoint(
                "attached pid=" + pid,
                endpoint,
                agent,
                Long.parseLong(pid),
                -1,
                options.machineJson());
    }

    private static void launch(final String... args) throws Exception {
        final var options = launchOptions(args);
        final var requestedPort = options.port();
        final var separator = options.separator();
        if (args.length <= separator
                || !"--".equals(args[separator])
                || args.length <= separator + 1) {
            System.err.println(
                    "usage: fxdriver launch [--json|--quiet] [port] -- java [java-options...]"
                            + " <main-or-jar> [args...]");
            System.exit(2);
        }

        final var agent = agentJar();
        final var token = token();
        final var endpointFile = endpointFile("launch");

        final var command = new ArrayList<String>();
        for (var i = separator + 1; i < args.length; i++) {
            command.add(args[i]);
        }
        if (command.isEmpty() || !isJavaCommand(command.getFirst())) {
            System.err.println("fxdriver launch currently supports direct java commands only");
            System.err.println(
                    "example: java -jar fxdriver.jar launch -- java --module-path ... --add-modules"
                            + " javafx.controls -cp target/classes app.Main");
            System.exit(2);
        }
        command.add(
                1,
                "-javaagent:"
                        + agent
                        + "=port="
                        + requestedPort
                        + ",token="
                        + token
                        + ",endpointFile="
                        + endpointFile);

        final var startedAt = System.nanoTime();
        final var builder = new ProcessBuilder(command);
        final var process = options.machineJson() ? builder.start() : builder.inheritIO().start();
        final var stdout =
                options.machineJson()
                        ? forwardToStderr(process.getInputStream(), "fxdriver-child-stdout")
                        : null;
        final var stderr =
                options.machineJson()
                        ? forwardToStderr(process.getErrorStream(), "fxdriver-child-stderr")
                        : null;
        Runtime.getRuntime()
                .addShutdownHook(new Thread(() -> destroyTree(process), "fxdriver-launch-cleanup"));
        try {
            final var endpoint = waitForEndpoint(endpointFile, Duration.ofSeconds(10));
            waitForRpc(endpoint.port(), endpoint.token(), Duration.ofSeconds(10));
            final var startupMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            printEndpoint(
                    "launched pid=" + process.pid() + " startupMs=" + startupMs + " mode=javaagent",
                    endpoint,
                    agent,
                    process.pid(),
                    startupMs,
                    options.machineJson());
            final var exit = process.waitFor();
            join(stdout);
            join(stderr);
            System.exit(exit);
        } catch (final Throwable throwable) {
            destroyTree(process);
            process.waitFor(5, TimeUnit.SECONDS);
            join(stdout);
            join(stderr);
            throw throwable;
        }
    }

    private static void destroyTree(final Process process) {
        process.descendants().forEach(child -> child.destroyForcibly());
        process.destroyForcibly();
    }

    private static Path agentJar() throws IOException {
        try {
            final var uri =
                    FxDriver.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            final var path = Path.of(uri).toAbsolutePath();
            if (Files.isRegularFile(path)) {
                return path;
            }
        } catch (final Exception exception) {
            throw new IOException("cannot locate fxdriver jar", exception);
        }
        final var dev = Path.of("target", "fxdriver.jar").toAbsolutePath();
        if (Files.isRegularFile(dev)) {
            return dev;
        }
        throw new IOException("cannot locate fxdriver jar; run mvn package");
    }

    private static boolean isJavaCommand(final String command) {
        final var name = Path.of(command).getFileName().toString();
        return name.equals("java") || name.equals("java.exe");
    }

    private record Endpoint(int port, String token, Path file) {}

    private record CommandOptions(boolean machineJson, ArrayList<String> positionals) {}

    private record LaunchOptions(boolean machineJson, int port, int separator) {}

    private static CommandOptions commandOptions(final String[] args, final int start) {
        var machineJson = false;
        final var positionals = new ArrayList<String>();
        for (var i = start; i < args.length; i++) {
            if ("--json".equals(args[i]) || "--quiet".equals(args[i])) {
                machineJson = true;
            } else {
                positionals.add(args[i]);
            }
        }
        return new CommandOptions(machineJson, positionals);
    }

    private static LaunchOptions launchOptions(final String[] args) {
        var machineJson = false;
        var requestedPort = 0;
        var separator = -1;
        for (var i = 1; i < args.length; i++) {
            if ("--".equals(args[i])) {
                separator = i;
                break;
            }
            if ("--json".equals(args[i]) || "--quiet".equals(args[i])) {
                machineJson = true;
            } else {
                requestedPort = Integer.parseInt(args[i]);
            }
        }
        return new LaunchOptions(
                machineJson, requestedPort, separator < 0 ? args.length : separator);
    }

    private static String token() {
        final var bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static Path endpointFile(final String prefix) throws IOException {
        final var dir = Path.of("target", "fxdriver-endpoints").toAbsolutePath();
        Files.createDirectories(dir);
        return dir.resolve(prefix + "-" + System.nanoTime() + ".json");
    }

    private static Thread forwardToStderr(final InputStream input, final String name) {
        final var thread =
                new Thread(
                        () -> {
                            try (input) {
                                input.transferTo(System.err);
                            } catch (final IOException ignored) {
                                // Child exit and endpoint state remain authoritative.
                            }
                        },
                        name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void join(final Thread thread) throws InterruptedException {
        if (thread != null) {
            thread.join(5_000);
        }
    }

    private static Endpoint waitForEndpoint(final Path file, final Duration timeout)
            throws Exception {
        final var deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(file)) {
                try {
                    final var json = Files.readString(file);
                    return new Endpoint(jsonInt(json, "port"), jsonString(json, "token"), file);
                } catch (final IOException | RuntimeException exception) {
                }
            }
            Thread.sleep(100);
        }
        throw new IOException("fxdriver endpoint file was not written: " + file);
    }

    private static void waitForRpc(final int port, final String token, final Duration timeout)
            throws Exception {
        final var deadline = System.nanoTime() + timeout.toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                rpcBody(port, token, "ping", "{}");
                return;
            } catch (final Exception exception) {
                last = exception;
                Thread.sleep(100);
            }
        }
        throw new IOException("fxdriver RPC did not start on port " + port, last);
    }

    private static void printEndpoint(
            final String label,
            final Endpoint endpoint,
            final Path agent,
            final long pid,
            final long startupMs,
            final boolean machineJson) {
        System.out.println(endpointJson(endpoint, pid, startupMs));
        if (machineJson) {
            return;
        }
        System.out.printf(
                "fxdriver %s rpc=http://127.0.0.1:%d/rpc token=%s endpoint=%s%n",
                label, endpoint.port(), endpoint.token(), endpoint.file());
        System.out.printf("export FXDRIVER_TOKEN=%s%n", endpoint.token());
        System.out.printf(
                "try: java -jar %s rpc %d highlight '{\"selector\":\"Button\"}'%n",
                agent.getFileName(), endpoint.port());
    }

    private static String endpointJson(
            final Endpoint endpoint, final long pid, final long startupMs) {
        return "{\"pid\":"
                + pid
                + ",\"port\":"
                + endpoint.port()
                + ",\"token\":\""
                + jsonEscape(endpoint.token())
                + "\",\"endpoint\":\"http://127.0.0.1:"
                + endpoint.port()
                + "/rpc\",\"endpointFile\":\""
                + jsonEscape(endpoint.file().toString())
                + "\",\"startupMs\":"
                + startupMs
                + "}";
    }

    private static void rpc(final String... args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: fxdriver rpc <port> <method> [params-json] [token]");
            System.exit(2);
        }
        final var body =
                rpcBody(
                        Integer.parseInt(args[1]),
                        tokenArg(args, 4),
                        args[2],
                        args.length >= 4 ? args[3] : "{}");
        System.out.println(body);
        if (body.contains("\"error\"")) {
            System.exit(1);
        }
    }

    private static void screenshot(final String... args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: fxdriver screenshot <port> <path> [token]");
            System.exit(2);
        }
        final var port = Integer.parseInt(args[1]);
        final var path = Path.of(args[2]).toAbsolutePath();
        final var response =
                rpcBody(
                        port,
                        tokenArg(args, 3),
                        "screenshot",
                        "{\"path\":\"" + jsonEscape(path.toString()) + "\"}");
        if (response.contains("\"error\"")) {
            System.out.println(response);
            System.exit(1);
        }
        System.out.println(
                "{\"screenshot\":" + response + ",\"summary\":" + imageSummaryJson(path) + "}");
    }

    private static void imageSummary(final String... args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: fxdriver image-summary <path>");
            System.exit(2);
        }
        System.out.println(imageSummaryJson(Path.of(args[1])));
    }

    private static void imageDiff(final String... args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: fxdriver image-diff <before> <after> [diff-png]");
            System.exit(2);
        }
        final var diffPath = args.length >= 4 ? Path.of(args[3]) : null;
        System.out.println(imageDiffJson(Path.of(args[1]), Path.of(args[2]), diffPath));
    }

    private static String tokenArg(final String[] args, final int index) {
        if (args.length > index && !args[index].isBlank()) {
            return args[index];
        }
        final var token = System.getenv("FXDRIVER_TOKEN");
        return token == null ? "" : token;
    }

    private static String rpcBody(
            final int port, final String token, final String method, final String params)
            throws Exception {
        final var body =
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\""
                        + jsonEscape(method)
                        + "\",\"params\":"
                        + params
                        + "}";
        final var builder =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/rpc"))
                        .timeout(Duration.ofSeconds(45))
                        .header("content-type", "application/json");
        if (!token.isBlank()) {
            builder.header("fxdriver-token", token);
        }
        final var request = builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
        final var response =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    static String imageSummaryJson(final Path requestedPath) throws IOException {
        final var path = requestedPath.toAbsolutePath();
        final var image = readImage(path);
        long red = 0;
        long green = 0;
        long blue = 0;
        long alpha = 0;
        for (var y = 0; y < image.getHeight(); y++) {
            for (var x = 0; x < image.getWidth(); x++) {
                final var argb = image.getRGB(x, y);
                alpha += (argb >>> 24) & 0xff;
                red += (argb >>> 16) & 0xff;
                green += (argb >>> 8) & 0xff;
                blue += argb & 0xff;
            }
        }
        final long pixels = (long) image.getWidth() * image.getHeight();
        return "{\"path\":\""
                + jsonEscape(path.toString())
                + "\",\"bytes\":"
                + Files.size(path)
                + ",\"width\":"
                + image.getWidth()
                + ",\"height\":"
                + image.getHeight()
                + ",\"avgColor\":{\"r\":"
                + red / pixels
                + ",\"g\":"
                + green / pixels
                + ",\"b\":"
                + blue / pixels
                + ",\"a\":"
                + alpha / pixels
                + "}"
                + ",\"ahash\":\""
                + averageHash(image)
                + "\"}";
    }

    static String imageDiffJson(
            final Path beforePath, final Path afterPath, final Path requestedDiffPath)
            throws IOException {
        final var before = readImage(beforePath);
        final var after = readImage(afterPath);
        final var width = Math.min(before.getWidth(), after.getWidth());
        final var height = Math.min(before.getHeight(), after.getHeight());
        var changed = 0L;
        var minX = width;
        var minY = height;
        var maxX = -1;
        var maxY = -1;
        final BufferedImage diff =
                requestedDiffPath == null
                        ? null
                        : new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                final var left = before.getRGB(x, y);
                final var right = after.getRGB(x, y);
                if (left == right) {
                    if (diff != null) {
                        diff.setRGB(x, y, dim(right));
                    }
                    continue;
                }
                changed++;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                if (diff != null) {
                    diff.setRGB(x, y, 0xffff00ff);
                }
            }
        }
        String diffField = "null";
        if (diff != null) {
            final var diffPath = requestedDiffPath.toAbsolutePath();
            if (diffPath.getParent() != null) {
                Files.createDirectories(diffPath.getParent());
            }
            writeOptimizedPng(diff, diffPath);
            diffField = "\"" + jsonEscape(diffPath.toString()) + "\"";
        }
        final var compared = (long) width * height;
        final var changedPercent = compared == 0 ? 0.0 : (changed * 100.0) / compared;
        return "{\"before\":\""
                + jsonEscape(beforePath.toAbsolutePath().toString())
                + "\",\"after\":\""
                + jsonEscape(afterPath.toAbsolutePath().toString())
                + "\",\"diff\":"
                + diffField
                + ",\"beforeSize\":{\"w\":"
                + before.getWidth()
                + ",\"h\":"
                + before.getHeight()
                + "}"
                + ",\"afterSize\":{\"w\":"
                + after.getWidth()
                + ",\"h\":"
                + after.getHeight()
                + "}"
                + ",\"compared\":{\"w\":"
                + width
                + ",\"h\":"
                + height
                + "}"
                + ",\"changedPixels\":"
                + changed
                + ",\"changedPercent\":"
                + String.format(java.util.Locale.ROOT, "%.4f", changedPercent)
                + ",\"bounds\":"
                + (changed == 0
                        ? "null"
                        : "{\"x\":"
                                + minX
                                + ",\"y\":"
                                + minY
                                + ",\"w\":"
                                + (maxX - minX + 1)
                                + ",\"h\":"
                                + (maxY - minY + 1)
                                + "}")
                + ",\"sizeMismatch\":"
                + (before.getWidth() != after.getWidth() || before.getHeight() != after.getHeight())
                + "}";
    }

    private static void writeOptimizedPng(final BufferedImage image, final Path path)
            throws IOException {
        final var writers = ImageIO.getImageWritersByFormatName("png");
        if (!writers.hasNext()) {
            ImageIO.write(image, "png", path.toFile());
            return;
        }
        final var writer = writers.next();
        try (var output = ImageIO.createImageOutputStream(path.toFile())) {
            writer.setOutput(output);
            final var param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.0f);
            }
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private static BufferedImage readImage(final Path path) throws IOException {
        final var image = ImageIO.read(path.toFile());
        if (image == null) {
            throw new IOException("not an image: " + path);
        }
        return image;
    }

    private static int dim(final int argb) {
        final var a = (argb >>> 24) & 0xff;
        final var r = ((argb >>> 16) & 0xff) / 4;
        final var g = ((argb >>> 8) & 0xff) / 4;
        final var b = (argb & 0xff) / 4;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static String averageHash(final BufferedImage image) {
        final var gray = new int[64];
        var total = 0;
        for (var y = 0; y < 8; y++) {
            for (var x = 0; x < 8; x++) {
                final var sx = Math.min(image.getWidth() - 1, x * image.getWidth() / 8);
                final var sy = Math.min(image.getHeight() - 1, y * image.getHeight() / 8);
                final var argb = image.getRGB(sx, sy);
                final var value =
                        (int)
                                ((((argb >>> 16) & 0xff) * 0.299)
                                        + (((argb >>> 8) & 0xff) * 0.587)
                                        + ((argb & 0xff) * 0.114));
                gray[y * 8 + x] = value;
                total += value;
            }
        }
        final var average = total / 64;
        long bits = 0;
        for (final var value : gray) {
            bits = (bits << 1) | (value >= average ? 1L : 0L);
        }
        return "%016x".formatted(bits);
    }

    private static void usage() {
        System.err.println("usage: fxdriver attach [--json|--quiet] <pid> [port]");
        System.err.println(
                "       fxdriver launch [--json|--quiet] [port] -- java [java-options...]"
                        + " <main-or-jar> [args...]");
        System.err.println("       fxdriver rpc <port> <method> [params-json]");
        System.err.println("       fxdriver screenshot <port> <path>");
        System.err.println("       fxdriver image-summary <path>");
        System.err.println("       fxdriver image-diff <before> <after> [diff-png]");
        System.exit(2);
    }

    static int jsonInt(final String json, final String key) throws IOException {
        return Json.requiredInt(json, key);
    }

    static String jsonString(final String json, final String key) throws IOException {
        return Json.requiredString(json, key);
    }

    static String jsonEscape(final String value) {
        return Json.escape(value);
    }
}
