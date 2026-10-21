package fxdriver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.AccessibleAttribute;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.SubScene;
import javafx.scene.control.Accordion;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Cell;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.Control;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Pagination;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumnBase;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToolBar;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.TreeView;
import javafx.scene.effect.Blend;
import javafx.scene.effect.BlendMode;
import javafx.scene.effect.Bloom;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.effect.ColorInput;
import javafx.scene.effect.Effect;
import javafx.scene.effect.Glow;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;

public final class FxDriverAgent {
    private static final String MARK = "[fxdriver] ";
    private static final Object HIGHLIGHT_STATE = new Object();
    private static final Object MARKED_TITLE = new Object();
    private static final Set<Integer> highlightedNodes = new LinkedHashSet<>();
    private static final Set<Integer> markedWindows = new LinkedHashSet<>();

    private record HighlightState(Effect previous, Effect applied) {}

    private record MarkedTitle(String previous, String applied) {}

    private static volatile boolean visualTrace;
    private static volatile int visualTraceMs = 250;
    private static volatile String visualTraceEffect = "invert";
    private static final java.util.ArrayDeque<DriverEvent> events = new java.util.ArrayDeque<>();
    private static long eventSequence;

    private record DriverEvent(long sequence, long timestamp, String json) {}

    private static final WeakIdentityHandles<Node> nodeIds = new WeakIdentityHandles<>();
    private static final WeakIdentityHandles<Window> windowIds = new WeakIdentityHandles<>();
    private static volatile ServerSocket server;
    private static volatile String authToken = "";
    private static volatile boolean exitOnShutdown;
    private static volatile Boolean shutdownExitApp;
    private static final int MAX_REQUEST_BYTES = 1_048_576;
    private static final ScopedValue<Integer> RUN_DEPTH = ScopedValue.newInstance();
    private static final AgentArtifact ARTIFACT = AgentArtifact.capture(FxDriverAgent.class);
    private static final String SENSITIVE = "fxdriver.sensitive";
    private static final String METHODS =
            "ping, version, capabilities, configure, shutdown, run, actionStatus, events,"
                + " eventsSince, clearEvents, mark, highlight, snapshot, snapshotSummary, query,"
                + " press, key, focus, fire, fireMenuItem, tableCell, tableColumns, click, type,"
                + " clear, setText, setValue, commit, showPopup, hidePopup, increment, decrement,"
                + " scroll, listItems, selectListItem, selectIndex, scrollToIndex, expand,"
                + " collapse, wait, waitForLayout, waitForQuiet, assert, screenshot,"
                + " webExecuteScript";

    private FxDriverAgent() {}

    public static void agentmain(final String args, final Instrumentation instrumentation)
            throws Exception {
        start(args);
    }

    public static void premain(final String args, final Instrumentation instrumentation)
            throws Exception {
        start(args);
    }

    private static synchronized void start(final String args) throws IOException {
        if (server != null) {
            return;
        }

        final var options = options(args);
        authToken = options.containsKey("token") ? options.get("token") : "";
        exitOnShutdown = Boolean.parseBoolean(options.getOrDefault("exitOnShutdown", "false"));
        final var port = Integer.parseInt(options.getOrDefault("port", "0"));
        shutdownExitApp = null;
        server = new ServerSocket(port, 10, InetAddress.getLoopbackAddress());
        writeEndpointFile(options.get("endpointFile"));
        final var thread = new Thread(FxDriverAgent::serve, "fxdriver-rpc");
        thread.setDaemon(true);
        thread.start();
        System.err.printf("fxdriver listening http://127.0.0.1:%d/rpc%n", server.getLocalPort());
    }

    private static Map<String, String> options(final String args) {
        if (args == null || args.isBlank()) {
            return Map.of();
        }
        final var options = new java.util.HashMap<String, String>();
        for (final var part : args.split(",")) {
            final var kv = part.split("=", 2);
            if (kv.length == 2) {
                options.put(kv[0].strip(), kv[1].strip());
            }
        }
        return options;
    }

    private static void writeEndpointFile(final String endpointFile) throws IOException {
        if (endpointFile == null || endpointFile.isBlank()) {
            return;
        }
        final var path = Path.of(endpointFile);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(
                path,
                "{\"host\":\"127.0.0.1\",\"port\":"
                        + server.getLocalPort()
                        + ",\"url\":\"http://127.0.0.1:"
                        + server.getLocalPort()
                        + "/rpc\",\"token\":\""
                        + jsonEscape(authToken)
                        + "\"}",
                StandardCharsets.UTF_8);
    }

    private static void serve() {
        while (true) {
            final var current = server;
            if (current == null || current.isClosed()) {
                return;
            }
            try {
                handle(current.accept());
            } catch (final SocketException exception) {
                if (!isClientDisconnect(exception)) {
                    exception.printStackTrace(System.err);
                }
            } catch (final IOException exception) {
                if (!isClientDisconnect(exception)) {
                    exception.printStackTrace(System.err);
                }
            } catch (final Exception exception) {
                exception.printStackTrace(System.err);
            }
        }
    }

    private static boolean authorized(final String request) {
        if (authToken.isBlank()) {
            return true;
        }
        for (final var line : request.split("\r?\n")) {
            final var colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            if (line.substring(0, colon).equalsIgnoreCase("fxdriver-token")) {
                return line.substring(colon + 1).strip().equals(authToken);
            }
        }
        return false;
    }

    private static void handle(final Socket socket) throws Exception {
        try (socket) {
            final var request = readRequest(socket);
            final var payload = httpBody(request).stripLeading();
            final var id = extractId(request);
            final var method = payload.startsWith("[") ? "batch" : requestMethod(request);
            final var allowed = authorized(request);
            var body =
                    allowed
                            ? dispatchRequest(request)
                            : errorResponse(
                                    id,
                                    -32001,
                                    "UNAUTHORIZED",
                                    "Missing or invalid fxdriver-token header");
            if (allowed) {
                try {
                    recordEvent(method, request, body);
                } catch (final RuntimeException recordingFailure) {
                    System.err.println(
                            "[fxdriver] Response/event validation failed: "
                                    + recordingFailure.getClass().getSimpleName());
                    try {
                        Json.parse(body);
                    } catch (final IllegalArgumentException invalidResponse) {
                        body =
                                errorResponse(
                                        id,
                                        -32603,
                                        "INVALID_DRIVER_RESPONSE",
                                        "Driver response was invalid; action may already have run."
                                                + " Inspect actual state before retrying.");
                    }
                }
            }
            final var bytes = body.getBytes(StandardCharsets.UTF_8);
            final var headers =
                    "HTTP/1.1 200 OK\r\n"
                            + "content-type: application/json; charset=utf-8\r\n"
                            + "content-length: "
                            + bytes.length
                            + "\r\nconnection: close\r\n\r\n";
            try {
                socket.getOutputStream().write(headers.getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().write(bytes);
            } finally {
                if (allowed && shutdownExitApp != null) completeShutdown();
            }
        }
    }

    private static String shutdownResponse(final String id, final String request) {
        shutdownExitApp =
                Boolean.TRUE.equals(shutdownExitApp)
                        || extractBoolean(request, "exitApp", exitOnShutdown);
        return "{\"jsonrpc\":\"2.0\",\"id\":"
                + id
                + ",\"result\":{\"ok\":true,\"shutdown\":true,\"exitApp\":"
                + shutdownExitApp
                + "}}";
    }

    private static void completeShutdown() throws IOException {
        final var current = server;
        server = null;
        if (current != null) current.close();
        try {
            Platform.runLater(
                    () -> {
                        try {
                            clearHighlights();
                            restoreTitles();
                        } catch (final Throwable cleanupFailure) {
                            System.err.println(
                                    "[fxdriver] Visual cleanup failed: "
                                            + cleanupFailure.getClass().getSimpleName());
                        }
                    });
        } catch (final IllegalStateException notStarted) {
            // No JavaFX visual state exists before toolkit startup.
        }
        if (Boolean.TRUE.equals(shutdownExitApp)) {
            final var exit = new Thread(() -> System.exit(0), "fxdriver-exit");
            exit.setDaemon(true);
            exit.start();
        }
    }

    private static String requestMethod(final String request) {
        try {
            final var value = Json.object(httpBody(request)).get("method");
            return value instanceof String method ? method : "";
        } catch (final IllegalArgumentException invalid) {
            return "";
        }
    }

    private static String dispatchRequest(final String request) {
        final Object payload;
        try {
            payload = Json.parse(httpBody(request));
        } catch (final IllegalArgumentException malformed) {
            return errorResponse("null", -32700, "PARSE_ERROR", "Malformed JSON");
        }
        if (payload instanceof List<?> requests) {
            if (requests.isEmpty() || requests.size() > 1000) {
                return errorResponse(
                        "null", -32600, "INVALID_REQUEST", "Batch must contain 1 to 1000 requests");
            }
            return "["
                    + String.join(
                            ",", requests.stream().map(FxDriverAgent::dispatchEnvelope).toList())
                    + "]";
        }
        return dispatchEnvelope(payload);
    }

    private static String dispatchEnvelope(final Object value) {
        if (!(value instanceof Map<?, ?> envelope)) {
            return errorResponse("null", -32600, "INVALID_REQUEST", "Request must be an object");
        }
        final var requestId = envelope.get("id");
        final var id =
                requestId == null || requestId instanceof String || requestId instanceof Number
                        ? Json.stringify(requestId)
                        : "null";
        if (!envelope.containsKey("id")
                || requestId != null
                        && !(requestId instanceof String || requestId instanceof Number)
                || !"2.0".equals(envelope.get("jsonrpc"))
                || !(envelope.get("method") instanceof String method)
                || method.isBlank()
                || (envelope.get("params") != null
                        && !(envelope.get("params") instanceof Map<?, ?>))) {
            return errorResponse(
                    id,
                    -32600,
                    "INVALID_REQUEST",
                    "Expected JSON-RPC 2.0, an explicit scalar id and named parameters;"
                            + " notifications are unsupported");
        }
        return dispatch(
                id,
                method,
                Json.stringify(envelope.get("params") == null ? Map.of() : envelope.get("params")));
    }

    private static List<String> objectArray(final String json, final String key) {
        final var value = namedParams(json).get(key);
        if (!(value instanceof List<?> list)
                || list.size() > 1000
                || list.stream().anyMatch(item -> !(item instanceof Map<?, ?>))) {
            throw new IllegalArgumentException(key + " must contain at most 1000 objects");
        }
        return list.stream().map(Json::stringify).toList();
    }

    private static String dispatch(final String id, final String method, final String request) {
        final boolean sensitive;
        try {
            sensitive = extractBoolean(request, "sensitive", false) || FxActions.requestSensitive();
        } catch (final IllegalArgumentException invalid) {
            return errorResponse(id, -32602, "INVALID_PARAMS", invalid.getMessage());
        }
        final var reply =
                FxActions.withSensitivity(sensitive, () -> dispatchRaw(id, method, request));
        return sensitive ? Json.stringify(redactSensitiveReply(Json.parse(reply), "")) : reply;
    }

    private static Object redactSensitiveReply(final Object value, final String key) {
        if (Set.of("value", "displayValue", "text", "chars", "script", "selectedText")
                .contains(key)) return "[redacted]";
        if (value instanceof Map<?, ?> map) {
            final var result = new LinkedHashMap<String, Object>();
            map.forEach(
                    (name, item) ->
                            result.put(
                                    String.valueOf(name),
                                    redactSensitiveReply(item, String.valueOf(name))));
            return result;
        }
        if (value instanceof List<?> list)
            return list.stream().map(item -> redactSensitiveReply(item, key)).toList();
        if (value instanceof String
                && !Set.of(
                                "jsonrpc",
                                "id",
                                "eid",
                                "wid",
                                "window",
                                "type",
                                "role",
                                "accessibleRole",
                                "completion",
                                "reason",
                                "mechanism",
                                "code",
                                "operations",
                                "accessibleOperations",
                                "kind",
                                "mode",
                                "units")
                        .contains(key)) return "[redacted]";
        return value;
    }

    private static String dispatchRaw(final String id, final String method, final String request) {
        if (shutdownExitApp != null && !"shutdown".equals(method)) {
            return errorResponse(
                    id,
                    -32000,
                    "SHUTTING_DOWN",
                    "Shutdown already requested; no later action dispatched");
        }
        try {
            return switch (method) {
                case "ping" ->
                        "{\"jsonrpc\":\"2.0\",\"id\":"
                                + id
                                + ",\"result\":{\"ok\":true,\"agent\":\"fxdriver\"}}";
                case "version" -> versionResponse(id);
                case "capabilities" -> capabilitiesResponse(id);
                case "configure" -> configureResponse(id, request);
                case "run" -> runResponse(id, request);
                case "actionStatus" -> actionStatusResponse(id, request);
                case "shutdown" -> shutdownResponse(id, request);
                case "events" -> eventsResponse(id);
                case "eventsSince" -> eventsSinceResponse(id, request);
                case "clearEvents" -> clearEventsResponse(id);
                case "mark" ->
                        "{\"jsonrpc\":\"2.0\",\"id\":"
                                + id
                                + ",\"result\":{\"marked\":"
                                + markStages()
                                + "}}";
                case "highlight" -> highlightResponse(id, request);
                case "snapshot" -> snapshotResponse(id, request);
                case "snapshotSummary" -> snapshotSummaryResponse(id);
                case "query" -> queryResponse(id, request);
                case "press" -> pressResponse(id, request);
                case "key" -> keyResponse(id, request);
                case "focus" -> focusResponse(id, request);
                case "fire" -> fireResponse(id, request);
                case "fireMenuItem" -> fireMenuItemResponse(id, request);
                case "tableCell" -> tableCellResponse(id, request);
                case "tableColumns" -> tableColumnsResponse(id, request);
                case "click" -> clickResponse(id, request);
                case "type" -> typeResponse(id, request);
                case "clear" -> clearResponse(id, request);
                case "setText" -> setTextResponse(id, request);
                case "setValue" -> setValueResponse(id, request);
                case "commit" -> commitResponse(id, request);
                case "showPopup" -> popupResponse(id, request, true);
                case "hidePopup" -> popupResponse(id, request, false);
                case "increment" -> incrementResponse(id, request, true);
                case "decrement" -> incrementResponse(id, request, false);
                case "scroll" -> scrollResponse(id, request);
                case "listItems" -> listItemsResponse(id, request);
                case "selectListItem" -> selectListItemResponse(id, request);
                case "selectIndex" -> selectIndexResponse(id, request);
                case "scrollToIndex" -> scrollToIndexResponse(id, request);
                case "expand" -> expandResponse(id, request, true);
                case "collapse" -> expandResponse(id, request, false);
                case "wait" -> waitResponse(id, request);
                case "waitForLayout" -> layoutResponse(id, request);
                case "waitForQuiet" -> quietResponse(id, request);
                case "assert" -> assertResponse(id, request);
                case "screenshot" -> screenshotResponse(id, request);
                case "webExecuteScript" -> webExecuteScriptResponse(id, request);
                default -> errorResponse(id, -32601, "METHOD_NOT_FOUND", "methods: " + METHODS);
            };
        } catch (final FxActions.Pending pending) {
            try {
                return actionStatusReply(id, pending.actionId(), request, true);
            } catch (final Exception diagnosticFailure) {
                return "{\"jsonrpc\":\"2.0\",\"id\":"
                        + id
                        + ",\"result\":{\"ok\":true,\"completion\":\"pending\",\"actionId\":"
                        + pending.actionId()
                        + ",\"reason\":\"nested-event-loop\",\"stateError\":\""
                        + jsonEscape(diagnosticFailure.getClass().getSimpleName())
                        + "\"}}";
            }
        } catch (final FxActions.TimedOut timeout) {
            return "{\"jsonrpc\":\"2.0\",\"id\":"
                    + id
                    + ",\"result\":{\"ok\":false,\"actionId\":"
                    + timeout.actionId()
                    + ",\"completion\":\""
                    + timeout.completion()
                    + "\",\"error\":{\"code\":\"ACTION_TIMEOUT\",\"message\":\""
                    + jsonEscape(timeout.getMessage())
                    + "\"}}}";
        } catch (final TargetFailure failure) {
            try {
                return resultResponse(
                        id,
                        "\"query\":\""
                                + jsonEscape(failure.query)
                                + "\",\"matches\":"
                                + failure.matches
                                + ",\"candidates\":["
                                + failure.candidates
                                + "]"
                                + okOrError(false, failure.code, failure.getMessage()),
                        request);
            } catch (final Exception diagnosticFailure) {
                return errorResponse(id, -32000, failure.code, failure.getMessage());
            }
        } catch (final Throwable throwable) {
            return errorResponse(
                    id,
                    -32000,
                    throwable.getClass().getSimpleName(),
                    throwable.getMessage() == null ? "internal error" : throwable.getMessage());
        }
    }

    private static String actionStatusResponse(final String id, final String request)
            throws Exception {
        return actionStatusReply(id, extractLong(request, "actionId", -1), request);
    }

    private static String actionStatusReply(
            final String id, final long actionId, final String request) throws Exception {
        return actionStatusReply(id, actionId, request, false);
    }

    private static String actionStatusReply(
            final String id, final long actionId, final String request, final boolean yielding)
            throws Exception {
        final var status = FxActions.status(actionId);
        if (status == null) {
            return resultResponse(
                    id,
                    "\"actionId\":"
                            + actionId
                            + okOrError(false, "UNKNOWN_ACTION", "Action is unknown or expired"),
                    request);
        }
        final var failed =
                "failed".equals(status.completion()) || "cancelled".equals(status.completion());
        return resultResponse(
                id,
                "\"actionId\":"
                        + actionId
                        + ",\"completion\":\""
                        + status.completion()
                        + "\",\"sensitive\":"
                        + status.sensitive()
                        + ("pending".equals(status.completion())
                                ? ",\"reason\":\"nested-event-loop\""
                                : "")
                        + okOrError(
                                !failed,
                                "ACTION_FAILED",
                                status.errorType() + ": " + status.errorMessage())
                        + (yielding && !hasKey(request, "returnState")
                                ? ",\"state\":" + snapshotSummary()
                                : ""),
                request);
    }

    private static String versionResponse(final String id) {
        final var version = FxDriverAgent.class.getPackage().getImplementationVersion();
        final var fields = new LinkedHashMap<String, Object>(ARTIFACT.fields());
        fields.put("name", "fxdriver");
        fields.put("version", version == null ? "0.1.0-SNAPSHOT" : version);
        fields.put("protocolVersion", 1);
        fields.put("runtime", runtimeInfo());
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + Json.stringify(fields) + "}";
    }

    private static Map<String, Object> runtimeInfo() {
        final var fields = new LinkedHashMap<String, Object>();
        fields.put("javaVersion", System.getProperty("java.runtime.version", ""));
        fields.put("javaHome", System.getProperty("java.home", ""));
        fields.put("javaExecutable", ProcessHandle.current().info().command().orElse(""));
        final var descriptor = Node.class.getModule().getDescriptor();
        fields.put(
                "javafxVersion",
                descriptor == null
                        ? System.getProperty("javafx.runtime.version", "unknown")
                        : descriptor.rawVersion().orElse("unknown"));
        fields.put("glassPlatform", System.getProperty("glass.platform", "default"));
        fields.put("prismOrder", System.getProperty("prism.order", "default"));
        fields.put("awtHeadless", System.getProperty("java.awt.headless", "default"));
        return fields;
    }

    private static String capabilitiesResponse(final String id) {
        final var fields = new LinkedHashMap<String, Object>(ARTIFACT.fields());
        fields.put("workingDirectory", Path.of("").toAbsolutePath().normalize().toString());
        fields.put("runtime", runtimeInfo());
        fields.put("methods", List.of(METHODS.split(", ")));
        fields.put(
                "selectors",
                List.of(
                        "@handle",
                        "eid=n",
                        "#node-id",
                        ".style-class",
                        "=exact text",
                        "text=contains",
                        "regexText=...",
                        "type=Button",
                        "role=BUTTON",
                        "accessible=label",
                        "window=w"));
        fields.put(
                "features",
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
                        "webExecuteScript",
                        "editor-commit",
                        "accessibility-candidates",
                        "weak-handles",
                        "modal-actions",
                        "guarded-targets",
                        "model-pagination",
                        "event-redaction",
                        "sensitive-pixels",
                        "property-predicates",
                        "observable-waits",
                        "layout-pulse",
                        "artifact-provenance",
                        "request-validation"));
        fields.put("requestIdRequired", true);
        fields.put("namedParametersOnly", true);
        fields.put("notifications", false);
        fields.put(
                "limits",
                Map.of(
                        "requestBytes",
                        MAX_REQUEST_BYTES,
                        "batchRequests",
                        1000,
                        "runSteps",
                        1000,
                        "runDepth",
                        16,
                        "jsonDepth",
                        128,
                        "eventHistory",
                        500));
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + Json.stringify(fields) + "}";
    }

    private static String configureResponse(final String id, final String request) {
        if (hasKey(request, "visualTrace")) {
            visualTrace = extractBoolean(request, "visualTrace", visualTrace);
        }
        if (hasKey(request, "highlightMs")) {
            visualTraceMs = Math.max(0, extractInt(request, "highlightMs", visualTraceMs));
        }
        final var effect = extractString(request, "effect");
        if (!effect.isBlank()) {
            visualTraceEffect = effect;
        }
        return "{\"jsonrpc\":\"2.0\",\"id\":"
                + id
                + ",\"result\":{"
                + visualTraceConfigJson()
                + "}}";
    }

    private static String visualTraceConfigJson() {
        return "\"visualTrace\":"
                + visualTrace
                + ",\"highlightMs\":"
                + visualTraceMs
                + ",\"effect\":\""
                + jsonEscape(visualTraceEffect)
                + "\"";
    }

    private static String runResponse(final String id, final String request) throws Exception {
        final var depth = RUN_DEPTH.orElse(0);
        if (depth >= 16)
            return errorResponse(id, -32602, "RUN_NESTING_LIMIT", "run nesting exceeds 16 levels");
        return ScopedValue.where(RUN_DEPTH, depth + 1).call(() -> runStepsResponse(id, request));
    }

    private static String runStepsResponse(final String id, final String request) throws Exception {
        final var steps = objectArray(request, "steps");
        final var continueOnError = extractBoolean(request, "continueOnError", false);
        final var methods =
                steps.stream()
                        .map(
                                step -> {
                                    final var op = extractString(step, "op");
                                    return op.isBlank() ? extractString(step, "method") : op;
                                })
                        .toList();
        final var results = new ArrayList<RpcReply>();
        var reconciled = true;
        for (var i = 0; i < steps.size(); i++) {
            if (!(reconciled = reconcileActions(results))) {
                break;
            }
            if (!continueOnError && results.stream().anyMatch(RpcReply::failed)) {
                break;
            }
            final var step = steps.get(i);
            final var method = methods.get(i);
            results.add(
                    RpcReply.parse(
                            method.isBlank()
                                    ? errorResponse(
                                            String.valueOf(i + 1),
                                            -32600,
                                            "MISSING_OP",
                                            "run step requires op or method")
                                    : dispatch(String.valueOf(i + 1), method, step)));
        }
        if (reconciled) {
            reconciled = reconcileActions(results);
        }
        var failedStep = 0;
        final var pending = new ArrayList<Long>();
        for (var i = 0; i < results.size(); i++) {
            final var reply = results.get(i);
            if (failedStep == 0 && reply.failed()) {
                failedStep = i + 1;
            }
            if (unsettled(reply) && reply.actionId() instanceof Number number) {
                pending.add(number.longValue());
            }
        }
        final var fields = new LinkedHashMap<String, Object>();
        fields.put("ok", failedStep == 0 && reconciled);
        fields.put(
                "completion",
                !reconciled
                        ? "unknown"
                        : failedStep > 0 ? "failed" : pending.isEmpty() ? "completed" : "pending");
        fields.put("count", steps.size());
        fields.put("executed", results.size());
        fields.put("failedStep", failedStep == 0 ? null : failedStep);
        fields.put("pendingActions", pending);
        fields.put("steps", results.stream().map(RpcReply::toObject).toList());
        if (!reconciled) {
            fields.put(
                    "error",
                    Map.of(
                            "code",
                            "RECONCILIATION_TIMEOUT",
                            "message",
                            "A pending action could not be reconciled; no later step was"
                                    + " dispatched"));
        }
        final var json = Json.stringify(fields);
        return resultResponse(id, json.substring(1, json.length() - 1), reconciled ? request : "");
    }

    private static boolean unsettled(final RpcReply reply) {
        return reply.completion() != null
                && Set.of("queued", "preparing", "running", "pending", "cancelling", "unknown")
                        .contains(reply.completion());
    }

    private static boolean reconcileActions(final List<RpcReply> results) throws Exception {
        if (results.stream().noneMatch(FxDriverAgent::unsettled)) {
            return true;
        }
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            final var remaining =
                    Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            final boolean unwinding;
            try {
                unwinding =
                        onFxThread(
                                () -> {
                                    var waiting = false;
                                    for (var i = 0; i < results.size(); i++) {
                                        final var reply = results.get(i);
                                        if (!unsettled(reply)) {
                                            continue;
                                        }
                                        final var actionId =
                                                reply.actionId() instanceof Number number
                                                        ? number.longValue()
                                                        : -1L;
                                        final var status = FxActions.status(actionId);
                                        if (status == null) {
                                            results.set(
                                                    i,
                                                    RpcReply.parse(
                                                            resultResponse(
                                                                    Integer.toString(i + 1),
                                                                    "\"actionId\":"
                                                                            + actionId
                                                                            + okOrError(
                                                                                    false,
                                                                                    "UNKNOWN_ACTION",
                                                                                    "Pending action"
                                                                                        + " is no"
                                                                                        + " longer"
                                                                                        + " tracked"),
                                                                    "")));
                                        } else if (List.of("completed", "failed", "cancelled")
                                                .contains(status.completion())) {
                                            results.set(
                                                    i,
                                                    RpcReply.parse(
                                                            actionStatusReply(
                                                                    Integer.toString(i + 1),
                                                                    actionId,
                                                                    "")));
                                        } else {
                                            waiting |=
                                                    !"pending".equals(status.completion())
                                                            || FxActions.awaitingUnwind(actionId);
                                        }
                                    }
                                    return waiting;
                                },
                                remaining);
            } catch (final TimeoutException stalled) {
                return false;
            }
            if (!unwinding) {
                return true;
            }
        } while (System.nanoTime() < deadline);
        return false;
    }

    private static String eventsResponse(final String id) {
        final List<DriverEvent> copy;
        final long sequence;
        synchronized (events) {
            copy = List.copyOf(events);
            sequence = eventSequence;
        }
        return "{\"jsonrpc\":\"2.0\",\"id\":"
                + id
                + ",\"result\":{\"events\":["
                + String.join(",", copy.stream().map(DriverEvent::json).toList())
                + "],\"nextCursor\":"
                + sequence
                + "}}";
    }

    private static String clearEventsResponse(final String id) {
        final int cleared;
        synchronized (events) {
            cleared = events.size();
            events.clear();
        }
        return "{\"jsonrpc\":\"2.0\",\"id\":"
                + id
                + ",\"result\":{\"ok\":true,\"cleared\":"
                + cleared
                + "}}";
    }

    private static String eventsSinceResponse(final String id, final String request) {
        final List<DriverEvent> copy;
        final long sequence;
        synchronized (events) {
            copy = List.copyOf(events);
            sequence = eventSequence;
        }
        final var cursor = Math.max(0, extractLong(request, "cursor", 0));
        final var since = Math.max(0, extractLong(request, "since", 0));
        final var byTime = hasKey(request, "since");
        final var selected =
                copy.stream()
                        .filter(
                                event ->
                                        byTime
                                                ? event.timestamp() >= since
                                                : event.sequence() > cursor)
                        .toList();
        final var first = copy.isEmpty() ? sequence + 1 : copy.getFirst().sequence();
        final var overflow =
                byTime
                        ? !copy.isEmpty()
                                && sequence > copy.size()
                                && since < copy.getFirst().timestamp()
                        : cursor < first - 1;
        return "{\"jsonrpc\":\"2.0\",\"id\":"
                + id
                + ",\"result\":{\"cursor\":"
                + cursor
                + ",\"nextCursor\":"
                + sequence
                + ",\"overflow\":"
                + overflow
                + ",\"historyTruncated\":"
                + (sequence > copy.size())
                + ",\"events\":["
                + String.join(",", selected.stream().map(DriverEvent::json).toList())
                + "]}}";
    }

    private static String highlightResponse(final String id, final String request)
            throws Exception {
        final var query = highlightQuery(request);
        final var effect = highlightEffect(request);
        final var matches = highlight(query, effect);
        return "{\"jsonrpc\":\"2.0\",\"id\":"
                + id
                + ",\"result\":{\"highlighted\":"
                + matches.size()
                + ",\"query\":\""
                + jsonEscape(query)
                + "\",\"effect\":\""
                + jsonEscape(effect)
                + "\",\"matches\":["
                + jsonStrings(matches)
                + "]}}";
    }

    private static String snapshotResponse(final String id, final String request) throws Exception {
        final var includeTypes = extractStringArray(request, "includeTypes");
        final var excludeTypes = extractStringArray(request, "excludeTypes");
        return "{\"jsonrpc\":\"2.0\",\"id\":"
                + id
                + ",\"result\":"
                + snapshot(includeTypes, excludeTypes)
                + "}";
    }

    private static String snapshotSummaryResponse(final String id) throws Exception {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + snapshotSummary() + "}";
    }

    private static String snapshotSummary() throws Exception {
        final var result = new CompletableFuture<String>();
        Platform.runLater(
                () -> {
                    try {
                        result.complete(snapshotSummaryOnFxThread());
                    } catch (final Throwable throwable) {
                        result.completeExceptionally(throwable);
                    }
                });
        return result.get(5, TimeUnit.SECONDS);
    }

    private static String snapshotSummaryOnFxThread() {
        final var windows = new ArrayList<String>();
        final var buttons = new ArrayList<String>();
        final var textFields = new ArrayList<String>();
        final var controls = new ArrayList<String>();
        final var menus = new ArrayList<String>();
        final var tables = new ArrayList<String>();
        final var selectedTabs = new ArrayList<String>();
        final var visibleText = new java.util.LinkedHashSet<String>();
        var focusedNode = "null";
        for (final var window : orderedWindows()) {
            if (!window.isShowing() || window.getScene() == null) {
                continue;
            }
            final var owner = window.getScene().getFocusOwner();
            if (owner == null || !effectivelyVisible(owner)) {
                continue;
            }
            if (window.isFocused()) {
                focusedNode = summaryNodeJson(window, owner);
                break;
            }
            if ("null".equals(focusedNode)) {
                focusedNode = summaryNodeJson(window, owner);
            }
        }
        for (final var window : orderedWindows()) {
            if (!window.isShowing() || window.getScene() == null) {
                continue;
            }
            addCapped(windows, orientationWindowJson(window), 8);
            for (final var node : stateFlatten(window.getScene().getRoot())) {
                if (!effectivelyVisible(node)) {
                    continue;
                }
                final var text = truncate(textOf(node), 160);
                if (!text.isBlank() && visibleText.size() < 40) {
                    visibleText.add(text);
                }
                if (isSummaryValueControl(node)) {
                    addCapped(controls, summaryValueControlJson(window, node), 24);
                }
                final var items = menuItems(node);
                if (!items.isEmpty()) {
                    addCapped(
                            menus,
                            summaryNodeOpen(window, node)
                                    + ",\"items\":["
                                    + jsonStrings(menuItemPaths(items))
                                    + "]}",
                            16);
                }
                if (node instanceof ButtonBase button) {
                    addCapped(
                            buttons,
                            summaryNodeOpen(window, button)
                                    + ",\"disabled\":"
                                    + button.isDisabled()
                                    + "}",
                            24);
                } else if (node instanceof TextInputControl input) {
                    final var value = isSensitive(input) ? "[redacted]" : input.getText();
                    addCapped(
                            textFields,
                            summaryNodeOpen(window, input)
                                    + ",\"value\":\""
                                    + jsonEscape(truncate(value, 160))
                                    + "\",\"prompt\":\""
                                    + jsonEscape(truncate(input.getPromptText(), 160))
                                    + "\",\"editable\":"
                                    + input.isEditable()
                                    + "}",
                            16);
                } else if (node instanceof TableView<?> table) {
                    addCapped(
                            tables,
                            summaryNodeOpen(window, table)
                                    + ",\"kind\":\"table\",\"rows\":"
                                    + table.getItems().size()
                                    + ",\"columns\":"
                                    + summaryColumnsJson(table.getColumns())
                                    + "}",
                            12);
                } else if (node instanceof TreeTableView<?> table) {
                    addCapped(
                            tables,
                            summaryNodeOpen(window, table)
                                    + ",\"kind\":\"treeTable\",\"rows\":"
                                    + table.getExpandedItemCount()
                                    + ",\"columns\":"
                                    + summaryColumnsJson(table.getColumns())
                                    + "}",
                            12);
                } else if (node instanceof TabPane tabs
                        && tabs.getSelectionModel().getSelectedItem() != null) {
                    addCapped(
                            selectedTabs,
                            summaryNodeOpen(window, tabs)
                                    + ",\"selected\":\""
                                    + jsonEscape(
                                            truncate(
                                                    tabs.getSelectionModel()
                                                            .getSelectedItem()
                                                            .getText(),
                                                    160))
                                    + "\"}",
                            12);
                }
            }
        }
        return "{\"windows\":["
                + String.join(",", windows)
                + "],\"buttons\":["
                + String.join(",", buttons)
                + "],\"textFields\":["
                + String.join(",", textFields)
                + "],\"controls\":["
                + String.join(",", controls)
                + "],\"menus\":["
                + String.join(",", menus)
                + "],\"tables\":["
                + String.join(",", tables)
                + "],\"selectedTabs\":["
                + String.join(",", selectedTabs)
                + "],\"focusedNode\":"
                + focusedNode
                + ",\"visibleTextSample\":["
                + jsonStrings(new ArrayList<>(visibleText))
                + "]}";
    }

    private static boolean isSummaryValueControl(final Node node) {
        return node instanceof CheckBox
                || node instanceof Toggle
                || node instanceof ChoiceBox<?>
                || node instanceof ComboBoxBase<?>
                || node instanceof Spinner<?>
                || node instanceof Slider;
    }

    private static String summaryValueControlJson(final Window window, final Node node) {
        final var fields = valueControlFields(node);
        return summaryNodeOpen(window, node)
                + ",\"kind\":\""
                + semanticKind(node)
                + "\""
                + (fields.isEmpty() ? "" : "," + String.join(",", fields))
                + "}";
    }

    private static List<String> modelValueFields(final Node node) {
        final var fields = new ArrayList<String>();
        final Object value;
        if (node instanceof ComboBoxBase<?> combo) value = combo.getValue();
        else if (node instanceof ChoiceBox<?> choice) value = choice.getValue();
        else if (node instanceof Spinner<?> spinner) value = spinner.getValue();
        else return fields;
        final var model = String.valueOf(value);
        final var display = FxControls.displayValue(node);
        addString(fields, "value", truncate(model, 512));
        addString(fields, "displayValue", truncate(display, 512));
        addString(fields, "valueRepresentation", "model-string");
        addString(fields, "displayValueSource", "converter-or-default-model-text");
        fields.add("\"valueIsNull\":" + (value == null));
        fields.add("\"valueTruncated\":" + (model.length() > 512));
        fields.add("\"displayValueTruncated\":" + (display != null && display.length() > 512));
        TextInputControl editor = null;
        if (node instanceof ComboBox<?> combo && combo.isEditable()) editor = combo.getEditor();
        if (node instanceof DatePicker date) editor = date.getEditor();
        if (node instanceof Spinner<?> spinner) editor = spinner.getEditor();
        if (editor != null) {
            addString(fields, "editorText", truncate(editor.getText(), 512));
            fields.add("\"editorTextTruncated\":" + (editor.getLength() > 512));
        }
        return fields;
    }

    private static List<String> valueControlFields(final Node node) {
        final var fields = new ArrayList<String>();
        if (isSensitive(node) && !(node instanceof TextInputControl))
            return List.of("\"masked\":true");
        if (node instanceof CheckBox check) {
            fields.add("\"selected\":" + check.isSelected());
            fields.add("\"indeterminate\":" + check.isIndeterminate());
        }
        if (node instanceof Toggle toggle) {
            fields.add("\"selected\":" + toggle.isSelected());
        }
        if (node instanceof ChoiceBox<?> choice) {
            fields.addAll(modelValueFields(choice));
            fields.add("\"selectedIndex\":" + selectedIndex(node));
        } else if (node instanceof ComboBoxBase<?> combo) {
            fields.addAll(modelValueFields(combo));
            fields.add("\"showing\":" + combo.isShowing());
        } else if (node instanceof Spinner<?> spinner) {
            fields.addAll(modelValueFields(spinner));
        } else if (node instanceof Slider slider) {
            fields.add("\"value\":" + jsonNumber(slider.getValue()));
        }
        if (node instanceof ScrollBar bar) {
            fields.add("\"value\":" + jsonNumber(bar.getValue()));
        }
        if (node instanceof TabPane tabs) {
            fields.add("\"selectedIndex\":" + selectedIndex(node));
            final var selected = (javafx.scene.control.Tab) selectedItem(node);
            addString(fields, "selectedTab", selected == null ? "" : tabName(selected));
        }
        return fields;
    }

    private static List<String> menuItemPaths(final List<MenuItem> items) {
        final var paths = new ArrayList<String>();
        addMenuItemPaths(paths, items, "");
        return paths;
    }

    private static void addMenuItemPaths(
            final List<String> paths, final List<MenuItem> items, final String prefix) {
        for (final var item : items) {
            if (paths.size() >= 24) {
                return;
            }
            final var text = item.getText() == null ? "" : item.getText();
            final var path = prefix + text;
            if (item instanceof Menu menu && !menu.getItems().isEmpty()) {
                addMenuItemPaths(paths, menu.getItems(), path + " > ");
            } else {
                paths.add(path);
            }
        }
    }

    private static String orientationWindowJson(final Window window) {
        final var title = window instanceof Stage stage ? stage.getTitle() : "";
        return "{\"wid\":\"w"
                + windowHandle(window)
                + "\",\"title\":\""
                + jsonEscape(truncate(title, 160))
                + "\",\"focused\":"
                + window.isFocused()
                + windowContextJson(window)
                + ",\"bounds\":{"
                + boundsJson(
                        Math.round(window.getX()),
                        Math.round(window.getY()),
                        Math.round(window.getWidth()),
                        Math.round(window.getHeight()))
                + "}}";
    }

    private static String summaryNodeOpen(final Window window, final Node node) {
        return "{\"eid\":\"n"
                + handle(node)
                + "\",\"window\":\"w"
                + windowHandle(window)
                + "\",\"type\":\""
                + jsonEscape(node.getClass().getSimpleName())
                + "\",\"id\":\""
                + jsonEscape(truncate(node.getId(), 160))
                + "\",\"name\":\""
                + jsonEscape(accessibleNameOf(node))
                + "\",\"text\":\""
                + jsonEscape(truncate(textOf(node), 160))
                + "\"";
    }

    private static String summaryNodeJson(final Window window, final Node node) {
        return summaryNodeOpen(window, node) + "}";
    }

    private static String summaryColumnsJson(final List<? extends TableColumnBase<?, ?>> columns) {
        final var values = new ArrayList<String>();
        for (var i = 0; i < columns.size() && i < 16; i++) {
            final var column = columns.get(i);
            values.add(
                    "{\"index\":"
                            + i
                            + ",\"id\":\""
                            + jsonEscape(truncate(column.getId(), 160))
                            + "\",\"text\":\""
                            + jsonEscape(truncate(column.getText(), 160))
                            + "\"}");
        }
        return "[" + String.join(",", values) + "]";
    }

    private static void addCapped(final List<String> values, final String value, final int limit) {
        if (values.size() < limit) {
            values.add(value);
        }
    }

    private static String truncate(final String value, final int limit) {
        if (value == null || value.length() <= limit) {
            return value == null ? "" : value;
        }
        return value.substring(0, limit - 1) + "…";
    }

    private static String queryResponse(final String id, final String request) throws Exception {
        final var query = highlightQueryOrDefault(request, "");
        final var mode = stateMode(request, "compact");
        final var limit = Math.clamp(extractInt(request, "limit", 120), 1, 500);
        return onFxThread(
                () -> {
                    final List<Node> matches;
                    if (query.isBlank()) {
                        matches = new ArrayList<>();
                        for (final var window : orderedWindows()) {
                            if (!window.isShowing() || window.getScene() == null) continue;
                            for (final var node : stateFlatten(window.getScene().getRoot())) {
                                if (stateNode(node, mode)) matches.add(node);
                            }
                        }
                    } else {
                        matches = rawMatchesFor(query, Boolean.TRUE);
                    }
                    final var nodes =
                            matches.stream()
                                    .limit(limit)
                                    .map(
                                            node ->
                                                    "full".equals(mode)
                                                            ? nodeJson(windowOf(node), node)
                                                            : compactNodeJson(windowOf(node), node))
                                    .toList();
                    return resultResponse(
                            id,
                            "\"query\":\""
                                    + jsonEscape(query)
                                    + "\",\"mode\":\""
                                    + jsonEscape(mode)
                                    + "\",\"count\":"
                                    + matches.size()
                                    + ",\"truncated\":"
                                    + (matches.size() > limit)
                                    + ",\"nodes\":["
                                    + String.join(",", nodes)
                                    + "]",
                            "");
                });
    }

    private static String pressResponse(final String id, final String request) throws Exception {
        var key = extractString(request, "key");
        if (key.isBlank()) key = "F1";
        final var fired = press(key, extractString(request, "window"));
        return resultResponse(
                id,
                "\"key\":\""
                        + jsonEscape(key)
                        + "\",\"acceleratorsFired\":"
                        + fired
                        + ",\"mechanism\":\"scene-accelerator\",\"outcomeVerified\":false"
                        + okOrError(
                                fired > 0,
                                "NO_ACCELERATOR",
                                "No matching accelerator in the selected context"),
                request);
    }

    private record ParsedKey(
            KeyCode code, boolean shift, boolean control, boolean alt, boolean meta) {}

    private record KeyboardDispatch(
            int sent, String target, boolean sensitive, String focusStrategy) {}

    private static String keyResponse(final String id, final String request) throws Exception {
        final var chars = extractString(request, "chars");
        final var key = extractString(request, "key");
        final var hasChars = hasKey(request, "chars");
        final var sensitive = extractBoolean(request, "sensitive", false);
        final var result =
                FxActions.call(
                        () -> {
                            final var target = keyTarget(request);
                            if (target == null)
                                return new KeyboardDispatch(0, "null", sensitive, "none");
                            markSensitive(target, sensitive);
                            final var strategy =
                                    hasTargetSelector(request)
                                            ? "selector"
                                            : !extractString(request, "window").isBlank()
                                                    ? "explicit-window"
                                                    : windowOf(target).isFocused()
                                                            ? "focused-window"
                                                            : "top-context-fallback";
                            final var sent = dispatchKeyboard(target, key, chars, hasChars);
                            return new KeyboardDispatch(
                                    sent, targetJson(target), isSensitive(target), strategy);
                        });
        return resultResponse(
                id,
                "\"key\":\""
                        + jsonEscape(result.sensitive() ? "[redacted]" : key)
                        + "\",\"chars\":\""
                        + (hasChars ? "[redacted]" : "")
                        + "\",\"sent\":"
                        + result.sent()
                        + ",\"target\":"
                        + result.target()
                        + ",\"sensitive\":"
                        + result.sensitive()
                        + ",\"focusStrategy\":\""
                        + result.focusStrategy()
                        + "\",\"mechanism\":\"synthetic-key-event\",\"outcomeVerified\":false"
                        + okOrError(
                                result.sent() > 0,
                                "null".equals(result.target()) ? "NO_FOCUS" : "NO_KEY",
                                "null".equals(result.target())
                                        ? "No keyboard target was focused or matched"
                                        : "key requires key or chars"),
                request);
    }

    private static String focusResponse(final String id, final String request) throws Exception {
        final var query = highlightQuery(request);
        final var result =
                withFirstMatch(
                        query,
                        traceMs(request),
                        highlightEffect(request),
                        node -> {
                            markSensitive(node, extractBoolean(request, "sensitive", false));
                            windowOf(node).requestFocus();
                            node.requestFocus();
                            return node;
                        });
        return actionResponse(id, request, query, "focusRequested", result);
    }

    private static Node keyTarget(final String request) {
        if (hasTargetSelector(request)) {
            final var query = highlightQuery(request);
            return requireUniqueTarget(query, matchesFor(query));
        }
        final var requested = extractString(request, "window");
        if (!requested.isBlank()) {
            final var window = screenshotWindow(requested);
            if (modalBlocker(window) != null) {
                throw new TargetFailure(
                        "BLOCKED",
                        "window=" + requested,
                        window.getScene() == null
                                ? List.of()
                                : List.of(window.getScene().getRoot()));
            }
            return window.getScene() == null ? null : eligibleFocusOwner(window);
        }
        final var windows = orderedWindows();
        for (final var focusedOnly : List.of(true, false)) {
            for (final var window : windows) {
                if (focusedOnly && !window.isFocused()) continue;
                final var owner = eligibleFocusOwner(window);
                if (owner != null) return owner;
            }
        }
        return null;
    }

    private static Node eligibleFocusOwner(final Window window) {
        if (!window.isShowing() || window.getScene() == null || modalBlocker(window) != null)
            return null;
        final var owner = window.getScene().getFocusOwner();
        return owner != null && effectivelyVisible(owner) && !owner.isDisabled() ? owner : null;
    }

    private static boolean hasTargetSelector(final String request) {
        return hasKey(request, "handle")
                || hasKey(request, "eid")
                || hasKey(request, "nodeId")
                || hasKey(request, "cssId")
                || hasKey(request, "styleClass")
                || hasKey(request, "textExact")
                || hasKey(request, "text")
                || hasKey(request, "regexText")
                || hasKey(request, "accessible")
                || hasKey(request, "role")
                || hasKey(request, "selector")
                || hasKey(request, "type");
    }

    private static int dispatchKeyboard(
            final Node target, final String key, final String chars, final boolean hasChars) {
        if (!effectivelyVisible(target)) {
            return 0;
        }
        requireUniqueTarget("eid=n" + handle(target), List.of(target));
        target.requestFocus();
        if (hasChars) {
            var sent = 0;
            for (final var codePoint : chars.codePoints().toArray()) {
                final var character = new String(Character.toChars(codePoint));
                target.fireEvent(
                        new KeyEvent(
                                KeyEvent.KEY_TYPED,
                                character,
                                character,
                                KeyCode.UNDEFINED,
                                false,
                                false,
                                false,
                                false));
                sent++;
            }
            return sent;
        }
        if (key.isBlank()) {
            return 0;
        }
        final var parsed = parseKey(key);
        target.fireEvent(
                new KeyEvent(
                        KeyEvent.KEY_PRESSED,
                        "",
                        "",
                        parsed.code(),
                        parsed.shift(),
                        parsed.control(),
                        parsed.alt(),
                        parsed.meta()));
        target.fireEvent(
                new KeyEvent(
                        KeyEvent.KEY_RELEASED,
                        "",
                        "",
                        parsed.code(),
                        parsed.shift(),
                        parsed.control(),
                        parsed.alt(),
                        parsed.meta()));
        return 2;
    }

    private static ParsedKey parseKey(final String value) {
        var shift = false;
        var control = false;
        var alt = false;
        var meta = false;
        var code = "";
        for (final var raw : value.toUpperCase(java.util.Locale.ROOT).split("[+]")) {
            final var part = raw.strip();
            switch (part) {
                case "SHIFT" -> shift = true;
                case "CTRL", "CONTROL" -> control = true;
                case "ALT" -> alt = true;
                case "META", "CMD", "COMMAND" -> meta = true;
                default -> code = "ESC".equals(part) ? "ESCAPE" : part.replace('-', '_');
            }
        }
        if (code.isBlank()) {
            throw new IllegalArgumentException("key requires a named key after modifiers");
        }
        return new ParsedKey(KeyCode.valueOf(code), shift, control, alt, meta);
    }

    private static <T> T onFxThread(final java.util.concurrent.Callable<T> action)
            throws Exception {
        return onFxThread(action, 5000);
    }

    private static <T> T onFxThread(
            final java.util.concurrent.Callable<T> action, final long timeoutMillis)
            throws Exception {
        final var result = new CompletableFuture<T>();
        Platform.runLater(
                () -> {
                    if (result.isDone()) return;
                    try {
                        result.complete(action.call());
                    } catch (final Throwable throwable) {
                        result.completeExceptionally(throwable);
                    }
                });
        try {
            return result.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (final TimeoutException timeout) {
            result.cancel(false);
            throw timeout;
        } catch (final java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof Exception exception) {
                throw exception;
            }
            if (failure.getCause() instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(failure.getCause());
        }
    }

    private static String fireResponse(final String id, final String request) throws Exception {
        final var query = highlightQuery(request);
        final var fired = fire(query, traceMs(request), highlightEffect(request));
        return actionResponse(id, request, query, "fired", fired);
    }

    private record MenuFireResult(
            String kind, String value, int fired, String code, boolean sensitive, String target) {}

    private static String fireMenuItemResponse(final String id, final String request)
            throws Exception {
        final var query = highlightQuery(request);
        final var path = extractStringArray(request, "path");
        final var itemText = extractString(request, "itemText");
        final var result = FxActions.call(() -> fireMenuItem(query, path, itemText));
        final var ok = result.fired() > 0;
        return resultResponse(
                id,
                "\"query\":\""
                        + jsonEscape(query)
                        + "\",\"kind\":\""
                        + jsonEscape(result.kind())
                        + "\",\"path\":["
                        + jsonStrings(result.sensitive() ? List.of("[redacted]") : path)
                        + "],\"value\":\""
                        + jsonEscape(result.value())
                        + "\",\"fired\":"
                        + result.fired()
                        + ",\"sensitive\":"
                        + result.sensitive()
                        + ",\"target\":"
                        + result.target()
                        + ",\"mechanism\":\"menu-model\",\"outcomeVerified\":false"
                        + okOrError(ok, result.code(), "Menu item could not be resolved or fired"),
                request);
    }

    private static MenuFireResult fireMenuItem(
            final String query, final List<String> path, final String itemText) {
        final var matches = matchesFor(query);
        if (matches.isEmpty()) return menuResult(null, null, 0, "NO_MENU_ITEM");
        final var target = requireUniqueTarget(query, matches);
        markSensitive(target, false);
        if (path.isEmpty() && itemText.isBlank() || path.stream().anyMatch(String::isBlank)) {
            return menuResult(target, null, 0, "NO_MENU_ITEM");
        }
        final var items = menuItems(target);
        final List<MenuItem> found;
        if (!path.isEmpty()) found = menuPath(items, path, 0);
        else {
            final var exact = menuText(items, itemText, true);
            found = exact.isEmpty() ? menuText(items, itemText, false) : exact;
        }
        if (found.size() != 1)
            return menuResult(
                    target, null, 0, found.isEmpty() ? "NO_MENU_ITEM" : "AMBIGUOUS_MENU_ITEM");
        final var item = found.getFirst();
        for (MenuItem current = item; current != null; current = current.getParentMenu()) {
            if (current.isDisable()) return menuResult(target, item, 0, "DISABLED");
            if (!current.isVisible()) return menuResult(target, item, 0, "NOT_VISIBLE");
        }
        item.fire();
        return menuResult(target, item, 1, "");
    }

    private static MenuFireResult menuResult(
            final Node target, final MenuItem item, final int fired, final String code) {
        final var sensitive = target != null && isSensitive(target);
        return new MenuFireResult(
                target == null ? "" : target.getClass().getSimpleName(),
                item == null ? "" : sensitive ? "[redacted]" : item.getText(),
                fired,
                code,
                sensitive,
                target == null ? "null" : targetJson(target));
    }

    private static List<MenuItem> menuItems(final Node node) {
        if (node instanceof MenuBar menuBar) {
            return List.copyOf(menuBar.getMenus());
        }
        if (node instanceof MenuButton menuButton) {
            return List.copyOf(menuButton.getItems());
        }
        if (node instanceof Control control && control.getContextMenu() != null) {
            return List.copyOf(control.getContextMenu().getItems());
        }
        return List.of();
    }

    private static List<MenuItem> menuPath(
            final List<MenuItem> items, final List<String> path, final int index) {
        final var found = new ArrayList<MenuItem>();
        if (index >= path.size()) return found;
        for (final var item : items) {
            if (!path.get(index).equals(item.getText())) continue;
            if (index == path.size() - 1) found.add(item);
            else if (item instanceof Menu menu)
                found.addAll(menuPath(menu.getItems(), path, index + 1));
        }
        return found;
    }

    private static List<MenuItem> menuText(
            final List<MenuItem> items, final String text, final boolean exact) {
        final var found = new ArrayList<MenuItem>();
        for (final var item : items) {
            final var value = item.getText() == null ? "" : item.getText();
            if (exact ? text.equals(value) : value.contains(text)) found.add(item);
            if (item instanceof Menu menu) found.addAll(menuText(menu.getItems(), text, exact));
        }
        return found;
    }

    private record TableCellResult(
            boolean ok,
            String code,
            int rowIndex,
            int columnIndex,
            String column,
            String rowText,
            String value) {
        static TableCellResult found(
                final int row,
                final int columnIndex,
                final String column,
                final String rowText,
                final String value) {
            return new TableCellResult(true, "", row, columnIndex, column, rowText, value);
        }

        static TableCellResult failure(final String code, final int row) {
            return new TableCellResult(false, code, row, -1, "", "", "");
        }

        String json() {
            return "\"rowIndex\":"
                    + rowIndex
                    + ",\"columnIndex\":"
                    + columnIndex
                    + ",\"column\":\""
                    + jsonEscape(column)
                    + "\",\"rowText\":\""
                    + jsonEscape(truncate(rowText, 512))
                    + "\",\"value\":\""
                    + jsonEscape(truncate(value, 512))
                    + "\",\"representation\":\"model-string\",\"presentationVerified\":false"
                    + ",\"valueTruncated\":"
                    + (value.length() > 512)
                    + ",\"rowTextTruncated\":"
                    + (rowText.length() > 512)
                    + ",\"ok\":"
                    + ok
                    + (ok
                            ? ""
                            : ",\"error\":{\"code\":\""
                                    + code
                                    + "\",\"message\":\"Table cell could not be resolved\"}");
        }
    }

    private static String tableCellResponse(final String id, final String request)
            throws Exception {
        final var query = highlightQuery(request);
        final var rowText = extractString(request, "tableText");
        final var rowIndex = extractInt(request, "rowIndex", -1);
        final var column = extractString(request, "column");
        final var columnIndex = extractInt(request, "columnIndex", -1);
        final var result =
                onFxThread(
                        () -> {
                            final var matches = matchesFor(query);
                            if (matches.size() != 1)
                                return TableCellResult.failure(
                                        matches.size() > 1 ? "AMBIGUOUS_TABLE" : "NO_TABLE", -1);
                            final var target = matches.getFirst();
                            if (isSensitive(target))
                                return TableCellResult.failure("SENSITIVE", -1);
                            for (final var node : matches) {
                                if (node instanceof TableView<?> table) {
                                    if (rowIndex < 0 && rowText.isBlank()) {
                                        return TableCellResult.failure("NO_ROW", -1);
                                    }
                                    if (columnIndex < 0 && column.isBlank()) {
                                        return TableCellResult.failure("NO_COLUMN", rowIndex);
                                    }
                                    return tableCell(table, rowText, rowIndex, column, columnIndex);
                                }
                                if (node instanceof TreeTableView<?> table) {
                                    if (rowIndex < 0 && rowText.isBlank()) {
                                        return TableCellResult.failure("NO_ROW", -1);
                                    }
                                    if (columnIndex < 0 && column.isBlank()) {
                                        return TableCellResult.failure("NO_COLUMN", rowIndex);
                                    }
                                    return tableCell(table, rowText, rowIndex, column, columnIndex);
                                }
                            }
                            return TableCellResult.failure("NO_TABLE", -1);
                        });
        return resultResponse(
                id, "\"query\":\"" + jsonEscape(query) + "\"," + result.json(), request);
    }

    private static String tableColumnsResponse(final String id, final String request)
            throws Exception {
        final var query = highlightQuery(request);
        final var offset = Math.max(0, extractInt(request, "offset", 0));
        final var limit = Math.clamp(extractInt(request, "limit", 100), 1, 500);
        return onFxThread(
                () -> {
                    final var matches = matchesFor(query);
                    if (matches.size() != 1)
                        return resultResponse(
                                id,
                                "\"columns\":[]"
                                        + okOrError(
                                                false,
                                                matches.size() > 1 ? "AMBIGUOUS_TABLE" : "NO_TABLE",
                                                "Resolve exactly one table"),
                                "");
                    final var node = matches.getFirst();
                    if (isSensitive(node))
                        return resultResponse(
                                id,
                                "\"columns\":[]"
                                        + okOrError(false, "SENSITIVE", "Table data is redacted"),
                                "");
                    final List<? extends TableColumnBase<?, ?>> columns;
                    if (node instanceof TableView<?> table) columns = table.getVisibleLeafColumns();
                    else if (node instanceof TreeTableView<?> table)
                        columns = table.getVisibleLeafColumns();
                    else
                        return resultResponse(
                                id,
                                "\"columns\":[]"
                                        + okOrError(false, "NO_TABLE", "Target is not a table"),
                                "");
                    final var start = Math.min(offset, columns.size());
                    return resultResponse(
                            id,
                            "\"columns\":"
                                    + jsonColumns(columns, start, limit)
                                    + ",\"columnsCount\":"
                                    + columns.size()
                                    + ",\"offset\":"
                                    + start
                                    + ",\"limit\":"
                                    + limit
                                    + ",\"truncated\":"
                                    + ((long) start + limit < columns.size())
                                    + ",\"representation\":\"visible-leaf-columns\",\"ok\":true",
                            "");
                });
    }

    private static TableCellResult tableCell(
            final TableView<?> table,
            final String rowText,
            final int requestedRow,
            final String column,
            final int requestedColumn) {
        final var row =
                resolveRow(
                        table.getItems() == null ? 0 : table.getItems().size(),
                        index -> table.getItems().get(index),
                        rowText,
                        requestedRow);
        final var columnIndex =
                resolveColumn(table.getVisibleLeafColumns(), column, requestedColumn);
        if (row < 0) {
            return TableCellResult.failure(row == -2 ? "AMBIGUOUS_ROW" : "NO_ROW", -1);
        }
        if (columnIndex < 0) {
            return TableCellResult.failure(
                    columnIndex == -2 ? "AMBIGUOUS_COLUMN" : "NO_COLUMN", row);
        }
        final var selected = table.getVisibleLeafColumns().get(columnIndex);
        return TableCellResult.found(
                row,
                columnIndex,
                columnName(selected),
                String.valueOf(table.getItems().get(row)),
                String.valueOf(selected.getCellData(row)));
    }

    private static TableCellResult tableCell(
            final TreeTableView<?> table,
            final String rowText,
            final int requestedRow,
            final String column,
            final int requestedColumn) {
        final var row =
                resolveRow(
                        table.getExpandedItemCount(),
                        index -> table.getTreeItem(index).getValue(),
                        rowText,
                        requestedRow);
        final var columnIndex =
                resolveColumn(table.getVisibleLeafColumns(), column, requestedColumn);
        if (row < 0) {
            return TableCellResult.failure(row == -2 ? "AMBIGUOUS_ROW" : "NO_ROW", -1);
        }
        if (columnIndex < 0) {
            return TableCellResult.failure(
                    columnIndex == -2 ? "AMBIGUOUS_COLUMN" : "NO_COLUMN", row);
        }
        final var selected = table.getVisibleLeafColumns().get(columnIndex);
        return TableCellResult.found(
                row,
                columnIndex,
                columnName(selected),
                String.valueOf(table.getTreeItem(row).getValue()),
                String.valueOf(selected.getCellData(row)));
    }

    private static int resolveRow(
            final int count,
            final java.util.function.IntFunction<?> rowAt,
            final String text,
            final int requested) {
        if (requested >= 0) return requested < count ? requested : -1;
        final var exact =
                uniqueIndex(count, index -> String.valueOf(rowAt.apply(index)).equals(text));
        return exact != -1
                ? exact
                : uniqueIndex(count, index -> String.valueOf(rowAt.apply(index)).contains(text));
    }

    private static int uniqueIndex(final int count, final java.util.function.IntPredicate matches) {
        var found = -1;
        for (var i = 0; i < count; i++) {
            if (!matches.test(i)) continue;
            if (found >= 0) return -2;
            found = i;
        }
        return found;
    }

    private static int resolveColumn(
            final List<? extends TableColumnBase<?, ?>> columns,
            final String value,
            final int requested) {
        if (requested >= 0) return requested < columns.size() ? requested : -1;
        var index = uniqueIndex(columns.size(), i -> value.equals(columns.get(i).getId()));
        if (index != -1) return index;
        index = uniqueIndex(columns.size(), i -> value.equals(columns.get(i).getText()));
        if (index != -1) return index;
        return uniqueIndex(columns.size(), i -> value.equalsIgnoreCase(columns.get(i).getText()));
    }

    private static String columnName(final TableColumnBase<?, ?> column) {
        return column.getId() == null || column.getId().isBlank()
                ? column.getText()
                : column.getId();
    }

    private static String clickResponse(final String id, final String request) throws Exception {
        final var query = highlightQuery(request);
        final var clicked = click(query, traceMs(request), highlightEffect(request));
        final var untilQuietMs = Math.max(0, extractInt(request, "untilQuietMs", 0));
        if (untilQuietMs <= 0 || clicked.count() <= 0) {
            return actionResponse(id, request, query, "clicked", clicked);
        }
        final var timeoutMs = Math.max(untilQuietMs, extractInt(request, "timeoutMs", 10_000));
        final var quiet = waitForQuiet(untilQuietMs, timeoutMs);
        return resultResponse(
                id,
                actionFields(query, "clicked", clicked)
                        + ",\"quiet\":"
                        + quiet.json()
                        + okOrError(
                                quiet.ok(),
                                quiet.ok() ? "NO_MATCH" : "QUIET_TIMEOUT",
                                quiet.ok()
                                        ? "No actionable node matched query"
                                        : "UI did not stay quiet before timeout"),
                request);
    }

    private static String typeResponse(final String id, final String request) throws Exception {
        final var query = inputQuery(request);
        final var value = inputValue(request);
        final var replace = extractBoolean(request, "replace", false);
        final var typed =
                type(
                        query,
                        value,
                        replace,
                        traceMs(request),
                        highlightEffect(request),
                        extractBoolean(request, "sensitive", false));
        return textActionResponse(id, request, query, "typed", typed, value, replace);
    }

    private static String clearResponse(final String id, final String request) throws Exception {
        final var query = inputQuery(request);
        final var cleared = setText(query, "", traceMs(request), highlightEffect(request));
        return actionResponse(id, request, query, "cleared", cleared);
    }

    private static String setTextResponse(final String id, final String request) throws Exception {
        final var query = inputQuery(request);
        final var value = inputValue(request);
        final var changed =
                type(
                        query,
                        value,
                        true,
                        traceMs(request),
                        highlightEffect(request),
                        extractBoolean(request, "sensitive", false));
        return textActionResponse(id, request, query, "changed", changed, value, true);
    }

    private static String setValueResponse(final String id, final String request) throws Exception {
        final var query = highlightQuery(request);
        final var value = inputValue(request);
        final var changed =
                setValue(
                        query,
                        value,
                        traceMs(request),
                        highlightEffect(request),
                        extractBoolean(request, "sensitive", false));
        return textActionResponse(id, request, query, "changed", changed, value, true);
    }

    private static String popupResponse(final String id, final String request, final boolean show)
            throws Exception {
        final var query = highlightQuery(request);
        final var changed = popup(query, show, traceMs(request), highlightEffect(request));
        return actionResponse(id, request, query, show ? "shown" : "hidden", changed);
    }

    private static String incrementResponse(
            final String id, final String request, final boolean increment) throws Exception {
        final var query = highlightQuery(request);
        final var steps = Math.clamp(extractInt(request, "steps", 1), 1, 1000);
        final var changed =
                stepValue(query, steps, increment, traceMs(request), highlightEffect(request));
        return actionResponse(
                id, request, query, increment ? "incremented" : "decremented", changed);
    }

    private static String scrollResponse(final String id, final String request) throws Exception {
        final var query = highlightQuery(request);
        final var amount = extractInt(request, "amount", 6);
        final var scrolled = scroll(query, amount, traceMs(request), highlightEffect(request));
        return resultResponse(
                id,
                "\"query\":\""
                        + jsonEscape(query)
                        + "\",\"amount\":"
                        + amount
                        + ",\"scrolled\":"
                        + scrolled.count()
                        + ",\"target\":"
                        + scrolled.targetJson()
                        + ",\"matches\":"
                        + scrolled.matches()
                        + scrolled.ambiguityJson()
                        + okOrError(
                                scrolled.count() > 0, "NO_MATCH", "No scroll target matched query"),
                request);
    }

    private static String listItemsResponse(final String id, final String request)
            throws Exception {
        final var query = highlightQueryOrDefault(request, "ListView");
        final var offset = Math.max(0, extractInt(request, "offset", 0));
        final var limit = Math.clamp(extractInt(request, "limit", 100), 1, 500);
        return onFxThread(
                () -> {
                    final var matches = matchesFor(query);
                    if (matches.size() != 1 || !(matches.getFirst() instanceof ListView<?> list)) {
                        return resultResponse(
                                id,
                                "\"items\":[]"
                                        + okOrError(
                                                false,
                                                matches.size() > 1 ? "AMBIGUOUS" : "NO_LIST",
                                                "Resolve exactly one list"),
                                "");
                    }
                    final var items = list.getItems();
                    final var end =
                            Math.min(
                                    items.size(),
                                    (int) Math.min(Integer.MAX_VALUE, (long) offset + limit));
                    final var page = new ArrayList<String>();
                    var itemTruncated = false;
                    for (var i = Math.min(offset, items.size()); i < end; i++) {
                        final var value =
                                isSensitive(list) ? "[redacted]" : String.valueOf(items.get(i));
                        itemTruncated |= value.length() > 512;
                        page.add(truncate(value, 512));
                    }
                    return resultResponse(
                            id,
                            "\"query\":\""
                                    + jsonEscape(query)
                                    + "\",\"items\":["
                                    + jsonStrings(page)
                                    + "],\"itemsCount\":"
                                    + items.size()
                                    + ",\"offset\":"
                                    + offset
                                    + ",\"limit\":"
                                    + limit
                                    + ",\"representation\":\"model-string\",\"truncated\":"
                                    + (end < items.size())
                                    + ",\"itemTextTruncated\":"
                                    + itemTruncated
                                    + ",\"ok\":true",
                            "");
                });
    }

    private static String selectListItemResponse(final String id, final String request)
            throws Exception {
        final var query = highlightQuery(request);
        final var text = extractString(request, "itemText");
        final var index = extractInt(request, "index", -1);
        final var activate = extractBoolean(request, "activate", true);
        final var result = selectListItem(query, text, index, activate, selectionExtends(request));
        return resultResponse(
                id,
                "\"query\":\""
                        + jsonEscape(query)
                        + "\",\"itemText\":\""
                        + jsonEscape(text)
                        + "\",\"index\":"
                        + index
                        + ",\"selected\":"
                        + result.selected()
                        + ",\"activate\":"
                        + activate
                        + ",\"activated\":"
                        + result.activated()
                        + ",\"ok\":"
                        + (result.selected() > 0)
                        + (result.selected() > 0
                                ? ""
                                : ",\"error\":{\"code\":\"NO_MATCH\",\"message\":\"No list item"
                                        + " matched query\"}"),
                request);
    }

    private static String selectIndexResponse(final String id, final String request)
            throws Exception {
        final var query = highlightQuery(request);
        final var index = extractInt(request, "index", -1);
        final var changed =
                selectIndex(
                        query,
                        index,
                        traceMs(request),
                        highlightEffect(request),
                        selectionExtends(request));
        return actionResponse(id, request, query, "selected", changed);
    }

    private static String scrollToIndexResponse(final String id, final String request)
            throws Exception {
        final var query = highlightQuery(request);
        final var index = extractInt(request, "index", -1);
        final var changed = scrollToIndex(query, index, traceMs(request), highlightEffect(request));
        return actionResponse(id, request, query, "scrolled", changed);
    }

    private static String expandResponse(
            final String id, final String request, final boolean expand) throws Exception {
        final var query = highlightQuery(request);
        final var index = extractInt(request, "index", -1);
        final var changed =
                expand(query, index, expand, traceMs(request), highlightEffect(request));
        return actionResponse(id, request, query, expand ? "expanded" : "collapsed", changed);
    }

    private static String webExecuteScriptResponse(final String id, final String request)
            throws Exception {
        final var query = highlightQuery(request);
        final var script = extractString(request, "script");
        final var result =
                webExecuteScript(query, script, traceMs(request), highlightEffect(request));
        return resultResponse(
                id,
                "\"query\":\""
                        + jsonEscape(query)
                        + "\",\"value\":\""
                        + jsonEscape(
                                extractBoolean(request, "sensitive", false)
                                        ? "[redacted]"
                                        : result.value())
                        + "\",\"executed\":"
                        + result.count()
                        + ",\"target\":"
                        + result.target()
                        + ",\"valueTruncated\":"
                        + result.truncated()
                        + ",\"representation\":\"js-string\",\"outcomeVerified\":false"
                        + ",\"ok\":"
                        + (result.count() > 0)
                        + (result.count() > 0
                                ? ""
                                : ",\"error\":{\"code\":\"NO_MATCH\",\"message\":\"No WebView"
                                        + " target matched query\"}"),
                request);
    }

    private static String quietResponse(final String id, final String request) throws Exception {
        final var quietMs = Math.clamp(extractInt(request, "untilQuietMs", 200), 1, 10000);
        final var timeout = Math.clamp(extractInt(request, "timeoutMs", 5000), 1, 120000);
        final var quiet = waitForQuiet(quietMs, timeout);
        return resultResponse(
                id,
                "\"quiet\":"
                        + quiet.json()
                        + ",\"renderingVerified\":false"
                        + okOrError(
                                quiet.ok(),
                                "QUIET_TIMEOUT",
                                "Sanitized scene state did not become quiet"),
                "");
    }

    private static String layoutResponse(final String id, final String request) throws Exception {
        final var timeout = Math.clamp(extractInt(request, "timeoutMs", 2000), 1, 120000);
        final var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        final var scene =
                onFxThread(
                        () -> screenshotWindow(extractString(request, "window")).getScene(),
                        timeout);
        final var remaining =
                Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
        final var observed = FxWaits.nextLayout(scene, remaining);
        return resultResponse(
                id,
                "\"observed\":"
                        + observed
                        + ",\"boundary\":\"post-layout-pulse\",\"renderingVerified\":false"
                        + okOrError(observed, "LAYOUT_TIMEOUT", "Layout pulse was not observed"),
                "");
    }

    private static String waitResponse(final String id, final String request) throws Exception {
        final var query = highlightQuery(request);
        final var timeout = Math.clamp(extractInt(request, "timeoutMs", 2000), 1, 120000);
        final var present = extractBoolean(request, "present", true);
        final var expectedCount = extractInt(request, "count", -1);
        final var targets = new ArrayList<Node>();
        final var result =
                FxWaits.until(
                        () -> {
                            targets.clear();
                            targets.addAll(
                                    matchesFor(query, extractBoolean(request, "visible", true)));
                            return (int)
                                    targets.stream()
                                            .filter(node -> predicateMatches(node, request))
                                            .count();
                        },
                        count ->
                                expectedCount >= 0
                                        ? count == expectedCount
                                        : present ? count > 0 : count == 0,
                        () -> waitSignals(targets),
                        timeout);
        final var fields = new LinkedHashMap<String, Object>();
        fields.put("query", query);
        fields.put("present", present);
        fields.put("count", result.observed() ? result.value() : -1);
        fields.put("observed", result.observed());
        fields.put("polls", result.polls());
        fields.put("strategy", "observable-with-bounded-fallback");
        fields.put("ok", result.satisfied());
        if (!result.satisfied()) {
            fields.put("nearMatches", result.observed() ? safeNearMatches(query) : List.of());
            fields.put(
                    "error", Map.of("code", "TIMEOUT", "message", "Wait predicate not satisfied"));
        }
        final var json = Json.stringify(fields);
        return resultResponse(id, json.substring(1, json.length() - 1), "");
    }

    private static List<Object> safeNearMatches(final String query) {
        try {
            final var value = Json.parse("[" + nearMatchesJson(query) + "]");
            return value instanceof List<?> list ? new ArrayList<Object>(list) : List.of();
        } catch (final Exception unavailable) {
            return List.of();
        }
    }

    private record NearMatch(Node node, int score) {}

    private static String nearMatchesJson(final String query) throws Exception {
        final var expected =
                truncate(query.replaceFirst("^(text=|type=|regexText=|[=#.])", ""), 80)
                        .toLowerCase(java.util.Locale.ROOT);
        if (expected.isBlank()) {
            return "";
        }
        return onFxThread(
                () -> {
                    final var candidates = new ArrayList<NearMatch>();
                    for (final var window : Window.getWindows()) {
                        if (!window.isShowing() || window.getScene() == null) {
                            continue;
                        }
                        for (final var node : flatten(window.getScene().getRoot())) {
                            if (!effectivelyVisible(node)) {
                                continue;
                            }
                            final var nodeId =
                                    truncate(node.getId(), 80).toLowerCase(java.util.Locale.ROOT);
                            final var text =
                                    truncate(textOf(node), 80).toLowerCase(java.util.Locale.ROOT);
                            final var type =
                                    node.getClass()
                                            .getSimpleName()
                                            .toLowerCase(java.util.Locale.ROOT);
                            if (nodeId.isBlank() && text.isBlank()) {
                                continue;
                            }
                            candidates.add(
                                    new NearMatch(
                                            node,
                                            Math.min(
                                                    editDistance(
                                                            expected,
                                                            nodeId.isBlank() ? type : nodeId),
                                                    editDistance(
                                                            expected,
                                                            text.isBlank() ? type : text))));
                        }
                    }
                    candidates.sort(java.util.Comparator.comparingInt(NearMatch::score));
                    final var values = new ArrayList<String>();
                    for (var i = 0; i < Math.min(5, candidates.size()); i++) {
                        values.add(nearMatchJson(candidates.get(i).node()));
                    }
                    return String.join(",", values);
                },
                100);
    }

    private static String nearMatchJson(final Node node) {
        final var window = node.getScene() == null ? null : node.getScene().getWindow();
        return "{\"eid\":\"n"
                + handle(node)
                + "\",\"type\":\""
                + jsonEscape(node.getClass().getSimpleName())
                + "\",\"id\":\""
                + jsonEscape(truncate(node.getId(), 160))
                + "\",\"text\":\""
                + jsonEscape(truncate(textOf(node), 160))
                + "\",\"selectorPath\":\""
                + jsonEscape(window == null ? "" : selectorPath(window, node))
                + "\"}";
    }

    private static int editDistance(final String left, final String right) {
        final var previous = new int[right.length() + 1];
        for (var j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (var i = 1; i <= left.length(); i++) {
            final var current = new int[right.length() + 1];
            current[0] = i;
            for (var j = 1; j <= right.length(); j++) {
                current[j] =
                        Math.min(
                                Math.min(current[j - 1] + 1, previous[j] + 1),
                                previous[j - 1]
                                        + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1));
            }
            System.arraycopy(current, 0, previous, 0, current.length);
        }
        return previous[right.length()];
    }

    private static String assertResponse(final String id, final String request) throws Exception {
        final var query = highlightQuery(request);
        final var present = extractBoolean(request, "present", true);
        final var expectedCount = extractInt(request, "count", -1);
        final var count =
                onFxThread(
                        () ->
                                (int)
                                        matchesFor(query, extractBoolean(request, "visible", true))
                                                .stream()
                                                .filter(node -> predicateMatches(node, request))
                                                .count());
        final var ok =
                expectedCount >= 0 ? count == expectedCount : present ? count > 0 : count == 0;
        return resultResponse(
                id,
                "\"query\":\""
                        + jsonEscape(query)
                        + "\",\"present\":"
                        + present
                        + ",\"expectedCount\":"
                        + expectedCount
                        + ",\"count\":"
                        + count
                        + okOrError(ok, "ASSERT_FAILED", "Assert predicate not satisfied"),
                request);
    }

    private static boolean predicateMatches(final Node node, final String request) {
        final var expected = namedParams(request);
        markSensitive(node, Boolean.TRUE.equals(expected.get("sensitive")));
        if (expected.containsKey("enabled")
                && !equivalent(
                        expected.get("enabled"),
                        !node.isDisabled() && modalBlocker(windowOf(node)) == null)) return false;
        if (expected.containsKey("focused")
                && !equivalent(expected.get("focused"), node.isFocused())) return false;
        for (final var key :
                List.of(
                        "value",
                        "displayValue",
                        "selected",
                        "selectedIndex",
                        "selectedIndices",
                        "itemsCount",
                        "expanded",
                        "editable")) {
            if (expected.containsKey(key)
                    && !equivalent(expected.get(key), observedValue(node, key, request)))
                return false;
        }
        if (expected.get("pseudoClass") instanceof String name
                && node.getPseudoClassStates().stream()
                        .noneMatch(state -> state.getPseudoClassName().equals(name))) return false;
        return true;
    }

    private static boolean equivalent(final Object expected, final Object actual) {
        if (expected instanceof Number left && actual instanceof Number right) {
            return new java.math.BigDecimal(left.toString())
                            .compareTo(new java.math.BigDecimal(right.toString()))
                    == 0;
        }
        if (expected instanceof List<?> left && actual instanceof List<?> right) {
            if (left.size() != right.size()) return false;
            for (var i = 0; i < left.size(); i++)
                if (!equivalent(left.get(i), right.get(i))) return false;
            return true;
        }
        return java.util.Objects.equals(expected, actual);
    }

    private static javafx.scene.control.MultipleSelectionModel<?> multipleSelection(
            final Node node) {
        if (node instanceof ListView<?> list) return list.getSelectionModel();
        if (node instanceof TableView<?> table) return table.getSelectionModel();
        if (node instanceof TreeView<?> tree) return tree.getSelectionModel();
        if (node instanceof TreeTableView<?> tree) return tree.getSelectionModel();
        return null;
    }

    private static javafx.scene.control.SingleSelectionModel<?> singleSelection(final Node node) {
        if (node instanceof ComboBox<?> combo) return combo.getSelectionModel();
        if (node instanceof ChoiceBox<?> choice) return choice.getSelectionModel();
        if (node instanceof TabPane tabs) return tabs.getSelectionModel();
        return null;
    }

    private static Object observedValue(final Node node, final String key, final String request) {
        if ("selectedIndices".equals(key) || "selectedIndex".equals(key)) {
            final var multiple = multipleSelection(node);
            final var single = singleSelection(node);
            if ("selectedIndex".equals(key))
                return multiple != null
                        ? multiple.getSelectedIndex()
                        : single == null ? null : single.getSelectedIndex();
            return multiple != null
                    ? multiple.getSelectedIndices()
                    : single == null
                            ? null
                            : single.getSelectedIndex() < 0
                                    ? List.of()
                                    : List.of(single.getSelectedIndex());
        }
        if ("itemsCount".equals(key)) {
            if (node instanceof ListView<?> list) return itemCount(list.getItems());
            if (node instanceof TableView<?> table) return itemCount(table.getItems());
            if (node instanceof ComboBox<?> combo) return itemCount(combo.getItems());
            if (node instanceof ChoiceBox<?> choice) return itemCount(choice.getItems());
            if (node instanceof TreeView<?> tree) return tree.getExpandedItemCount();
            if (node instanceof TreeTableView<?> tree) return tree.getExpandedItemCount();
            if (node instanceof TabPane tabs) return tabs.getTabs().size();
            return node.queryAccessibleAttribute(AccessibleAttribute.ITEM_COUNT);
        }
        if ("selected".equals(key)) {
            if (node instanceof CheckBox check) return check.isSelected();
            if (node instanceof Toggle toggle) return toggle.isSelected();
            return node.queryAccessibleAttribute(AccessibleAttribute.SELECTED);
        }
        if ("expanded".equals(key)) {
            if (node instanceof TitledPane pane) return pane.isExpanded();
            if (node instanceof Accordion accordion) return accordion.getExpandedPane() != null;
            final var index = extractInt(request, "index", 0);
            if (node instanceof TreeView<?> tree) {
                final var item = tree.getTreeItem(index);
                return item == null ? null : item.isExpanded();
            }
            if (node instanceof TreeTableView<?> tree) {
                final var item = tree.getTreeItem(index);
                return item == null ? null : item.isExpanded();
            }
            return node.queryAccessibleAttribute(AccessibleAttribute.EXPANDED);
        }
        if ("editable".equals(key)) {
            final var editor = FxControls.editor(node);
            return editor == null
                    ? node.queryAccessibleAttribute(AccessibleAttribute.EDITABLE)
                    : editor.isEditable() && !editor.textProperty().isBound();
        }
        if ("displayValue".equals(key)) return FxControls.displayValue(node);
        if (node instanceof TextInputControl input) return input.getText();
        if (node instanceof Slider slider) return slider.getValue();
        if (node instanceof ScrollBar bar) return bar.getValue();
        if (node instanceof CheckBox check) return check.isSelected();
        if (node instanceof Toggle toggle) return toggle.isSelected();
        if (node instanceof DatePicker date)
            return date.getValue() == null ? null : date.getValue().toString();
        if (node instanceof Spinner<?> spinner) return String.valueOf(spinner.getValue());
        if (node instanceof TabPane tabs) {
            final var tab = tabs.getSelectionModel().getSelectedItem();
            return tab == null ? null : tabName(tab);
        }
        if (node instanceof ComboBoxBase<?> combo) return String.valueOf(combo.getValue());
        if (node instanceof ChoiceBox<?> choice) return String.valueOf(choice.getValue());
        final var value = node.queryAccessibleAttribute(AccessibleAttribute.VALUE);
        return value != null ? value : node.queryAccessibleAttribute(AccessibleAttribute.TEXT);
    }

    private static List<javafx.beans.Observable> waitSignals(final List<Node> nodes) {
        final Set<javafx.beans.Observable> signals =
                Collections.newSetFromMap(new IdentityHashMap<>());
        signals.add(Window.getWindows());
        for (final var window : Window.getWindows()) {
            signals.add(window.showingProperty());
            signals.add(window.focusedProperty());
            signals.add(window.sceneProperty());
            if (window.getScene() != null) {
                signals.add(window.getScene().rootProperty());
                signals.add(window.getScene().focusOwnerProperty());
            }
        }
        for (final var node : nodes) {
            signals.add(node.disabledProperty());
            signals.add(node.focusedProperty());
            signals.add(node.accessibleTextProperty());
            signals.add(node.getPseudoClassStates());
            for (Node current = node; current != null; current = current.getParent()) {
                signals.add(current.visibleProperty());
                signals.add(current.parentProperty());
                signals.add(current.sceneProperty());
                if (current instanceof Parent parent) signals.add(parent.getChildrenUnmodifiable());
            }
            if (node instanceof Labeled label) signals.add(label.textProperty());
            if (node instanceof TextInputControl input) {
                signals.add(input.textProperty());
                signals.add(input.editableProperty());
            }
            if (node instanceof CheckBox check) signals.add(check.selectedProperty());
            if (node instanceof Toggle toggle) signals.add(toggle.selectedProperty());
            if (node instanceof ComboBoxBase<?> combo) signals.add(combo.valueProperty());
            if (node instanceof ComboBox<?> combo) {
                signals.add(combo.itemsProperty());
                signals.add(combo.getItems());
            }
            if (node instanceof ChoiceBox<?> choice) {
                signals.add(choice.valueProperty());
                signals.add(choice.itemsProperty());
                signals.add(choice.getItems());
            }
            if (node instanceof Spinner<?> spinner) signals.add(spinner.valueProperty());
            if (node instanceof Slider slider) signals.add(slider.valueProperty());
            if (node instanceof ScrollBar bar) signals.add(bar.valueProperty());
            if (node instanceof TitledPane pane) signals.add(pane.expandedProperty());
            if (node instanceof ListView<?> list) {
                signals.add(list.itemsProperty());
                signals.add(list.getItems());
            }
            if (node instanceof TableView<?> table) {
                signals.add(table.itemsProperty());
                signals.add(table.getItems());
            }
            if (node instanceof TreeView<?> tree) {
                signals.add(tree.rootProperty());
                signals.add(tree.expandedItemCountProperty());
            }
            if (node instanceof TreeTableView<?> tree) {
                signals.add(tree.rootProperty());
                signals.add(tree.expandedItemCountProperty());
            }
            if (node instanceof TabPane tabs) signals.add(tabs.getTabs());
            final var multiple = multipleSelection(node);
            if (multiple != null) {
                signals.add(multiple.getSelectedIndices());
                signals.add(multiple.selectedIndexProperty());
            }
            final var single = singleSelection(node);
            if (single != null) signals.add(single.selectedIndexProperty());
        }
        signals.remove(null);
        return List.copyOf(signals);
    }

    private static String screenshotResponse(final String id, final String request)
            throws Exception {
        var path = extractString(request, "path");
        if (path.isBlank())
            path =
                    Files.createTempDirectory("fxdriver-images-")
                            .resolve("screenshot.png")
                            .toString();
        if (!Path.of(path).isAbsolute())
            throw new IllegalArgumentException("screenshot path must be absolute");
        return "{\"jsonrpc\":\"2.0\",\"id\":"
                + id
                + ",\"result\":"
                + screenshot(Path.of(path), request)
                + "}";
    }

    private static String highlightQuery(final String request) {
        if (!hasTargetSelector(request))
            throw new IllegalArgumentException("Explicit target selector required");
        return highlightQueryOrDefault(request, "");
    }

    private static String highlightQueryOrDefault(final String request, final String fallback) {
        final var filters = new ArrayList<String>();
        final var handle = extractInt(request, "handle", -1);
        if (hasKey(request, "handle") && handle < 1)
            throw new IllegalArgumentException("handle must be positive");
        if (handle >= 0) {
            filters.add("@" + handle);
        }
        for (final var field :
                List.of(
                        "eid",
                        "nodeId",
                        "cssId",
                        "styleClass",
                        "textExact",
                        "text",
                        "regexText",
                        "accessible",
                        "role",
                        "selector",
                        "type",
                        "window")) {
            if (!hasKey(request, field)) continue;
            final var value = extractString(request, field);
            if (value.isBlank() && !"textExact".equals(field)) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            filters.add(
                    switch (field) {
                        case "eid" -> "eid=" + value;
                        case "nodeId", "cssId" -> "#" + value;
                        case "styleClass" -> "." + value;
                        case "textExact" -> "=" + value;
                        case "selector" -> value;
                        default -> field + "=" + value;
                    });
        }
        return filters.isEmpty()
                ? fallback
                : filters.size() == 1 ? filters.getFirst() : "all=[" + jsonStrings(filters) + "]";
    }

    private static String inputQuery(final String request) {
        return highlightQuery(request);
    }

    private static String inputValue(final String request) {
        if (!hasKey(request, "value")) {
            throw new IllegalArgumentException(
                    "setText/type require value; text is a selector field");
        }
        final var value = namedParams(request).get("value");
        if (value instanceof String || value instanceof Number || value instanceof Boolean)
            return value.toString();
        throw new IllegalArgumentException("value must be a string, number or boolean");
    }

    private static int traceMs(final String request) {
        if (hasKey(request, "highlightMs")) {
            return Math.max(0, extractInt(request, "highlightMs", 0));
        }
        return visualTrace ? visualTraceMs : 0;
    }

    private static String highlightEffect(final String request) {
        final var effect = extractString(request, "effect");
        return effect.isBlank() ? visualTraceEffect : effect;
    }

    private static String snapshot(final List<String> includeTypes, final List<String> excludeTypes)
            throws Exception {
        final var result = new CompletableFuture<String>();
        Platform.runLater(
                () -> {
                    try {
                        final var windows = new StringBuilder();
                        final var flatNodes = new StringBuilder();
                        var firstWindow = true;
                        var firstFlatNode = true;
                        for (final var window : Window.getWindows()) {
                            if (!window.isShowing() || window.getScene() == null) {
                                continue;
                            }
                            if (!firstWindow) {
                                windows.append(',');
                            }
                            firstWindow = false;
                            windows.append(windowJson(window));
                            windows.append(",\"nodes\":[");
                            var firstWindowNode = true;
                            final var candidates =
                                    includeTypes.isEmpty() && excludeTypes.isEmpty()
                                            ? flatten(window.getScene().getRoot())
                                            : stateFlatten(window.getScene().getRoot());
                            for (final var node : candidates) {
                                if (!effectivelyVisible(node)) {
                                    continue;
                                }
                                if (!typeMatches(node, includeTypes, excludeTypes)) {
                                    continue;
                                }
                                if (!firstWindowNode) {
                                    windows.append(',');
                                }
                                firstWindowNode = false;
                                final var json = nodeJson(window, node);
                                windows.append(json);
                                if (!firstFlatNode) {
                                    flatNodes.append(',');
                                }
                                firstFlatNode = false;
                                flatNodes.append(json);
                            }
                            windows.append("]}");
                        }
                        result.complete(
                                "{\"windows\":[" + windows + "],\"nodes\":[" + flatNodes + "]}");
                    } catch (final Throwable throwable) {
                        result.completeExceptionally(throwable);
                    }
                });
        return result.get(30, TimeUnit.SECONDS);
    }

    private static boolean typeMatches(
            final Node node, final List<String> includeTypes, final List<String> excludeTypes) {
        if (includeTypes.isEmpty() && excludeTypes.isEmpty()) {
            return true;
        }
        final var type = node.getClass().getSimpleName();
        if (!excludeTypes.isEmpty() && excludeTypes.contains(type)) {
            return false;
        }
        if (!includeTypes.isEmpty() && !includeTypes.contains(type)) {
            return false;
        }
        return true;
    }

    private static String compactState(final String mode, final int limit) throws Exception {
        final var result = new CompletableFuture<String>();
        Platform.runLater(
                () -> {
                    try {
                        result.complete(compactStateOnFx(mode, limit));
                    } catch (final Throwable throwable) {
                        result.completeExceptionally(throwable);
                    }
                });
        return result.get(30, TimeUnit.SECONDS);
    }

    private static String compactStateOnFx(final String mode, final int limit) {
        final var windows = new StringBuilder();
        final var nodes = new StringBuilder();
        var firstWindow = true;
        var firstNode = true;
        var emitted = 0;
        String focus = "null";
        for (final var window : orderedWindows()) {
            if (!window.isShowing() || window.getScene() == null) {
                continue;
            }
            if (!firstWindow) {
                windows.append(',');
            }
            firstWindow = false;
            windows.append(windowJson(window)).append('}');
            final var focusOwner = window.getScene().getFocusOwner();
            if ("null".equals(focus) && focusOwner != null && effectivelyVisible(focusOwner)) {
                focus = compactNodeJson(window, focusOwner);
            }
            if ("minimal".equals(mode)) {
                continue;
            }
            for (final var node : stateFlatten(window.getScene().getRoot())) {
                if (emitted >= limit) {
                    break;
                }
                if (!stateNode(node, mode)) {
                    continue;
                }
                if (!firstNode) {
                    nodes.append(',');
                }
                firstNode = false;
                nodes.append(compactNodeJson(window, node));
                emitted++;
            }
        }
        return "{\"mode\":\""
                + jsonEscape(mode)
                + "\",\"limit\":"
                + limit
                + ",\"truncated\":"
                + (emitted >= limit)
                + ",\"windows\":["
                + windows
                + "],\"focus\":"
                + focus
                + ",\"nodes\":["
                + nodes
                + "]}";
    }

    private static boolean stateNode(final Node node, final String mode) {
        if (!effectivelyVisible(node)) {
            return false;
        }
        if (node instanceof Text text && hasLabeledAncestor(text)) {
            return false;
        }
        final var kind = semanticKind(node);
        if ("debug".equals(mode)) {
            return !kind.isBlank() || !textOf(node).isBlank();
        }
        return node.isFocused()
                || hasKnownOperations(node)
                || (node.getId() != null && !node.getId().isBlank())
                || !accessibleNameOf(node).isBlank()
                || node instanceof ButtonBase
                || node instanceof TextInputControl
                || node instanceof Labeled labeled && !empty(labeled.getText())
                || "webView".equals(kind)
                || "table".equals(kind)
                || "treeTable".equals(kind)
                || "list".equals(kind)
                || "tree".equals(kind);
    }

    private static String compactSemanticJson(final Node node) {
        if (isSensitive(node) && !(node instanceof TextInputControl)) {
            return ",\"semantic\":{\"kind\":\"" + semanticKind(node) + "\",\"masked\":true}";
        }
        final var fields = new ArrayList<String>(valueControlFields(node));
        addString(fields, "kind", semanticKind(node));
        addSelectionFields(fields, node);
        if (node instanceof Labeled labeled) {
            addString(fields, "text", labeled.getText() == null ? "" : labeled.getText());
        }
        if (node instanceof TextInputControl input) {
            final var masked = isSensitive(input);
            addString(fields, "value", masked ? "[redacted]" : truncate(input.getText(), 512));
            addString(
                    fields,
                    "prompt",
                    masked || input.getPromptText() == null ? "" : input.getPromptText());
            fields.add("\"editable\":" + input.isEditable());
            if (!masked) {
                fields.add("\"length\":" + input.getLength());
                fields.add("\"valueTruncated\":" + (input.getLength() > 512));
            }
            fields.add("\"masked\":" + masked);
        }
        if (node instanceof ListView<?> listView) {
            fields.add("\"itemsCount\":" + itemCount(listView.getItems()));
            fields.add("\"selectedIndices\":" + jsonInts(selectedIndices(node)));
            fields.add("\"focusedIndex\":" + focusedIndex(node));
        }
        if (node instanceof TableView<?> tableView) {
            fields.add("\"itemsCount\":" + itemCount(tableView.getItems()));
            fields.add("\"selectedIndices\":" + jsonInts(selectedIndices(node)));
            fields.add("\"columns\":" + jsonColumns(tableView.getVisibleLeafColumns()));
            fields.add("\"columnsCount\":" + tableView.getVisibleLeafColumns().size());
            fields.add("\"columnsTruncated\":" + (tableView.getVisibleLeafColumns().size() > 100));
        }
        if (node instanceof TreeView<?> treeView) {
            fields.add("\"expandedItemCount\":" + treeView.getExpandedItemCount());
            fields.add("\"selectedIndices\":" + jsonInts(selectedIndices(node)));
        }
        if (node instanceof TreeTableView<?> treeTableView) {
            fields.add("\"expandedItemCount\":" + treeTableView.getExpandedItemCount());
            fields.add("\"selectedIndices\":" + jsonInts(selectedIndices(node)));
            fields.add("\"columns\":" + jsonColumns(treeTableView.getVisibleLeafColumns()));
            fields.add("\"columnsCount\":" + treeTableView.getVisibleLeafColumns().size());
            fields.add(
                    "\"columnsTruncated\":" + (treeTableView.getVisibleLeafColumns().size() > 100));
        }
        return fields.isEmpty() ? "" : ",\"semantic\":{" + String.join(",", fields) + "}";
    }

    private static List<String> typedOperations(final Node node) {
        final var ops = new LinkedHashSet<String>();
        if (node.isFocusTraversable()) ops.add("focus");
        if (node instanceof ButtonBase) ops.addAll(List.of("fire", "click", "key"));
        if (node instanceof TextInputControl input) {
            ops.add("key");
            if (input.isEditable() && !input.textProperty().isBound()) {
                ops.addAll(List.of("setText", "type", "clear", "setValue", "commit"));
            }
        }
        if (FxControls.isValueControl(node)
                && !(node instanceof TextInputControl)
                && !FxControls.valueBound(node)) ops.add("setValue");
        if (node instanceof ComboBoxBase<?>
                || node instanceof ChoiceBox<?>
                || node instanceof MenuButton) {
            ops.addAll(List.of("showPopup", "hidePopup"));
        }
        if (node instanceof ComboBox<?> combo) {
            ops.add("selectIndex");
            if (combo.isEditable()) ops.addAll(List.of("setText", "type", "clear", "commit"));
        }
        if (node instanceof ChoiceBox<?>) ops.add("selectIndex");
        if (node instanceof DatePicker date && date.isEditable())
            ops.addAll(List.of("setText", "commit"));
        if (node instanceof Spinner<?> spinner) {
            ops.addAll(List.of("increment", "decrement"));
            if (spinner.isEditable()) ops.addAll(List.of("setText", "commit"));
        }
        if (node instanceof Slider || node instanceof ScrollBar)
            ops.addAll(List.of("increment", "decrement"));
        if (node instanceof ListView<?>)
            ops.addAll(List.of("listItems", "selectListItem", "selectIndex", "scrollToIndex"));
        if (node instanceof TableView<?> || node instanceof TreeTableView<?>) {
            ops.addAll(List.of("tableCell", "tableColumns", "selectIndex", "scrollToIndex"));
        }
        if (node instanceof TreeView<?>)
            ops.addAll(List.of("selectIndex", "scrollToIndex", "expand", "collapse"));
        if (node instanceof TitledPane || node instanceof Accordion)
            ops.addAll(List.of("expand", "collapse"));
        if (node instanceof TabPane || node instanceof Accordion) ops.add("selectIndex");
        if (node instanceof ScrollPane
                || node instanceof ListView<?>
                || node instanceof TableView<?>
                || node instanceof TreeView<?>
                || node instanceof TreeTableView<?>) ops.add("scroll");
        if (!menuItems(node).isEmpty()) ops.add("fireMenuItem");
        if (isWebView(node)) ops.add("webExecuteScript");
        if (node.getOnMouseClicked() != null || node.getOnMousePressed() != null) ops.add("click");
        if (node.getOnScroll() != null) ops.add("scroll");
        return List.copyOf(ops);
    }

    private static boolean hasKnownOperations(final Node node) {
        return !typedOperations(node).isEmpty() || !FxControls.candidateOperations(node).isEmpty();
    }

    private static String interactionJson(final Node node) {
        final var typed = typedOperations(node);
        final var accessible = FxControls.candidateOperations(node);
        final var fields = new LinkedHashMap<String, Object>();
        final var name = accessibleName(node);
        fields.put("nameSource", name.source());
        fields.put("nameTruncated", name.value().length() > 512);
        fields.put("operations", typed);
        fields.put("accessibleOperations", accessible);
        fields.put(
                "eligible",
                effectivelyVisible(node)
                        && !node.isDisabled()
                        && modalBlocker(windowOf(node)) == null);
        fields.put("sensitive", isSensitive(node));
        fields.put("valueBound", FxControls.valueBound(node));
        fields.put(
                "pseudoClasses",
                node.getPseudoClassStates().stream()
                        .map(javafx.css.PseudoClass::getPseudoClassName)
                        .sorted()
                        .toList());
        if (node instanceof TextInputControl input)
            fields.put("textBound", input.textProperty().isBound());
        if (typed.isEmpty() && !accessible.isEmpty())
            fields.put("accessibleState", FxControls.state(node, isSensitive(node)));
        final var json = Json.stringify(fields);
        return "," + json.substring(1, json.length() - 1);
    }

    private static String compactNodeJson(final Window window, final Node node) {
        final var bounds = node.localToScene(node.getBoundsInLocal());
        return "{\"handle\":"
                + handle(node)
                + ",\"eid\":\"n"
                + handle(node)
                + "\",\"window\":\"w"
                + windowHandle(window)
                + "\",\"type\":\""
                + jsonEscape(node.getClass().getSimpleName())
                + "\",\"id\":\""
                + jsonEscape(node.getId() == null ? "" : node.getId())
                + "\",\"text\":\""
                + jsonEscape(textOf(node))
                + "\",\"accessibleText\":\""
                + jsonEscape(accessibleTextOf(node))
                + "\",\"name\":\""
                + jsonEscape(accessibleNameOf(node))
                + "\",\"accessibleRole\":\""
                + jsonEscape(accessibleRoleOf(node))
                + "\",\"disabled\":"
                + node.isDisabled()
                + ",\"focused\":"
                + node.isFocused()
                + ",\"actionable\":"
                + hasKnownOperations(node)
                + interactionJson(node)
                + ",\"bounds\":{"
                + boundsJson(bounds)
                + "}"
                + compactSemanticJson(node)
                + "}";
    }

    private static boolean empty(final String value) {
        return value == null || value.isBlank();
    }

    private record ScreenshotCapture(
            java.awt.image.BufferedImage image,
            String target,
            Bounds source,
            String units,
            String window,
            String visibleText,
            String node,
            int redactions) {}

    private static String screenshot(final Path requestedPath, final String request)
            throws Exception {
        final var path = requestedPath.toAbsolutePath();
        final var capture = onFxThread(() -> captureScreenshot(request));
        // Encoding and filesystem I/O do not own JavaFX state.
        saveImage(path, capture.image());
        final var source = capture.source();
        return "{\"path\":\""
                + jsonEscape(path.toString())
                + "\",\"bytes\":"
                + Files.size(path)
                + ",\"target\":\""
                + capture.target()
                + "\",\"image\":{"
                + boundsJson(0, 0, capture.image().getWidth(), capture.image().getHeight())
                + "},\"source\":{"
                + boundsJson(source)
                + ",\"units\":\""
                + capture.units()
                + "\"},\"scale\":{\"x\":"
                + scale(capture.image().getWidth(), Math.round((float) source.getWidth()))
                + ",\"y\":"
                + scale(capture.image().getHeight(), Math.round((float) source.getHeight()))
                + "},\"activeWindow\":"
                + capture.window()
                + ",\"visibleTextSample\":["
                + capture.visibleText()
                + "],\"node\":"
                + capture.node()
                + ",\"redactions\":"
                + capture.redactions()
                + ",\"captureType\":\""
                + ("node".equals(capture.target())
                        ? "javafx-node-snapshot"
                        : "javafx-scene-snapshot")
                + "\",\"physicalOcclusionVerified\":false}";
    }

    private static ScreenshotCapture captureScreenshot(final String request) {
        var target = extractString(request, "target");
        if (target.isBlank()) target = "window";
        if (!Set.of("window", "node", "rect").contains(target)) {
            throw new IllegalArgumentException("screenshot target must be window, node or rect");
        }
        final Window window;
        final Node node;
        final java.awt.image.BufferedImage image;
        final Bounds source;
        final String units;
        if ("node".equals(target)) {
            final var query = highlightQuery(request);
            final var matches = rawMatchesFor(query, Boolean.TRUE);
            if (matches.size() > 1) throw new TargetFailure("AMBIGUOUS", query, matches);
            if (matches.isEmpty())
                throw new IllegalArgumentException("No node matched screenshot target");
            node = matches.getFirst();
            window = windowOf(node);
            requireUnblockedCapture(window);
            image = toBufferedImage(node.snapshot(null, null));
            source = node.getBoundsInLocal();
            units = "node-local";
        } else {
            node = null;
            window = screenshotWindow(extractString(request, "window"));
            requireUnblockedCapture(window);
            final var full = windowImage(window);
            if ("rect".equals(target)) {
                final var w = extractInt(request, "w", 0);
                final var h = extractInt(request, "h", 0);
                if (w <= 0 || h <= 0)
                    throw new IllegalArgumentException("rect requires positive w and h");
                final var requestedX = extractInt(request, "x", 0);
                final var requestedY = extractInt(request, "y", 0);
                final var x = Math.max(0, requestedX);
                final var y = Math.max(0, requestedY);
                final var right = Math.min(full.getWidth(), (long) requestedX + w);
                final var bottom = Math.min(full.getHeight(), (long) requestedY + h);
                if (right <= x || bottom <= y)
                    throw new IllegalArgumentException(
                            "rect does not intersect the captured image");
                final var width = (int) (right - x);
                final var height = (int) (bottom - y);
                image = full.getSubimage(x, y, width, height);
                source = new javafx.geometry.BoundingBox(x, y, width, height);
                units = "window-image";
            } else {
                image = full;
                source = new javafx.geometry.BoundingBox(0, 0, image.getWidth(), image.getHeight());
                units = "scene";
            }
        }
        final var redactions =
                extractBoolean(request, "maskSensitive", true)
                        ? redactScreenshot(image, node, window, source)
                        : 0;
        return new ScreenshotCapture(
                image,
                target,
                source,
                units,
                screenshotOrientationJson(window),
                screenshotVisibleTextJson(window),
                node == null ? "null" : targetJson(node),
                redactions);
    }

    private static void requireUnblockedCapture(final Window window) {
        if (modalBlocker(window) != null) {
            throw new TargetFailure(
                    "BLOCKED",
                    "window=w" + windowHandle(window),
                    window.getScene() == null ? List.of() : List.of(window.getScene().getRoot()));
        }
    }

    private static int redactScreenshot(
            final java.awt.image.BufferedImage image,
            final Node target,
            final Window window,
            final Bounds source) {
        final var graphics = image.createGraphics();
        graphics.setColor(new java.awt.Color(32, 32, 32));
        var count = 0;
        try {
            for (final var node : flatten(target == null ? window.getScene().getRoot() : target)) {
                if (!effectivelyVisible(node)
                        || !isSensitive(node)
                        || (node != target
                                && node.getParent() != null
                                && isSensitive(node.getParent()))) continue;
                final var sceneBounds = node.localToScene(node.getBoundsInLocal());
                final var bounds = target == null ? sceneBounds : target.sceneToLocal(sceneBounds);
                final var scaleX = image.getWidth() / Math.max(1.0, source.getWidth());
                final var scaleY = image.getHeight() / Math.max(1.0, source.getHeight());
                final var x = (int) Math.floor((bounds.getMinX() - source.getMinX()) * scaleX);
                final var y = (int) Math.floor((bounds.getMinY() - source.getMinY()) * scaleY);
                final var width = (int) Math.ceil(bounds.getWidth() * scaleX);
                final var height = (int) Math.ceil(bounds.getHeight() * scaleY);
                if (x < image.getWidth()
                        && y < image.getHeight()
                        && x + width > 0
                        && y + height > 0) {
                    graphics.fillRect(x, y, width, height);
                    count++;
                }
            }
        } finally {
            graphics.dispose();
        }
        return count;
    }

    private static Window screenshotWindow(final String requested) {
        Window fallback = null;
        for (final var window : orderedWindows()) {
            if (!window.isShowing() || window.getScene() == null) {
                continue;
            }
            if (("w" + windowHandle(window)).equals(requested)) {
                return window;
            }
            if (fallback == null) {
                fallback = window;
            }
        }
        if (!requested.isBlank()) {
            throw new IllegalArgumentException("unknown screenshot window: " + requested);
        }
        if (fallback != null) {
            return fallback;
        }
        throw new IllegalStateException("no showing JavaFX window");
    }

    private static java.awt.image.BufferedImage windowImage(final Window window) {
        return toBufferedImage(window.getScene().snapshot(null));
    }

    private static String screenshotOrientationJson(final Window window) {
        final var title = window instanceof Stage stage ? stage.getTitle() : "";
        final var focused = window.getScene().getFocusOwner();
        return "{\"wid\":\"w"
                + windowHandle(window)
                + "\",\"title\":\""
                + jsonEscape(truncate(title, 160))
                + "\",\"bounds\":{"
                + boundsJson(
                        Math.round(window.getX()),
                        Math.round(window.getY()),
                        Math.round(window.getWidth()),
                        Math.round(window.getHeight()))
                + "},\"focusedNode\":"
                + (focused == null ? "null" : targetJson(focused))
                + "}";
    }

    private static String screenshotVisibleTextJson(final Window window) {
        final var values = new LinkedHashSet<String>();
        for (final var node : flatten(window.getScene().getRoot())) {
            if (!effectivelyVisible(node)) {
                continue;
            }
            final var text = truncate(textOf(node), 160);
            if (!text.isBlank() && values.size() < 20) {
                values.add(text);
            }
        }
        return jsonStrings(new ArrayList<>(values));
    }

    private static void saveImage(final Path path, final java.awt.image.BufferedImage image)
            throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        writeOptimizedPng(image, path);
    }

    private static java.awt.image.BufferedImage toBufferedImage(final WritableImage image) {
        final var width = (int) image.getWidth();
        final var height = (int) image.getHeight();
        final var pixels = new int[width * height];
        image.getPixelReader()
                .getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
        var opaque = true;
        for (final var pixel : pixels) {
            if (((pixel >>> 24) & 0xff) != 0xff) {
                opaque = false;
                break;
            }
        }
        final var type =
                opaque
                        ? java.awt.image.BufferedImage.TYPE_INT_RGB
                        : java.awt.image.BufferedImage.TYPE_INT_ARGB;
        final var buffered = new java.awt.image.BufferedImage(width, height, type);
        buffered.setRGB(0, 0, width, height, pixels, 0, width);
        return buffered;
    }

    private static void writeOptimizedPng(final java.awt.image.BufferedImage image, final Path path)
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

    private static int press(final String key, final String requestedWindow) throws Exception {
        return FxActions.call(
                () -> {
                    final var window = screenshotWindow(requestedWindow);
                    if (modalBlocker(window) != null) {
                        throw new TargetFailure(
                                "BLOCKED",
                                "window=" + requestedWindow,
                                window.getScene() == null
                                        ? List.of()
                                        : List.of(window.getScene().getRoot()));
                    }
                    if (window.getScene() == null) return 0;
                    final var focused = window.getScene().getFocusOwner();
                    if (focused != null) markSensitive(focused, false);
                    final var action =
                            window.getScene()
                                    .getAccelerators()
                                    .get(KeyCombination.keyCombination(key));
                    if (action == null) {
                        return 0;
                    }
                    action.run();
                    return 1;
                });
    }

    private static int dispatchKey(final String key) {
        final var code = KeyCode.valueOf(key.toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
        final var windows = new ArrayList<>(Window.getWindows());
        Collections.reverse(windows);
        for (final var window : windows) {
            if (window instanceof Stage stage
                    && stage.isShowing()
                    && stage.isFocused()
                    && stage.getScene() != null
                    && stage.getScene().getFocusOwner() != null) {
                return dispatchKey(stage.getScene().getFocusOwner(), key, code);
            }
        }
        for (final var window : windows) {
            if (window instanceof Stage stage
                    && stage.isShowing()
                    && stage.getScene() != null
                    && stage.getScene().getFocusOwner() != null) {
                return dispatchKey(stage.getScene().getFocusOwner(), key, code);
            }
        }
        return 0;
    }

    private static int dispatchKey(final Node target, final String key, final KeyCode code) {
        target.fireEvent(
                new KeyEvent(KeyEvent.KEY_PRESSED, "", key, code, false, false, false, false));
        target.fireEvent(
                new KeyEvent(KeyEvent.KEY_RELEASED, "", key, code, false, false, false, false));
        return 1;
    }

    private static ActionResult fire(final String query, final int highlightMs, final String effect)
            throws Exception {
        return withFirstMatch(
                query,
                highlightMs,
                effect,
                node -> {
                    final var target = actionable(node);
                    if (target instanceof ButtonBase button) {
                        button.fire();
                        return target;
                    }
                    return FxControls.performAccessible(target, "fire", null) ? target : null;
                });
    }

    private static ActionResult click(
            final String query, final int highlightMs, final String effect) throws Exception {
        return withFirstRawMatch(
                query,
                highlightMs,
                effect,
                node -> {
                    final var target = clickTarget(node);
                    if (target instanceof ButtonBase button) {
                        button.fire();
                        return target;
                    }
                    if (target.getOnMouseClicked() != null
                            || target.getOnMousePressed() != null
                            || target.getOnMouseReleased() != null) {
                        return synthesizeClick(target) ? target : null;
                    }
                    return FxControls.performAccessible(target, "fire", null)
                                    || synthesizeClick(target)
                            ? target
                            : null;
                });
    }

    private static Node clickTarget(final Node node) {
        final var actionable = actionable(node);
        if (actionable instanceof ButtonBase
                || FxControls.candidateOperations(actionable).contains("fire")) {
            return actionable;
        }
        Node current = node;
        while (current != null) {
            if (current instanceof Cell<?>
                    || current.getAccessibleRole() == javafx.scene.AccessibleRole.BUTTON) {
                return current;
            }
            current = current.getParent();
        }
        return node;
    }

    private static boolean synthesizeClick(final Node node) {
        final var bounds = node.getBoundsInLocal();
        final var screenBounds = node.localToScreen(bounds);
        if (screenBounds == null) {
            return false;
        }
        node.requestFocus();
        final var x = bounds.getMinX() + bounds.getWidth() / 2.0;
        final var y = bounds.getMinY() + bounds.getHeight() / 2.0;
        final var screenX = screenBounds.getMinX() + screenBounds.getWidth() / 2.0;
        final var screenY = screenBounds.getMinY() + screenBounds.getHeight() / 2.0;
        node.fireEvent(mouseEvent(MouseEvent.MOUSE_PRESSED, x, y, screenX, screenY, true));
        node.fireEvent(mouseEvent(MouseEvent.MOUSE_RELEASED, x, y, screenX, screenY, false));
        node.fireEvent(mouseEvent(MouseEvent.MOUSE_CLICKED, x, y, screenX, screenY, false));
        return true;
    }

    private static MouseEvent mouseEvent(
            final javafx.event.EventType<MouseEvent> type,
            final double x,
            final double y,
            final double screenX,
            final double screenY,
            final boolean down) {
        return new MouseEvent(
                type,
                x,
                y,
                screenX,
                screenY,
                MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                down,
                false,
                false,
                false,
                false,
                false,
                null);
    }

    private static ActionResult type(
            final String query,
            final String text,
            final boolean replace,
            final int highlightMs,
            final String effect,
            final boolean sensitive)
            throws Exception {
        return withFirstMatch(
                query,
                highlightMs,
                effect,
                node -> {
                    final var target = actionable(node);
                    markSensitive(target, sensitive);
                    final var input = FxControls.editor(target);
                    if (input != null) {
                        if (!input.isEditable() || input.textProperty().isBound()) {
                            throw new TargetFailure("READ_ONLY", query, List.of(input));
                        }
                        input.requestFocus();
                        if (replace) input.setText(text);
                        else input.appendText(text);
                        return input;
                    }
                    final var previous = replace ? "" : FxControls.accessibleText(target);
                    return previous != null
                                    && FxControls.performAccessible(
                                            target, "setValue", previous + text)
                            ? target
                            : null;
                });
    }

    private static ActionResult setText(
            final String query, final String text, final int highlightMs, final String effect)
            throws Exception {
        return type(query, text, true, highlightMs, effect, false);
    }

    private static ActionResult setValue(
            final String query,
            final String value,
            final int highlightMs,
            final String effect,
            final boolean sensitive)
            throws Exception {
        return withFirstMatch(
                query,
                highlightMs,
                effect,
                node -> {
                    final var target = actionable(node);
                    markSensitive(target, sensitive);
                    if (target instanceof TextInputControl input
                            && (!input.isEditable() || input.textProperty().isBound())) {
                        throw new TargetFailure("READ_ONLY", query, List.of(target));
                    }
                    if (FxControls.setValue(target, value)) return target;
                    if (FxControls.isValueControl(target)) return null;
                    final Object converted =
                            Set.of("SLIDER", "SCROLL_BAR", "SPINNER")
                                            .contains(accessibleRoleOf(target))
                                    ? Double.valueOf(value)
                                    : value;
                    return FxControls.performAccessible(target, "setValue", converted)
                            ? target
                            : null;
                });
    }

    private static String commitResponse(final String id, final String request) throws Exception {
        final var query = highlightQuery(request);
        final var result =
                withFirstMatch(
                        query,
                        traceMs(request),
                        highlightEffect(request),
                        node -> FxControls.commit(node) ? node : null);
        return actionResponse(id, request, query, "committed", result);
    }

    private static ActionResult popup(
            final String query, final boolean show, final int highlightMs, final String effect)
            throws Exception {
        return withFirstMatch(
                query,
                highlightMs,
                effect,
                node -> {
                    final var target = actionable(node);
                    if (target instanceof ComboBoxBase<?> comboBoxBase) {
                        if (show) {
                            comboBoxBase.show();
                        } else {
                            comboBoxBase.hide();
                        }
                        return target;
                    }
                    if (target instanceof ChoiceBox<?> choiceBox) {
                        if (show) {
                            choiceBox.show();
                        } else {
                            choiceBox.hide();
                        }
                        return target;
                    }
                    if (target instanceof MenuButton menu) {
                        if (show) menu.show();
                        else menu.hide();
                        return target;
                    }
                    return FxControls.performAccessible(target, show ? "expand" : "collapse", null)
                            ? target
                            : null;
                });
    }

    private static ActionResult stepValue(
            final String query,
            final int steps,
            final boolean increment,
            final int highlightMs,
            final String effect)
            throws Exception {
        return withFirstMatch(
                query,
                highlightMs,
                effect,
                node -> {
                    final var target = actionable(node);
                    if (target instanceof Spinner<?> spinner && spinner.getValueFactory() != null) {
                        if (increment) {
                            spinner.getValueFactory().increment(steps);
                        } else {
                            spinner.getValueFactory().decrement(steps);
                        }
                        return target;
                    }
                    if (target instanceof Slider slider) {
                        for (var i = 0; i < steps; i++) {
                            if (increment) slider.increment();
                            else slider.decrement();
                        }
                        return target;
                    }
                    if (target instanceof ScrollBar scrollBar) {
                        for (var i = 0; i < steps; i++) {
                            if (increment) scrollBar.increment();
                            else scrollBar.decrement();
                        }
                        return target;
                    }
                    return null;
                });
    }

    private static ActionResult scroll(
            final String query, final int amount, final int highlightMs, final String effect)
            throws Exception {
        return withFirstMatch(
                query,
                highlightMs,
                effect,
                node -> {
                    final var target = actionable(node);
                    if (target instanceof ScrollPane pane) {
                        pane.setVvalue(
                                Math.clamp(
                                        pane.getVvalue()
                                                + (pane.getVmax() - pane.getVmin())
                                                        * amount
                                                        / 100.0,
                                        pane.getVmin(),
                                        pane.getVmax()));
                        return target;
                    }
                    final var bounds = target.getBoundsInLocal();
                    final var screen = target.localToScreen(bounds);
                    if (screen == null) return null;
                    final var steps = Math.clamp(amount, -10000, 10000);
                    target.fireEvent(
                            new javafx.scene.input.ScrollEvent(
                                    javafx.scene.input.ScrollEvent.SCROLL,
                                    bounds.getCenterX(),
                                    bounds.getCenterY(),
                                    screen.getCenterX(),
                                    screen.getCenterY(),
                                    false,
                                    false,
                                    false,
                                    false,
                                    false,
                                    false,
                                    0,
                                    -steps * 40.0,
                                    0,
                                    -steps * 40.0,
                                    javafx.scene.input.ScrollEvent.HorizontalTextScrollUnits.NONE,
                                    0,
                                    javafx.scene.input.ScrollEvent.VerticalTextScrollUnits.LINES,
                                    -steps,
                                    0,
                                    new javafx.scene.input.PickResult(
                                            target, bounds.getCenterX(), bounds.getCenterY())));
                    return target;
                });
    }

    private record ListSelectionResult(int selected, int activated) {}

    private static ListSelectionResult selectListItem(
            final String query,
            final String text,
            final int index,
            final boolean activate,
            final boolean extend)
            throws Exception {
        return FxActions.call(
                () -> {
                    final var target = requireUniqueTarget(query, matchesFor(query));
                    if (!(target instanceof ListView<?> list)) return new ListSelectionResult(0, 0);
                    var selection = index;
                    if (selection < 0 && !text.isBlank()) {
                        final var exact = new ArrayList<Integer>();
                        final var containing = new ArrayList<Integer>();
                        for (var i = 0; i < list.getItems().size(); i++) {
                            final var value = String.valueOf(list.getItems().get(i));
                            if (value.equals(text)) exact.add(i);
                            if (value.contains(text)) containing.add(i);
                        }
                        final var matches = exact.isEmpty() ? containing : exact;
                        if (matches.size() > 1)
                            throw new IllegalArgumentException(
                                    "List item is ambiguous; specify index");
                        if (!matches.isEmpty()) selection = matches.getFirst();
                    }
                    if (selection < 0 || selection >= list.getItems().size())
                        return new ListSelectionResult(0, 0);
                    if (extend) list.getSelectionModel().select(selection);
                    else list.getSelectionModel().clearAndSelect(selection);
                    list.scrollTo(selection);
                    layoutNow(list);
                    final var selectedText = String.valueOf(list.getItems().get(selection));
                    return new ListSelectionResult(
                            1, activate ? activateListItem(list, selectedText, text) : 0);
                });
    }

    private static boolean selectionExtends(final String request) {
        final var mode = extractString(request, "selectionMode");
        if (mode.isBlank() || "replace".equals(mode)) return false;
        if ("extend".equals(mode)) return true;
        throw new IllegalArgumentException("selectionMode must be replace or extend");
    }

    private static int activateListItem(
            final ListView<?> listView, final String selectedText, final String requestedText) {
        final var needle = requestedText.isBlank() ? selectedText : requestedText;
        for (final var node : flatten(listView)) {
            if (!effectivelyVisible(node)) {
                continue;
            }
            final var text = textOf(node);
            if (!text.contains(needle) && !text.contains(selectedText)) {
                continue;
            }
            final var target = actionable(node);
            if (target instanceof ButtonBase button) {
                requireUniqueTarget("eid=n" + handle(button), List.of(button));
                button.fire();
                return 1;
            }
        }
        return 0;
    }

    private static ActionResult selectIndex(
            final String query,
            final int index,
            final int highlightMs,
            final String effect,
            final boolean extend)
            throws Exception {
        if (index < 0) {
            return ActionResult.miss();
        }
        return withFirstMatch(
                query,
                highlightMs,
                effect,
                node -> {
                    final var target = actionable(node);
                    return selectIndex(target, index, extend) ? target : null;
                });
    }

    private static boolean selectIndex(final Node node, final int index, final boolean extend) {
        if (node instanceof ListView<?> listView && index < listView.getItems().size()) {
            if (extend) listView.getSelectionModel().select(index);
            else listView.getSelectionModel().clearAndSelect(index);
            listView.scrollTo(index);
            layoutNow(listView);
            return true;
        }
        if (node instanceof TreeView<?> treeView && index < treeView.getExpandedItemCount()) {
            if (extend) treeView.getSelectionModel().select(index);
            else treeView.getSelectionModel().clearAndSelect(index);
            treeView.scrollTo(index);
            layoutNow(treeView);
            return true;
        }
        if (node instanceof TableView<?> tableView && index < tableView.getItems().size()) {
            if (extend) tableView.getSelectionModel().select(index);
            else tableView.getSelectionModel().clearAndSelect(index);
            tableView.scrollTo(index);
            layoutNow(tableView);
            return true;
        }
        if (node instanceof TreeTableView<?> treeTableView
                && index < treeTableView.getExpandedItemCount()) {
            if (extend) treeTableView.getSelectionModel().select(index);
            else treeTableView.getSelectionModel().clearAndSelect(index);
            treeTableView.scrollTo(index);
            layoutNow(treeTableView);
            return true;
        }
        if (node instanceof TabPane tabPane && index < tabPane.getTabs().size()) {
            tabPane.getSelectionModel().select(index);
            return true;
        }
        if (node instanceof Accordion accordion && index < accordion.getPanes().size()) {
            accordion.setExpandedPane(accordion.getPanes().get(index));
            return true;
        }
        return false;
    }

    private static ActionResult scrollToIndex(
            final String query, final int index, final int highlightMs, final String effect)
            throws Exception {
        if (index < 0) {
            return ActionResult.miss();
        }
        return withFirstMatch(
                query,
                highlightMs,
                effect,
                node -> {
                    final var target = actionable(node);
                    return scrollToIndex(target, index) ? target : null;
                });
    }

    private static boolean scrollToIndex(final Node node, final int index) {
        if (node instanceof ListView<?> listView && index < listView.getItems().size()) {
            listView.scrollTo(index);
            layoutNow(listView);
            return true;
        }
        if (node instanceof TreeView<?> treeView && index < treeView.getExpandedItemCount()) {
            treeView.scrollTo(index);
            layoutNow(treeView);
            return true;
        }
        if (node instanceof TableView<?> tableView && index < tableView.getItems().size()) {
            tableView.scrollTo(index);
            layoutNow(tableView);
            return true;
        }
        if (node instanceof TreeTableView<?> treeTableView
                && index < treeTableView.getExpandedItemCount()) {
            treeTableView.scrollTo(index);
            layoutNow(treeTableView);
            return true;
        }
        return false;
    }

    private static void layoutNow(final Control control) {
        control.applyCss();
        control.layout();
    }

    private static ActionResult expand(
            final String query,
            final int index,
            final boolean expanded,
            final int highlightMs,
            final String effect)
            throws Exception {
        return withFirstMatch(
                query,
                highlightMs,
                effect,
                node -> {
                    final var target = actionable(node);
                    return expand(target, index, expanded)
                                    || FxControls.performAccessible(
                                            target, expanded ? "expand" : "collapse", null)
                            ? target
                            : null;
                });
    }

    private static boolean expand(final Node node, final int index, final boolean expanded) {
        if (node instanceof TitledPane titledPane) {
            titledPane.setExpanded(expanded);
            return true;
        }
        if (node instanceof TreeView<?> treeView) {
            final var item = index >= 0 ? treeView.getTreeItem(index) : treeView.getRoot();
            if (item != null) {
                item.setExpanded(expanded);
                return true;
            }
        }
        if (node instanceof TreeTableView<?> treeTableView) {
            final var item =
                    index >= 0 ? treeTableView.getTreeItem(index) : treeTableView.getRoot();
            if (item != null) {
                item.setExpanded(expanded);
                return true;
            }
        }
        return false;
    }

    private record WebScriptResult(int count, String value, boolean truncated, String target) {}

    private static WebScriptResult webExecuteScript(
            final String query, final String script, final int highlightMs, final String effect)
            throws Exception {
        final var value = new java.util.concurrent.atomic.AtomicReference<>("");
        final var truncated = new java.util.concurrent.atomic.AtomicBoolean();
        final var result =
                withFirstMatch(
                        query,
                        highlightMs,
                        effect,
                        node -> {
                            if (!isWebView(node)) return null;
                            markSensitive(node, false);
                            final var returned = FxWeb.execute(node, script);
                            final var text =
                                    isSensitive(node) ? "[redacted]" : String.valueOf(returned);
                            truncated.set(text.length() > 512);
                            value.set(truncate(text, 512));
                            return node;
                        });
        return new WebScriptResult(
                result.count(), value.get(), truncated.get(), result.targetJson());
    }

    private record QuietResult(
            boolean ok, int quietMs, int timeoutMs, int polls, String signature) {
        String json() {
            return "{\"ok\":"
                    + ok
                    + ",\"quietMs\":"
                    + quietMs
                    + ",\"timeoutMs\":"
                    + timeoutMs
                    + ",\"polls\":"
                    + polls
                    + ",\"signature\":\""
                    + jsonEscape(signature)
                    + "\"}";
        }
    }

    private static QuietResult waitForQuiet(final int quietMs, final int timeoutMs)
            throws Exception {
        final var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        final var pollNanos =
                TimeUnit.MILLISECONDS.toNanos(Math.min(100, Math.max(25, quietMs / 4)));
        var lastSignature = "";
        var stableSince = System.nanoTime();
        var polls = 0;
        while (true) {
            var remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return new QuietResult(false, quietMs, timeoutMs, polls, lastSignature);
            }
            final String signature;
            try {
                signature = uiSignature(remaining);
            } catch (final TimeoutException exception) {
                return new QuietResult(false, quietMs, timeoutMs, polls, lastSignature);
            }
            polls++;
            final var now = System.nanoTime();
            if (deadline - now <= 0) {
                return new QuietResult(false, quietMs, timeoutMs, polls, lastSignature);
            }
            if (!signature.equals(lastSignature)) {
                lastSignature = signature;
                stableSince = now;
            } else if (TimeUnit.NANOSECONDS.toMillis(now - stableSince) >= quietMs) {
                return new QuietResult(true, quietMs, timeoutMs, polls, signature);
            }
            remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return new QuietResult(false, quietMs, timeoutMs, polls, lastSignature);
            }
            TimeUnit.NANOSECONDS.sleep(Math.min(pollNanos, remaining));
        }
    }

    private static String uiSignature(final long timeoutNanos) throws Exception {
        if (timeoutNanos <= 0) {
            throw new TimeoutException("Quiet-wait deadline exhausted");
        }
        final var result = new CompletableFuture<String>();
        Platform.runLater(
                () -> {
                    try {
                        final var out = new StringBuilder();
                        for (final var window : Window.getWindows()) {
                            if (!window.isShowing() || window.getScene() == null) {
                                continue;
                            }
                            out.append('w')
                                    .append(windowHandle(window))
                                    .append(':')
                                    .append(Math.round(window.getWidth()))
                                    .append('x')
                                    .append(Math.round(window.getHeight()))
                                    .append(':')
                                    .append(window.isFocused())
                                    .append(':')
                                    .append(window.getX())
                                    .append(',')
                                    .append(window.getY());
                            for (final var node : flatten(window.getScene().getRoot())) {
                                if (!effectivelyVisible(node)) {
                                    continue;
                                }
                                final var bounds = node.localToScene(node.getBoundsInLocal());
                                out.append('|')
                                        .append(handle(node))
                                        .append(':')
                                        .append(node.isDisabled())
                                        .append(':')
                                        .append(node.isFocused())
                                        .append(':')
                                        .append(Math.round(bounds.getMinX()))
                                        .append(',')
                                        .append(Math.round(bounds.getMinY()))
                                        .append(',')
                                        .append(Math.round(bounds.getWidth()))
                                        .append(',')
                                        .append(Math.round(bounds.getHeight()))
                                        .append(':')
                                        .append(textOf(node))
                                        .append(compactSemanticJson(node));
                            }
                        }
                        result.complete(
                                java.util.HexFormat.of()
                                        .formatHex(
                                                java.security.MessageDigest.getInstance("SHA-256")
                                                        .digest(
                                                                out.toString()
                                                                        .getBytes(
                                                                                StandardCharsets
                                                                                        .UTF_8))));
                    } catch (final Throwable throwable) {
                        result.completeExceptionally(throwable);
                    }
                });
        return result.get(timeoutNanos, TimeUnit.NANOSECONDS);
    }

    private static ActionResult withFirstMatch(
            final String query, final int highlightMs, final String effect, final NodeAction action)
            throws Exception {
        return withFirstMatch(query, highlightMs, effect, action, false);
    }

    private static ActionResult withFirstRawMatch(
            final String query, final int highlightMs, final String effect, final NodeAction action)
            throws Exception {
        return withFirstMatch(query, highlightMs, effect, action, true);
    }

    private static ActionResult withFirstMatch(
            final String query,
            final int highlightMs,
            final String effect,
            final NodeAction action,
            final boolean raw)
            throws Exception {
        return FxActions.call(
                () -> {
                    final var matches = actionMatches(query, raw);
                    final var node = requireUniqueTarget(query, matches);
                    markSensitive(node, false);
                    return actionResult(node == null ? null : action.apply(node), matches);
                },
                highlightMs,
                () -> {
                    if (highlightMs > 0) {
                        final var node = requireUniqueTarget(query, actionMatches(query, raw));
                        markSensitive(node, false);
                        clearHighlights();
                        if (node != null) {
                            highlightNode(node, effect);
                        }
                    }
                },
                () -> {
                    if (highlightMs > 0) {
                        clearHighlights();
                    }
                });
    }

    private static List<Node> actionMatches(final String query, final boolean raw) {
        return raw
                ? ranked(
                        new LinkedHashSet<>(
                                rawMatchesFor(query, Boolean.TRUE).stream()
                                        .map(FxDriverAgent::clickTarget)
                                        .toList()))
                : matchesFor(query);
    }

    private static Node requireUniqueTarget(final String query, final List<Node> matches) {
        if (matches.size() > 1) {
            throw new TargetFailure("AMBIGUOUS", query, matches);
        }
        if (matches.isEmpty()) {
            return null;
        }
        final var node = matches.getFirst();
        if (node.isDisabled()) {
            throw new TargetFailure("DISABLED", query, matches);
        }
        if (!effectivelyVisible(node)) {
            throw new TargetFailure("NOT_VISIBLE", query, matches);
        }
        if (modalBlocker(windowOf(node)) != null) {
            throw new TargetFailure("BLOCKED", query, matches);
        }
        return node;
    }

    private static final class TargetFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;
        private final String query;
        private final int matches;
        private final String candidates;

        private TargetFailure(final String code, final String query, final List<Node> matches) {
            super(
                    code.equals("AMBIGUOUS")
                            ? "Target is ambiguous; refine the selector or window"
                            : code.equals("READ_ONLY")
                                    ? "Text input is read-only"
                                    : code.equals("BLOCKED")
                                            ? "Target window is blocked by a modal window"
                                            : code.equals("NOT_VISIBLE")
                                                    ? "Target is not effectively visible"
                                                    : "Target is disabled");
            this.code = code;
            this.query = query;
            this.matches = matches.size();
            this.candidates =
                    String.join(
                            ",", matches.stream().limit(8).map(FxDriverAgent::targetJson).toList());
        }
    }

    private static ActionResult actionResult(final Node target, final List<Node> matches) {
        if (target == null) {
            return new ActionResult(0, "null", matches.size(), ambiguityJson(matches));
        }
        return new ActionResult(1, targetJson(target), matches.size(), ambiguityJson(matches));
    }

    @FunctionalInterface
    private interface NodeAction {
        Node apply(Node node);
    }

    private static String resultResponse(final String id, final String fields, final String request)
            throws Exception {
        final var result = new LinkedHashMap<String, Object>(Json.object("{" + fields + "}"));
        try {
            final var suffix = stateSuffix(request);
            if (!suffix.isEmpty()) result.putAll(Json.object("{" + suffix.substring(1) + "}"));
        } catch (final Throwable observationFailure) {
            final var problem =
                    Map.of(
                            "code",
                            "OBSERVATION_FAILED",
                            "type",
                            observationFailure.getClass().getSimpleName(),
                            "message",
                            "Invocation already returned; follow-up observation failed. Inspect"
                                    + " actual state before retrying.");
            result.put("stateError", problem);
            result.put("invocationReturned", true);
            if (!result.containsKey("error")) result.put("error", problem);
            result.put("ok", false);
        }
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + Json.stringify(result) + "}";
    }

    private record ActionResult(int count, String targetJson, int matches, String ambiguityJson) {
        static ActionResult miss() {
            return new ActionResult(0, "null", 0, "");
        }
    }

    private static String actionResponse(
            final String id,
            final String request,
            final String query,
            final String field,
            final ActionResult value)
            throws Exception {
        return resultResponse(
                id,
                actionFields(query, field, value)
                        + okOrError(
                                value.count() > 0, "NO_MATCH", "No actionable node matched query"),
                request);
    }

    private static String actionFields(
            final String query, final String field, final ActionResult value) {
        return "\"query\":\""
                + jsonEscape(query)
                + "\",\""
                + field
                + "\":"
                + value.count()
                + ",\"target\":"
                + value.targetJson()
                + ",\"matches\":"
                + value.matches()
                + value.ambiguityJson()
                + ",\"outcomeVerified\":false";
    }

    private static String targetJson(final Node node) {
        final var window = windowOf(node);
        return "{\"eid\":\"n"
                + handle(node)
                + "\",\"window\":\""
                + (window == null ? "" : "w" + windowHandle(window))
                + "\",\"selectorPath\":\""
                + jsonEscape(window == null ? "" : selectorPath(window, node))
                + "\",\"type\":\""
                + jsonEscape(node.getClass().getSimpleName())
                + "\",\"id\":\""
                + jsonEscape(node.getId() == null ? "" : node.getId())
                + "\",\"name\":\""
                + jsonEscape(accessibleNameOf(node))
                + "\",\"text\":\""
                + jsonEscape(truncate(textOf(node), 160))
                + "\",\"textTruncated\":"
                + (textOf(node).length() > 160)
                + ",\"focused\":"
                + node.isFocused()
                + interactionJson(node)
                + "}";
    }

    private static String ambiguityJson(final List<Node> matches) {
        if (matches.size() <= 1) {
            return "";
        }
        return ",\"ambiguity\":["
                + String.join(
                        ",", matches.stream().limit(8).map(FxDriverAgent::targetJson).toList())
                + "]";
    }

    private static String okOrError(final boolean ok, final String code, final String message) {
        return ",\"ok\":"
                + ok
                + (ok
                        ? ""
                        : ",\"error\":{\"code\":\""
                                + jsonEscape(code)
                                + "\",\"message\":\""
                                + jsonEscape(message)
                                + "\"}");
    }

    private static String errorResponse(
            final String id, final int jsonRpcCode, final String code, final String message) {
        return "{\"jsonrpc\":\"2.0\",\"id\":"
                + id
                + ",\"error\":{\"code\":"
                + jsonRpcCode
                + ",\"message\":\""
                + jsonEscape(message)
                + "\",\"data\":{\"code\":\""
                + jsonEscape(code)
                + "\"}}}";
    }

    private static String textActionResponse(
            final String id,
            final String request,
            final String query,
            final String field,
            final ActionResult value,
            final String text,
            final boolean replace)
            throws Exception {
        return resultResponse(
                id,
                "\"query\":\""
                        + jsonEscape(query)
                        + "\",\"text\":\"[redacted]\",\"payloadRedacted\":true,\"replace\":"
                        + replace
                        + ",\""
                        + field
                        + "\":"
                        + value.count()
                        + ",\"target\":"
                        + value.targetJson()
                        + ",\"matches\":"
                        + value.matches()
                        + value.ambiguityJson()
                        + okOrError(value.count() > 0, "NO_MATCH", "No text input matched query"),
                request);
    }

    private static String stateSuffix(final String request) throws Exception {
        if (request.isBlank() || !hasKey(request, "returnState")) {
            return "";
        }
        if (!extractBoolean(request, "returnState", true)) {
            return "";
        }
        if (extractBoolean(request, "afterLayout", false)) {
            final var scene =
                    onFxThread(() -> screenshotWindow(extractString(request, "window")).getScene());
            if (!FxWaits.nextLayout(scene, 5000))
                throw new TimeoutException("Layout pulse was not observed");
        }
        final var settleMs = Math.max(0, extractInt(request, "settleMs", 0));
        if (settleMs > 0) {
            Thread.sleep(settleMs);
        }
        final var mode = stateMode(request, "compact");
        final var limit = Math.max(1, extractInt(request, "limit", 80));
        return ",\"state\":" + compactState(mode, limit);
    }

    private static String stateMode(final String request, final String fallback) {
        final var mode = extractString(request, "mode");
        if (!mode.isBlank()) {
            return mode;
        }
        final var returnState = extractString(request, "returnState");
        return returnState.isBlank() ? fallback : returnState;
    }

    private static List<String> highlight(final String query, final String effect)
            throws Exception {
        final var result = new CompletableFuture<List<String>>();
        Platform.runLater(
                () -> {
                    try {
                        clearHighlights();
                        final var matches = new ArrayList<String>();
                        for (final var node : rawMatchesFor(query, Boolean.TRUE)) {
                            if (highlightNode(node, effect)) matches.add(describe(node));
                        }
                        result.complete(matches);
                    } catch (final Throwable throwable) {
                        result.completeExceptionally(throwable);
                    }
                });
        return result.get(5, TimeUnit.SECONDS);
    }

    private static List<Node> matchesFor(final String query) {
        return matchesFor(query, Boolean.TRUE);
    }

    private static List<Node> matchesFor(final String query, final Boolean visible) {
        final Set<Node> matches = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (final var node : rawMatchesFor(query, visible)) {
            matches.add(actionable(node));
        }
        return ranked(matches);
    }

    private static List<Node> rawMatchesFor(final String query, final Boolean visible) {
        final var matcher = matcherFor(query);
        final var anchor = handleAnchor(query);
        if (anchor != null) {
            final var node = nodeIds.resolve(anchor);
            final var window = node == null ? null : windowOf(node);
            return node != null
                            && window != null
                            && window.isShowing()
                            && (visible == null || visible == effectivelyVisible(node))
                            && matcher.test(node)
                    ? List.of(node)
                    : List.of();
        }
        final Set<Node> matches = Collections.newSetFromMap(new IdentityHashMap<>());
        for (final var window : Window.getWindows()) {
            if (!window.isShowing() || window.getScene() == null) continue;
            for (final var node : flatten(window.getScene().getRoot())) {
                if ((visible == null || visible == effectivelyVisible(node)) && matcher.test(node))
                    matches.add(node);
            }
        }
        return ranked(matches);
    }

    private static List<Node> ranked(final Set<Node> matches) {
        return matches.stream()
                .sorted((left, right) -> Integer.compare(score(right), score(left)))
                .toList();
    }

    private static int score(final Node node) {
        var score = 0;
        if (node instanceof ButtonBase) {
            score += 100;
        }
        if (node instanceof TextInputControl) {
            score += 90;
        }
        if (node instanceof Labeled) {
            score += 50;
        }
        if (!textOf(node).isBlank()) {
            score += 10;
        }
        return score;
    }

    private static Node actionable(final Node node) {
        Node current = node;
        while (current != null) {
            if (current instanceof ButtonBase
                    || current instanceof TextInputControl
                    || current instanceof ListView<?>
                    || current instanceof TreeView<?>
                    || current instanceof TableView<?>
                    || current instanceof TreeTableView<?>
                    || current instanceof ComboBoxBase<?>
                    || current instanceof ChoiceBox<?>
                    || current instanceof Spinner<?>
                    || current instanceof Slider
                    || current instanceof ScrollBar
                    || current instanceof TabPane
                    || current instanceof TitledPane
                    || current instanceof Accordion
                    || FxControls.candidateOperations(current).stream()
                            .anyMatch(op -> !"focus".equals(op))) {
                return current;
            }
            current = current.getParent();
        }
        return node;
    }

    private static List<Node> flatten(final Node root) {
        final var nodes = new ArrayList<Node>();
        flatten(root, nodes);
        return nodes;
    }

    private static List<Node> stateFlatten(final Node root) {
        final var nodes = new ArrayList<Node>();
        stateFlatten(root, nodes);
        return nodes;
    }

    private static void stateFlatten(final Node node, final List<Node> nodes) {
        nodes.add(node);
        if (stateTerminal(node)) {
            return;
        }
        if (node instanceof SubScene subScene && subScene.getRoot() != null) {
            stateFlatten(subScene.getRoot(), nodes);
        }
        if (node instanceof Parent parent) {
            for (final var child : parent.getChildrenUnmodifiable()) {
                stateFlatten(child, nodes);
            }
        }
    }

    private static boolean stateTerminal(final Node node) {
        return isWebView(node)
                || node instanceof ButtonBase
                || node instanceof TextInputControl
                || node instanceof ListView<?>
                || node instanceof TreeView<?>
                || node instanceof TableView<?>
                || node instanceof TreeTableView<?>
                || node instanceof ComboBoxBase<?>
                || node instanceof ChoiceBox<?>
                || node instanceof Spinner<?>
                || node instanceof Slider
                || node instanceof ScrollBar;
    }

    private static void flatten(final Node node, final List<Node> nodes) {
        nodes.add(node);
        if (node instanceof SubScene subScene && subScene.getRoot() != null) {
            flatten(subScene.getRoot(), nodes);
        }
        if (node instanceof Parent parent) {
            for (final var child : parent.getChildrenUnmodifiable()) {
                flatten(child, nodes);
            }
        }
    }

    private static java.util.function.Predicate<Node> matcherFor(final String query) {
        if (query.isEmpty()) return node -> false;
        if (query.startsWith("all=")) {
            final var filters = extractStringArray("{\"all\":" + query.substring(4) + "}", "all");
            final var matchers =
                    filters.stream()
                            .sorted(java.util.Comparator.comparingInt(FxDriverAgent::selectorCost))
                            .map(FxDriverAgent::matcherFor)
                            .toList();
            return node ->
                    !matchers.isEmpty() && matchers.stream().allMatch(filter -> filter.test(node));
        }
        final var anchor = handleAnchor(query);
        if (anchor != null) return node -> nodeIds.resolve(anchor) == node;
        if (query.startsWith("window=")) {
            final var value = query.substring(7);
            return node ->
                    windowOf(node) != null && value.equals("w" + windowHandle(windowOf(node)));
        }
        if (query.startsWith("=")) {
            final var value = query.substring(1);
            return node -> textOf(node).equals(value);
        }
        if (query.startsWith("text=")) {
            final var value = query.substring(5);
            return node -> textOf(node).contains(value);
        }
        if (query.startsWith("regexText=")) {
            final var pattern = Pattern.compile(query.substring(10));
            return node -> pattern.matcher(textOf(node)).find();
        }
        if (query.startsWith("type=")) {
            final var value = query.substring(5);
            return node -> typeMatches(node, value);
        }
        if (query.startsWith("role=")) {
            final var value = query.substring(5);
            return node ->
                    node.getAccessibleRole() != null
                            && node.getAccessibleRole().name().equalsIgnoreCase(value);
        }
        if (query.startsWith("accessible=")) {
            final var value = query.substring(11);
            return node -> accessibleNameOf(node).contains(value);
        }
        if (query.startsWith("#")) {
            final var value = query.substring(1);
            return node -> value.equals(node.getId());
        }
        if (query.startsWith(".")) {
            final var value = query.substring(1);
            return node -> node.getStyleClass().contains(value);
        }
        final var lower = query.toLowerCase(java.util.Locale.ROOT);
        return node ->
                typeMatches(node, query)
                        || node.getStyleClass().stream()
                                .anyMatch(style -> style.equalsIgnoreCase(query))
                        || textOf(node).toLowerCase(java.util.Locale.ROOT).contains(lower);
    }

    private static int selectorCost(final String query) {
        if (query.startsWith("@") || query.startsWith("eid=") || query.startsWith("#")) return 0;
        if (query.startsWith("window=") || query.startsWith("type=") || query.startsWith("role="))
            return 1;
        if (query.startsWith(".")) return 2;
        return 3;
    }

    private static Integer handleAnchor(final String query) {
        if (query.startsWith("all=")) {
            for (final var filter :
                    extractStringArray("{\"all\":" + query.substring(4) + "}", "all")) {
                final var anchor = handleAnchor(filter);
                if (anchor != null) return anchor;
            }
            return null;
        }
        final var value =
                query.startsWith("@")
                        ? query.substring(1)
                        : query.startsWith("eid=n")
                                ? query.substring(5)
                                : query.startsWith("eid=") ? query.substring(4) : null;
        if (value == null) return null;
        try {
            return Integer.valueOf(value);
        } catch (final NumberFormatException invalid) {
            return -1;
        }
    }

    private static boolean typeMatches(final Node node, final String type) {
        Class<?> current = node.getClass();
        while (current != null && Node.class.isAssignableFrom(current)) {
            if (current.getSimpleName().equalsIgnoreCase(type)) {
                return true;
            }
            current = current.getSuperclass();
        }
        return false;
    }

    private static boolean effectivelyVisible(final Node node) {
        Node root = node;
        for (Node current = node; current != null; current = current.getParent()) {
            if (!current.isVisible()) {
                return false;
            }
            root = current;
        }
        final var scene = node.getScene();
        if (scene == null || scene.getWindow() == null || !scene.getWindow().isShowing()) {
            return false;
        }
        if (root != scene.getRoot()) {
            for (final var candidate : flatten(scene.getRoot())) {
                if (candidate instanceof SubScene subScene && subScene.getRoot() == root) {
                    return effectivelyVisible(subScene);
                }
            }
            return false;
        }
        return true;
    }

    private record AccessibleName(String value, String source) {}

    private static String accessibleNameOf(final Node node) {
        return truncate(accessibleName(node).value(), 512);
    }

    private static AccessibleName accessibleName(final Node node) {
        if (isSensitive(node)) return new AccessibleName("", "redacted");
        final var explicit = accessibleTextOf(node);
        if (!explicit.isBlank()) return new AccessibleName(explicit, "accessibleText-property");
        final var label = node.queryAccessibleAttribute(AccessibleAttribute.LABELED_BY);
        if (label instanceof Node labelNode && !isSensitive(labelNode)) {
            if (labelNode instanceof Labeled labeled && !empty(labeled.getText())) {
                return new AccessibleName(labeled.getText(), "labeled-by");
            }
            if (labelNode instanceof Text text && !empty(text.getText())) {
                return new AccessibleName(text.getText(), "labeled-by");
            }
        }
        if (node instanceof Labeled labeled && !empty(labeled.getText()))
            return new AccessibleName(labeled.getText(), "control-text");
        if (node instanceof TextInputControl input && !empty(input.getPromptText()))
            return new AccessibleName(input.getPromptText(), "prompt");
        if (node instanceof Control control
                && control.getTooltip() != null
                && !empty(control.getTooltip().getText())) {
            return new AccessibleName(control.getTooltip().getText(), "tooltip");
        }
        if (!(node instanceof TextInputControl)
                && !Set.of(
                                "TEXT_FIELD",
                                "TEXT_AREA",
                                "PASSWORD_FIELD",
                                "COMBO_BOX",
                                "DATE_PICKER",
                                "SPINNER",
                                "SLIDER",
                                "SCROLL_BAR")
                        .contains(accessibleRoleOf(node))) {
            final var text = FxControls.accessibleText(node);
            if (text != null && !text.isBlank()) return new AccessibleName(text, "accessible-TEXT");
        }
        return new AccessibleName("", "none");
    }

    private static boolean isSensitive(final Node node) {
        if (node == null) return false;
        Node root = node;
        for (Node current = node; current != null; current = current.getParent()) {
            if (current instanceof PasswordField
                    || current.getAccessibleRole() == javafx.scene.AccessibleRole.PASSWORD_FIELD
                    || current.hasProperties()
                            && Boolean.TRUE.equals(current.getProperties().get(SENSITIVE)))
                return true;
            root = current;
        }
        final var scene = node.getScene();
        if (scene != null && root != scene.getRoot()) {
            for (final var candidate : flatten(scene.getRoot())) {
                if (candidate instanceof SubScene subScene && subScene.getRoot() == root)
                    return isSensitive(subScene);
            }
        }
        return false;
    }

    private static void markSensitive(final Node node, final boolean sensitive) {
        if (node == null) return;
        if (sensitive || FxActions.requestSensitive())
            node.getProperties().put(SENSITIVE, Boolean.TRUE);
        if (isSensitive(node)) FxActions.sensitive();
    }

    private static String accessibleTextOf(final Node node) {
        return isSensitive(node) || node.getAccessibleText() == null
                ? ""
                : node.getAccessibleText();
    }

    private static String tabName(final javafx.scene.control.Tab tab) {
        if (!empty(tab.getText())) return tab.getText();
        if (tab.getGraphic() != null) {
            for (final var node : flatten(tab.getGraphic())) {
                if (node instanceof Labeled label && !isSensitive(label) && !empty(label.getText()))
                    return label.getText();
            }
        }
        return "";
    }

    private static String accessibleRoleOf(final Node node) {
        return node.getAccessibleRole() == null ? "" : node.getAccessibleRole().name();
    }

    private static String textOf(final Node node) {
        if (isSensitive(node)) {
            return "[redacted]";
        }
        if (node instanceof TextInputControl input) {
            return (input.getPromptText() == null ? "" : input.getPromptText())
                    + " "
                    + (input.getText() == null ? "" : input.getText());
        }
        if (node instanceof Labeled labeled) {
            return labeled.getText() == null ? "" : labeled.getText();
        }
        if (node instanceof Text text && !hasLabeledAncestor(text)) {
            return text.getText() == null ? "" : text.getText();
        }
        final var accessible = FxControls.accessibleText(node);
        return accessible == null ? "" : accessible;
    }

    private static boolean hasLabeledAncestor(final Node node) {
        Node current = node.getParent();
        while (current != null) {
            if (current instanceof Labeled || current instanceof TextInputControl) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private static boolean highlightNode(final Node node, final String effect) {
        if (node.effectProperty().isBound()) return false;
        final var stored = node.getProperties().get(HIGHLIGHT_STATE);
        final var previous =
                stored instanceof HighlightState state ? state.previous() : node.getEffect();
        final var applied = highlightEffectFor(node, effect);
        // Node-owned state cannot keep a detached view alive through a static map.
        node.getProperties().put(HIGHLIGHT_STATE, new HighlightState(previous, applied));
        highlightedNodes.add(handle(node));
        node.setEffect(applied);
        return true;
    }

    private static Effect highlightEffectFor(final Node node, final String effect) {
        return switch (effect) {
            case "bloom" -> new Bloom(0.15);
            case "colorAdjust" -> {
                final var adjust = new ColorAdjust();
                adjust.setHue(-0.35);
                adjust.setSaturation(1.0);
                adjust.setBrightness(0.1);
                yield adjust;
            }
            case "glow" -> new Glow(0.85);
            default -> invertEffect(node);
        };
    }

    private static Effect invertEffect(final Node node) {
        final var bounds = node.getBoundsInLocal();
        final var width = Math.max(1.0, bounds.getWidth());
        final var height = Math.max(1.0, bounds.getHeight());
        final var blend = new Blend(BlendMode.DIFFERENCE);
        blend.setTopInput(
                new ColorInput(bounds.getMinX(), bounds.getMinY(), width, height, Color.WHITE));
        return blend;
    }

    private static void clearHighlights() {
        Throwable failure = null;
        final var ids = List.copyOf(highlightedNodes);
        highlightedNodes.clear();
        for (final var id : ids) {
            final var node = nodeIds.resolve(id);
            if (node == null || !node.hasProperties()) continue;
            final var stored = node.getProperties().remove(HIGHLIGHT_STATE);
            if (!(stored instanceof HighlightState state)
                    || node.effectProperty().isBound()
                    || node.getEffect() != state.applied()) continue;
            try {
                node.setEffect(state.previous());
            } catch (final Throwable problem) {
                if (failure == null) failure = problem;
                else if (failure != problem) failure.addSuppressed(problem);
            }
        }
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException exception) throw exception;
        if (failure != null) throw new IllegalStateException(failure);
    }

    private static void restoreTitles() {
        final var ids = List.copyOf(markedWindows);
        markedWindows.clear();
        for (final var id : ids) {
            final var window = windowIds.resolve(id);
            if (!(window instanceof Stage stage) || !stage.hasProperties()) continue;
            final var stored = stage.getProperties().remove(MARKED_TITLE);
            if (stored instanceof MarkedTitle title
                    && !stage.titleProperty().isBound()
                    && java.util.Objects.equals(stage.getTitle(), title.applied()))
                stage.setTitle(title.previous());
        }
    }

    private static String describe(final Node node) {
        final var id = node.getId() == null ? "" : "#" + node.getId();
        final var text = textOf(node);
        return node.getClass().getSimpleName()
                + id
                + (text.isBlank() ? "" : " text=\"" + text + "\"");
    }

    private static String selectorPath(final Window window, final Node node) {
        final var parts = new ArrayList<String>();
        Node current = node;
        while (current != null && parts.size() < 32) {
            parts.add(pathSegment(current));
            current = current.getParent();
        }
        Collections.reverse(parts);
        final var path =
                "window[" + showingWindowIndex(window) + "] > " + String.join(" > ", parts);
        return path.length() <= 2_048 ? path : path.substring(0, 2_047) + "…";
    }

    private static String pathSegment(final Node node) {
        final var semantic = semanticKind(node);
        final var out =
                new StringBuilder(semantic.isBlank() ? node.getClass().getSimpleName() : semantic);
        if (node.getId() != null && !node.getId().isBlank()) {
            out.append('#').append(pathEscape(node.getId()));
        } else {
            final var text = textOf(node).strip();
            if (!text.isBlank()) {
                out.append("[text=").append(pathEscape(text)).append(']');
            }
        }
        out.append('[').append(siblingIndex(node)).append(']');
        return out.toString();
    }

    private static String pathEscape(final String value) {
        return value.replace("\\", "\\\\").replace("]", "\\]").replace(">", "\\>");
    }

    private static int siblingIndex(final Node node) {
        final var parent = node.getParent();
        if (parent == null) {
            return 0;
        }
        var index = 0;
        for (final var child : parent.getChildrenUnmodifiable()) {
            if (child == node) {
                return index;
            }
            if (child.getClass().equals(node.getClass())) {
                index++;
            }
        }
        return index;
    }

    private static int showingWindowIndex(final Window target) {
        var index = 0;
        for (final var window : Window.getWindows()) {
            if (!window.isShowing() || window.getScene() == null) {
                continue;
            }
            if (window == target) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private static String windowContextJson(final Window window) {
        final var owner = ownerOf(window);
        final var blocker = modalBlocker(window);
        final var contained =
                javafx.stage.Screen.getScreens().stream()
                        .anyMatch(
                                screen ->
                                        screen.getBounds()
                                                .contains(
                                                        window.getX(),
                                                        window.getY(),
                                                        window.getWidth(),
                                                        window.getHeight()));
        final var headless = "headless".equalsIgnoreCase(System.getProperty("glass.platform", ""));
        return ",\"owner\":"
                + (owner == null ? "null" : "\"w" + windowHandle(owner) + "\"")
                + ",\"modality\":\""
                + (window instanceof Stage stage ? stage.getModality().name() : "NONE")
                + "\""
                + ",\"blockedBy\":"
                + (blocker == null ? "null" : "\"w" + windowHandle(blocker) + "\"")
                + ",\"withinSingleScreen\":"
                + contained
                + ",\"headlessGeometryRisk\":"
                + (headless && !contained);
    }

    private static Window ownerOf(final Window window) {
        return window instanceof Stage stage
                ? stage.getOwner()
                : window instanceof PopupWindow popup ? popup.getOwnerWindow() : null;
    }

    private static boolean ownedBy(final Window window, final Window ancestor) {
        for (Window current = ownerOf(window); current != null; current = ownerOf(current)) {
            if (current == ancestor) return true;
        }
        return false;
    }

    private static Window modalBlocker(final Window target) {
        if (target == null) return null;
        Window effectiveTarget = target;
        while (effectiveTarget instanceof PopupWindow popup) {
            effectiveTarget = popup.getOwnerWindow();
        }
        // JavaFX appends on show and removes on hide. Older modals cannot block a newer modal
        // branch.
        final var windows = new ArrayList<>(Window.getWindows());
        Collections.reverse(windows);
        for (final var window : windows) {
            if (!(window instanceof Stage modal)
                    || !modal.isShowing()
                    || modal.getModality() == javafx.stage.Modality.NONE
                    || (modal.getModality() == javafx.stage.Modality.WINDOW_MODAL
                            && modal.getOwner() == null)) continue;
            if (modal == effectiveTarget || ownedBy(effectiveTarget, modal)) return null;
            if (modal.getModality() == javafx.stage.Modality.APPLICATION_MODAL) return modal;
            for (Window owner = ownerOf(modal); owner != null; owner = ownerOf(owner)) {
                if (owner == effectiveTarget) return modal;
            }
        }
        return null;
    }

    private static List<Window> orderedWindows() {
        final var windows = new ArrayList<>(Window.getWindows());
        Collections.reverse(windows);
        windows.sort(
                java.util.Comparator.comparingInt(FxDriverAgent::windowDepth)
                        .reversed()
                        .thenComparing(
                                java.util.Comparator.comparing(Window::isFocused).reversed()));
        return windows;
    }

    private static int windowDepth(final Window window) {
        var depth = 0;
        Window current = window;
        while (depth < 32) {
            current =
                    current instanceof Stage stage
                            ? stage.getOwner()
                            : current instanceof PopupWindow popup ? popup.getOwnerWindow() : null;
            if (current == null) {
                break;
            }
            depth++;
        }
        return depth;
    }

    private static Window windowOf(final Node node) {
        if (node.getScene() != null && node.getScene().getWindow() != null) {
            return node.getScene().getWindow();
        }
        for (final var window : Window.getWindows()) {
            if (window.isShowing()
                    && window.getScene() != null
                    && flatten(window.getScene().getRoot()).contains(node)) {
                return window;
            }
        }
        return null;
    }

    private static String windowJson(final Window window) {
        final var title =
                window instanceof Stage stage && stage.getTitle() != null ? stage.getTitle() : "";
        return "{\"wid\":\"w"
                + windowHandle(window)
                + "\",\"kind\":\""
                + jsonEscape(window.getClass().getSimpleName())
                + "\",\"title\":\""
                + jsonEscape(title)
                + "\",\"focused\":"
                + window.isFocused()
                + ",\"showing\":"
                + window.isShowing()
                + windowContextJson(window)
                + ",\"bounds\":{"
                + boundsJson(
                        Math.round(window.getX()),
                        Math.round(window.getY()),
                        Math.round(window.getWidth()),
                        Math.round(window.getHeight()))
                + "}";
    }

    private static String nodeJson(final Window window, final Node node) {
        final var bounds = screenBounds(node);
        final var parent = node.getParent();
        final var actionable = hasKnownOperations(node);
        return "{\"handle\":"
                + handle(node)
                + ",\"eid\":\"n"
                + handle(node)
                + "\",\"parent\":"
                + (parent == null ? "null" : "\"n" + handle(parent) + "\"")
                + ",\"window\":\"w"
                + windowHandle(window)
                + "\",\"type\":\""
                + jsonEscape(node.getClass().getSimpleName())
                + "\",\"id\":\""
                + jsonEscape(node.getId() == null ? "" : node.getId())
                + "\",\"nodeId\":\""
                + jsonEscape(node.getId() == null ? "" : node.getId())
                + "\",\"text\":\""
                + jsonEscape(textOf(node))
                + "\",\"accessibleText\":\""
                + jsonEscape(accessibleTextOf(node))
                + "\",\"name\":\""
                + jsonEscape(accessibleNameOf(node))
                + "\",\"accessibleHelp\":\""
                + jsonEscape(
                        isSensitive(node) || node.getAccessibleHelp() == null
                                ? ""
                                : node.getAccessibleHelp())
                + "\",\"accessibleRole\":\""
                + jsonEscape(accessibleRoleOf(node))
                + "\",\"accessibleRoleDescription\":\""
                + jsonEscape(
                        isSensitive(node) || node.getAccessibleRoleDescription() == null
                                ? ""
                                : node.getAccessibleRoleDescription())
                + "\",\"visible\":"
                + effectivelyVisible(node)
                + ",\"disabled\":"
                + node.isDisabled()
                + ",\"focused\":"
                + node.isFocused()
                + ",\"managed\":"
                + node.isManaged()
                + ",\"mouseTransparent\":"
                + node.isMouseTransparent()
                + ",\"pickOnBounds\":"
                + node.isPickOnBounds()
                + ",\"opacity\":"
                + jsonNumber(node.getOpacity())
                + ",\"cursor\":\""
                + jsonEscape(node.getCursor() == null ? "" : node.getCursor().toString())
                + "\""
                + ",\"actionable\":"
                + actionable
                + ",\"x\":"
                + Math.round(bounds.getMinX())
                + ",\"y\":"
                + Math.round(bounds.getMinY())
                + ",\"w\":"
                + Math.round(bounds.getWidth())
                + ",\"h\":"
                + Math.round(bounds.getHeight())
                + interactionJson(node)
                + ",\"bounds\":{"
                + boundsJson(bounds)
                + "}"
                + ",\"screenBounds\":{"
                + boundsJson(bounds)
                + "}"
                + ",\"boundsInLocal\":{"
                + boundsJson(node.getBoundsInLocal())
                + "}"
                + ",\"layoutBounds\":{"
                + boundsJson(node.getLayoutBounds())
                + "}"
                + ",\"styleClasses\":["
                + jsonStrings(node.getStyleClass())
                + "]"
                + semanticJson(node)
                + "}";
    }

    private static Bounds screenBounds(final Node node) {
        final var bounds = node.localToScreen(node.getBoundsInLocal());
        return bounds == null ? node.localToScene(node.getBoundsInLocal()) : bounds;
    }

    private static String boundsJson(final long x, final long y, final long w, final long h) {
        return "\"x\":" + x + ",\"y\":" + y + ",\"w\":" + w + ",\"h\":" + h;
    }

    private static String scale(final int imageSize, final int sourceSize) {
        return sourceSize <= 0
                ? "1.0000"
                : String.format(java.util.Locale.ROOT, "%.4f", (double) imageSize / sourceSize);
    }

    private static String boundsJson(final Bounds bounds) {
        return boundsJson(
                Math.round(bounds.getMinX()),
                Math.round(bounds.getMinY()),
                Math.round(bounds.getWidth()),
                Math.round(bounds.getHeight()));
    }

    private static String semanticJson(final Node node) {
        if (isSensitive(node) && !(node instanceof TextInputControl)) {
            return ",\"semantic\":{\"kind\":\"" + semanticKind(node) + "\",\"masked\":true}";
        }
        final var fields = new ArrayList<String>();
        addString(fields, "kind", semanticKind(node));
        addSelectionFields(fields, node);
        if (node instanceof Control control) {
            addString(
                    fields,
                    "tooltip",
                    control.getTooltip() == null || isSensitive(node)
                            ? ""
                            : control.getTooltip().getText());
            fields.add("\"contextMenu\":" + (control.getContextMenu() != null));
            fields.add(
                    "\"contextMenuShowing\":"
                            + (control.getContextMenu() != null
                                    && control.getContextMenu().isShowing()));
            addString(
                    fields,
                    "skinClass",
                    control.getSkin() == null ? "" : control.getSkin().getClass().getName());
        }
        if (node instanceof Labeled labeled) {
            addString(fields, "text", labeled.getText() == null ? "" : labeled.getText());
            addString(
                    fields,
                    "graphicType",
                    labeled.getGraphic() == null
                            ? ""
                            : labeled.getGraphic().getClass().getSimpleName());
            addString(
                    fields,
                    "contentDisplay",
                    labeled.getContentDisplay() == null ? "" : labeled.getContentDisplay().name());
            fields.add("\"mnemonicParsing\":" + labeled.isMnemonicParsing());
            fields.add("\"wrapText\":" + labeled.isWrapText());
            addString(
                    fields,
                    "textOverrun",
                    labeled.getTextOverrun() == null ? "" : labeled.getTextOverrun().name());
        }
        if (node instanceof ButtonBase button) {
            fields.add("\"armed\":" + button.isArmed());
            if (button instanceof Button normalButton) {
                fields.add("\"defaultButton\":" + normalButton.isDefaultButton());
                fields.add("\"cancelButton\":" + normalButton.isCancelButton());
            }
            if (button instanceof Hyperlink hyperlink) {
                fields.add("\"visited\":" + hyperlink.isVisited());
            }
        }
        if (node instanceof Toggle toggle) {
            fields.add("\"selected\":" + toggle.isSelected());
            addString(
                    fields,
                    "toggleGroup",
                    toggle.getToggleGroup() == null
                            ? ""
                            : "tg" + System.identityHashCode(toggle.getToggleGroup()));
        }
        if (node instanceof CheckBox checkBox) {
            fields.add("\"selected\":" + checkBox.isSelected());
            fields.add("\"indeterminate\":" + checkBox.isIndeterminate());
            fields.add("\"allowIndeterminate\":" + checkBox.isAllowIndeterminate());
        }
        if (node instanceof TextInputControl input) {
            final var masked = isSensitive(input);
            final var value = masked ? "[redacted]" : input.getText();
            addString(fields, "value", value == null ? "" : value);
            addString(
                    fields,
                    "prompt",
                    masked || input.getPromptText() == null ? "" : input.getPromptText());
            fields.add("\"editable\":" + input.isEditable());
            if (!masked) {
                addString(fields, "selectedText", input.getSelectedText());
                fields.add("\"selectionStart\":" + input.getSelection().getStart());
                fields.add("\"selectionEnd\":" + input.getSelection().getEnd());
                fields.add("\"caretPosition\":" + input.getCaretPosition());
                fields.add("\"anchor\":" + input.getAnchor());
                fields.add("\"length\":" + input.getLength());
            }
            fields.add("\"masked\":" + masked);
            if (input instanceof TextField textField) {
                fields.add("\"prefColumnCount\":" + textField.getPrefColumnCount());
            }
            if (input instanceof TextArea textArea) {
                fields.add("\"prefColumnCount\":" + textArea.getPrefColumnCount());
                fields.add("\"prefRowCount\":" + textArea.getPrefRowCount());
                fields.add("\"wrapText\":" + textArea.isWrapText());
                fields.add("\"scrollTop\":" + jsonNumber(textArea.getScrollTop()));
                fields.add("\"scrollLeft\":" + jsonNumber(textArea.getScrollLeft()));
            }
        }
        if (node instanceof ChoiceBox<?> choiceBox) {
            fields.addAll(modelValueFields(choiceBox));
            fields.add("\"itemsCount\":" + itemCount(choiceBox.getItems()));
            fields.add("\"selectedIndex\":" + selectedIndex(node));
            addString(fields, "selectedItem", String.valueOf(selectedItem(node)));
            fields.add("\"showing\":" + choiceBox.isShowing());
        }
        if (node instanceof ComboBoxBase<?> comboBoxBase) {
            fields.addAll(modelValueFields(comboBoxBase));
            fields.add("\"showing\":" + comboBoxBase.isShowing());
            fields.add("\"editable\":" + comboBoxBase.isEditable());
            addString(fields, "converterClass", converterClass(comboBoxBase));
            if (comboBoxBase instanceof ComboBox<?> comboBox) {
                fields.add("\"itemsCount\":" + itemCount(comboBox.getItems()));
                fields.add("\"selectedIndex\":" + selectedIndex(node));
                addString(fields, "selectedItem", String.valueOf(selectedItem(node)));
            }
            if (comboBoxBase instanceof DatePicker datePicker && datePicker.getValue() != null) {
                addString(fields, "localDate", datePicker.getValue().toString());
            }
            if (comboBoxBase instanceof ColorPicker colorPicker && colorPicker.getValue() != null) {
                addString(fields, "color", colorString(colorPicker.getValue()));
            }
        }
        if (node instanceof Spinner<?> spinner) {
            fields.addAll(modelValueFields(spinner));
            fields.add("\"editable\":" + spinner.isEditable());
            addString(
                    fields,
                    "valueFactoryClass",
                    spinner.getValueFactory() == null
                            ? ""
                            : spinner.getValueFactory().getClass().getName());
        }
        if (node instanceof Slider slider) {
            fields.add("\"min\":" + jsonNumber(slider.getMin()));
            fields.add("\"max\":" + jsonNumber(slider.getMax()));
            fields.add("\"value\":" + jsonNumber(slider.getValue()));
            fields.add("\"majorTickUnit\":" + jsonNumber(slider.getMajorTickUnit()));
            fields.add("\"minorTickCount\":" + slider.getMinorTickCount());
            fields.add("\"showTickMarks\":" + slider.isShowTickMarks());
            fields.add("\"showTickLabels\":" + slider.isShowTickLabels());
            fields.add("\"snapToTicks\":" + slider.isSnapToTicks());
        }
        if (node instanceof ScrollBar scrollBar) {
            fields.add("\"min\":" + jsonNumber(scrollBar.getMin()));
            fields.add("\"max\":" + jsonNumber(scrollBar.getMax()));
            fields.add("\"value\":" + jsonNumber(scrollBar.getValue()));
            fields.add("\"visibleAmount\":" + jsonNumber(scrollBar.getVisibleAmount()));
            fields.add("\"unitIncrement\":" + jsonNumber(scrollBar.getUnitIncrement()));
            fields.add("\"blockIncrement\":" + jsonNumber(scrollBar.getBlockIncrement()));
            addString(fields, "orientation", scrollBar.getOrientation().name());
        }
        if (node instanceof ListView<?> listView) {
            fields.add("\"itemsCount\":" + itemCount(listView.getItems()));
            fields.add("\"selectionMode\":\"" + selectionMode(node) + "\"");
            fields.add("\"selectedIndices\":" + jsonInts(selectedIndices(node)));
            fields.add("\"selectedItems\":" + selectedItemsJson(node, 20));
            fields.add("\"focusedIndex\":" + focusedIndex(node));
            fields.add("\"editable\":" + listView.isEditable());
            fields.add("\"fixedCellSize\":" + jsonNumber(listView.getFixedCellSize()));
            addString(
                    fields,
                    "orientation",
                    listView.getOrientation() == null ? "" : listView.getOrientation().name());
            addString(
                    fields,
                    "placeholderText",
                    listView.getPlaceholder() == null ? "" : textOf(listView.getPlaceholder()));
        }
        if (node instanceof TreeView<?> treeView) {
            fields.add("\"itemsCount\":" + treeView.getExpandedItemCount());
            fields.add("\"expandedItemCount\":" + treeView.getExpandedItemCount());
            fields.add("\"selectionMode\":\"" + selectionMode(node) + "\"");
            fields.add("\"selectedIndices\":" + jsonInts(selectedIndices(node)));
            fields.add("\"selectedItems\":" + selectedItemsJson(node, 20));
            fields.add("\"focusedIndex\":" + focusedIndex(node));
            fields.add("\"editable\":" + treeView.isEditable());
            fields.add("\"fixedCellSize\":" + jsonNumber(treeView.getFixedCellSize()));
            fields.add("\"rootVisible\":" + treeView.isShowRoot());
            addString(fields, "rootValue", treeItemValue(treeView.getRoot()));
        }
        if (node instanceof TableView<?> tableView) {
            fields.add("\"itemsCount\":" + itemCount(tableView.getItems()));
            fields.add("\"selectionMode\":\"" + selectionMode(node) + "\"");
            fields.add("\"selectedIndices\":" + jsonInts(selectedIndices(node)));
            fields.add("\"selectedItems\":" + selectedItemsJson(node, 20));
            fields.add("\"focusedIndex\":" + focusedIndex(node));
            addString(
                    fields,
                    "focusedCell",
                    String.valueOf(
                            tableView.getFocusModel() == null
                                    ? null
                                    : tableView.getFocusModel().getFocusedCell()));
            fields.add("\"editable\":" + tableView.isEditable());
            fields.add("\"fixedCellSize\":" + jsonNumber(tableView.getFixedCellSize()));
            addString(
                    fields,
                    "placeholderText",
                    tableView.getPlaceholder() == null ? "" : textOf(tableView.getPlaceholder()));
            fields.add("\"columns\":" + jsonColumns(tableView.getVisibleLeafColumns()));
            fields.add("\"columnsCount\":" + tableView.getVisibleLeafColumns().size());
            fields.add("\"columnsTruncated\":" + (tableView.getVisibleLeafColumns().size() > 100));
            fields.add("\"sortOrder\":" + jsonColumns(tableView.getSortOrder()));
        }
        if (node instanceof TreeTableView<?> treeTableView) {
            fields.add("\"itemsCount\":" + treeTableView.getExpandedItemCount());
            fields.add("\"expandedItemCount\":" + treeTableView.getExpandedItemCount());
            fields.add("\"selectionMode\":\"" + selectionMode(node) + "\"");
            fields.add("\"selectedIndices\":" + jsonInts(selectedIndices(node)));
            fields.add("\"selectedItems\":" + selectedItemsJson(node, 20));
            fields.add("\"focusedIndex\":" + focusedIndex(node));
            addString(
                    fields,
                    "focusedCell",
                    String.valueOf(
                            treeTableView.getFocusModel() == null
                                    ? null
                                    : treeTableView.getFocusModel().getFocusedCell()));
            fields.add("\"editable\":" + treeTableView.isEditable());
            fields.add("\"fixedCellSize\":" + jsonNumber(treeTableView.getFixedCellSize()));
            fields.add("\"rootVisible\":" + treeTableView.isShowRoot());
            addString(fields, "rootValue", treeItemValue(treeTableView.getRoot()));
            addString(
                    fields,
                    "placeholderText",
                    treeTableView.getPlaceholder() == null
                            ? ""
                            : textOf(treeTableView.getPlaceholder()));
            fields.add("\"columns\":" + jsonColumns(treeTableView.getVisibleLeafColumns()));
            fields.add("\"columnsCount\":" + treeTableView.getVisibleLeafColumns().size());
            fields.add(
                    "\"columnsTruncated\":" + (treeTableView.getVisibleLeafColumns().size() > 100));
            fields.add("\"sortOrder\":" + jsonColumns(treeTableView.getSortOrder()));
        }
        if (node instanceof TabPane tabPane) {
            fields.add("\"tabsCount\":" + tabPane.getTabs().size());
            fields.add("\"selectedIndex\":" + selectedIndex(node));
            if (selectedItem(node) != null) {
                addString(
                        fields,
                        "selectedTabText",
                        tabName((javafx.scene.control.Tab) selectedItem(node)));
                addString(
                        fields,
                        "selectedTabId",
                        ((javafx.scene.control.Tab) selectedItem(node)).getId());
            }
            addString(fields, "tabClosingPolicy", tabPane.getTabClosingPolicy().name());
            addString(fields, "side", tabPane.getSide().name());
        }
        if (node instanceof Accordion accordion) {
            fields.add("\"paneCount\":" + accordion.getPanes().size());
            fields.add(
                    "\"expandedPaneIndex\":"
                            + accordion.getPanes().indexOf(accordion.getExpandedPane()));
            if (accordion.getExpandedPane() != null) {
                addString(fields, "expandedPaneText", accordion.getExpandedPane().getText());
            }
        }
        if (node instanceof TitledPane titledPane) {
            fields.add("\"expanded\":" + titledPane.isExpanded());
            fields.add("\"collapsible\":" + titledPane.isCollapsible());
            fields.add("\"animated\":" + titledPane.isAnimated());
            addString(
                    fields,
                    "content",
                    titledPane.getContent() == null ? "" : "n" + handle(titledPane.getContent()));
        }
        if (node instanceof Pagination pagination) {
            fields.add("\"pageCount\":" + pagination.getPageCount());
            fields.add("\"currentPageIndex\":" + pagination.getCurrentPageIndex());
            fields.add("\"maxPageIndicatorCount\":" + pagination.getMaxPageIndicatorCount());
        }
        if (node instanceof SplitPane splitPane) {
            addString(fields, "orientation", splitPane.getOrientation().name());
            fields.add("\"itemCount\":" + splitPane.getItems().size());
            fields.add("\"dividerPositions\":" + jsonDoubles(splitPane.getDividerPositions()));
        }
        if (node instanceof ScrollPane scrollPane) {
            fields.add("\"hvalue\":" + jsonNumber(scrollPane.getHvalue()));
            fields.add("\"vvalue\":" + jsonNumber(scrollPane.getVvalue()));
            fields.add("\"hmin\":" + jsonNumber(scrollPane.getHmin()));
            fields.add("\"hmax\":" + jsonNumber(scrollPane.getHmax()));
            fields.add("\"vmin\":" + jsonNumber(scrollPane.getVmin()));
            fields.add("\"vmax\":" + jsonNumber(scrollPane.getVmax()));
            fields.add("\"fitToWidth\":" + scrollPane.isFitToWidth());
            fields.add("\"fitToHeight\":" + scrollPane.isFitToHeight());
            fields.add("\"pannable\":" + scrollPane.isPannable());
            fields.add("\"viewportBounds\":{" + boundsJson(scrollPane.getViewportBounds()) + "}");
            addString(
                    fields,
                    "content",
                    scrollPane.getContent() == null ? "" : "n" + handle(scrollPane.getContent()));
        }
        if (node instanceof ToolBar toolBar) {
            addString(fields, "orientation", toolBar.getOrientation().name());
            fields.add("\"itemCount\":" + toolBar.getItems().size());
            fields.add("\"items\":" + jsonNodeIds(toolBar.getItems()));
        }
        if (isWebView(node)) {
            addWebViewFields(fields, node);
        }
        if (node instanceof Text text) {
            addString(fields, "textNodeText", text.getText() == null ? "" : text.getText());
            fields.add("\"wrappingWidth\":" + jsonNumber(text.getWrappingWidth()));
        }
        return fields.isEmpty() ? "" : ",\"semantic\":{" + String.join(",", fields) + "}";
    }

    private static String semanticKind(final Node node) {
        if (isWebView(node)) {
            return "webView";
        }
        if (node instanceof TabPane) {
            return "tabs";
        }
        if (node instanceof Accordion) {
            return "accordion";
        }
        if (node instanceof TitledPane) {
            return "titledPane";
        }
        if (node instanceof Pagination) {
            return "pagination";
        }
        if (node instanceof SplitPane) {
            return "splitPane";
        }
        if (node instanceof ScrollPane) {
            return "scrollPane";
        }
        if (node instanceof ToolBar) {
            return "toolBar";
        }
        if (node instanceof ChoiceBox<?>) {
            return "choice";
        }
        if (node instanceof ComboBox<?>) {
            return "combo";
        }
        if (node instanceof DatePicker) {
            return "date";
        }
        if (node instanceof ColorPicker) {
            return "color";
        }
        if (node instanceof Spinner<?>) {
            return "spinner";
        }
        if (node instanceof Slider) {
            return "range";
        }
        if (node instanceof ScrollBar) {
            return "scrollbar";
        }
        if (node instanceof ListView<?>) {
            return "list";
        }
        if (node instanceof TreeView<?>) {
            return "tree";
        }
        if (node instanceof TableView<?>) {
            return "table";
        }
        if (node instanceof TreeTableView<?>) {
            return "treeTable";
        }
        if (node instanceof PasswordField) {
            return "textInput";
        }
        if (node instanceof TextInputControl) {
            return "textInput";
        }
        if (node instanceof CheckBox) {
            return "checkbox";
        }
        if (node instanceof RadioButton) {
            return "radio";
        }
        if (node instanceof Hyperlink) {
            return "hyperlink";
        }
        if (node instanceof ToggleButton) {
            return "toggle";
        }
        if (node instanceof ButtonBase) {
            return "button";
        }
        if (node instanceof Labeled) {
            return "labeled";
        }
        if (node instanceof Text) {
            return "text";
        }
        if (node instanceof Control) {
            return "control";
        }
        return "";
    }

    private static String converterClass(final Node control) {
        final Object converter;
        if (control instanceof ComboBox<?> combo) converter = combo.getConverter();
        else if (control instanceof DatePicker date) converter = date.getConverter();
        else return "";
        return converter == null ? "" : converter.getClass().getName();
    }

    private static String colorString(final Color color) {
        return String.format(
                java.util.Locale.ROOT,
                "rgba(%d,%d,%d,%.4f)",
                Math.round((float) (color.getRed() * 255.0)),
                Math.round((float) (color.getGreen() * 255.0)),
                Math.round((float) (color.getBlue() * 255.0)),
                color.getOpacity());
    }

    private static boolean isWebView(final Node node) {
        return node.getClass().getName().equals("javafx.scene.web.WebView");
    }

    private static void addWebViewFields(final List<String> fields, final Node node) {
        final var json = Json.stringify(FxWeb.state(node));
        fields.add(json.substring(1, json.length() - 1));
    }

    private static String jsonDoubles(final double[] values) {
        final var out = new StringBuilder();
        out.append('[');
        for (var i = 0; i < values.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(jsonNumber(values[i]));
        }
        out.append(']');
        return out.toString();
    }

    private static String jsonNodeIds(final List<? extends Node> values) {
        final var out = new StringBuilder();
        out.append('[');
        for (var i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append('"').append('n').append(handle(values.get(i))).append('"');
        }
        out.append(']');
        return out.toString();
    }

    private static int itemCount(final List<?> items) {
        return items == null ? 0 : items.size();
    }

    private static List<Integer> selectedIndices(final Node node) {
        final var model = multipleSelection(node);
        return model == null || model.getSelectedIndices() == null
                ? List.of()
                : model.getSelectedIndices();
    }

    private static int selectedIndex(final Node node) {
        final var multiple = multipleSelection(node);
        final var single = singleSelection(node);
        return multiple != null
                ? multiple.getSelectedIndex()
                : single == null ? -1 : single.getSelectedIndex();
    }

    private static Object selectedItem(final Node node) {
        final var multiple = multipleSelection(node);
        final var single = singleSelection(node);
        return multiple != null
                ? multiple.getSelectedItem()
                : single == null ? null : single.getSelectedItem();
    }

    private static String selectionMode(final Node node) {
        final var model = multipleSelection(node);
        return model == null ? "NONE" : model.getSelectionMode().name();
    }

    private static int focusedIndex(final Node node) {
        javafx.scene.control.FocusModel<?> model = null;
        if (node instanceof ListView<?> list) model = list.getFocusModel();
        if (node instanceof TableView<?> table) model = table.getFocusModel();
        if (node instanceof TreeView<?> tree) model = tree.getFocusModel();
        if (node instanceof TreeTableView<?> tree) model = tree.getFocusModel();
        return model == null ? -1 : model.getFocusedIndex();
    }

    private static List<?> selectedItems(final Node node) {
        final var model = multipleSelection(node);
        return model == null || model.getSelectedItems() == null
                ? List.of()
                : model.getSelectedItems();
    }

    private static String selectionText(final Node node, final Object item) {
        return (node instanceof TreeView<?> || node instanceof TreeTableView<?>)
                        && item instanceof TreeItem<?> tree
                ? treeItemValue(tree)
                : String.valueOf(item);
    }

    private static String selectedItemsJson(final Node node, final int limit) {
        return "["
                + jsonStrings(
                        selectedItems(node).stream()
                                .limit(limit)
                                .map(item -> truncate(selectionText(node, item), 512))
                                .toList())
                + "]";
    }

    private static void addSelectionFields(final List<String> fields, final Node node) {
        if (!(node instanceof ListView<?>
                || node instanceof TableView<?>
                || node instanceof TreeView<?>
                || node instanceof TreeTableView<?>)) return;
        final var indices = selectedIndices(node);
        final var items = selectedItems(node);
        fields.add("\"selectionAvailable\":" + (multipleSelection(node) != null));
        fields.add("\"selectedCount\":" + indices.size());
        fields.add("\"selectedIndicesTruncated\":" + (indices.size() > 100));
        fields.add(
                "\"selectedItemsTruncated\":"
                        + (items.size() > 20
                                || items.stream()
                                        .limit(20)
                                        .anyMatch(
                                                item -> selectionText(node, item).length() > 512)));
        fields.add("\"selectedItemsRepresentation\":\"model-string\"");
    }

    private static String jsonInts(final List<Integer> values) {
        final var out = new StringBuilder();
        out.append('[');
        for (var i = 0; i < values.size() && i < 100; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(values.get(i));
        }
        out.append(']');
        return out.toString();
    }

    private static String treeItemValue(final TreeItem<?> item) {
        return item == null ? "" : String.valueOf(item.getValue());
    }

    private static String jsonColumns(final List<? extends TableColumnBase<?, ?>> columns) {
        return jsonColumns(columns, 0, 100);
    }

    private static String jsonColumns(
            final List<? extends TableColumnBase<?, ?>> columns,
            final int offset,
            final int limit) {
        final var out = new StringBuilder();
        out.append('[');
        for (var i = offset; i < columns.size() && i < offset + limit; i++) {
            if (i > offset) {
                out.append(',');
            }
            final var column = columns.get(i);
            out.append('{')
                    .append("\"index\":")
                    .append(i)
                    .append(",\"id\":\"")
                    .append(jsonEscape(column.getId() == null ? "" : column.getId()))
                    .append('"')
                    .append(",\"text\":\"")
                    .append(
                            jsonEscape(
                                    truncate(
                                            column.getText() == null ? "" : column.getText(), 512)))
                    .append('"')
                    .append(",\"path\":\"")
                    .append(jsonEscape(truncate(columnPath(column), 512)))
                    .append('"')
                    .append(",\"visible\":")
                    .append(column.isVisible())
                    .append(",\"sortable\":")
                    .append(column.isSortable())
                    .append(",\"width\":")
                    .append(jsonNumber(column.getWidth()))
                    .append('}');
        }
        out.append(']');
        return out.toString();
    }

    private static String columnPath(final TableColumnBase<?, ?> column) {
        final var parts = new ArrayList<String>();
        for (TableColumnBase<?, ?> current = column;
                current != null;
                current = current.getParentColumn()) {
            parts.addFirst(columnName(current));
        }
        return String.join(" > ", parts);
    }

    private static void addString(final List<String> fields, final String key, final String value) {
        if (value != null
                && (!value.isBlank()
                        || Set.of("value", "displayValue", "editorText", "selectedText")
                                .contains(key))) {
            fields.add("\"" + jsonEscape(key) + "\":\"" + jsonEscape(value) + "\"");
        }
    }

    private static String jsonNumber(final double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "null";
        }
        return Double.toString(value);
    }

    private static int handle(final Node node) {
        return nodeIds.id(node);
    }

    private static int windowHandle(final Window window) {
        return windowIds.id(window);
    }

    private static int markStages() throws Exception {
        return onFxThread(
                () -> {
                    markedWindows.removeIf(id -> windowIds.resolve(id) == null);
                    var count = 0;
                    for (final var window : Window.getWindows()) {
                        if (!(window instanceof Stage stage)
                                || !stage.isShowing()
                                || stage.titleProperty().isBound()) continue;
                        if (stage.hasProperties()
                                && stage.getProperties().get(MARKED_TITLE) instanceof MarkedTitle) {
                            count++;
                            continue;
                        }
                        final var title = stage.getTitle() == null ? "" : stage.getTitle();
                        if (!title.startsWith(MARK)) {
                            final var applied = MARK + title;
                            stage.getProperties()
                                    .put(MARKED_TITLE, new MarkedTitle(stage.getTitle(), applied));
                            markedWindows.add(windowHandle(stage));
                            stage.setTitle(applied);
                            count++;
                        }
                    }
                    return count;
                });
    }

    private static void recordEvent(
            final String method, final String request, final String response) {
        if (method == null || method.isBlank()) return;
        final Object payload;
        try {
            payload = Json.parse(httpBody(request));
        } catch (final IllegalArgumentException malformed) {
            return;
        }
        if (payload instanceof List<?> requests
                && Json.parse(response) instanceof List<?> replies) {
            for (var i = 0; i < Math.min(requests.size(), replies.size()); i++) {
                if (requests.get(i) instanceof Map<?, ?> envelope
                        && envelope.get("method") instanceof String name) {
                    recordSingleEvent(
                            name,
                            envelope.get("params"),
                            RpcReply.parse(Json.stringify(replies.get(i))));
                }
            }
        } else if (payload instanceof Map<?, ?> envelope) {
            recordSingleEvent(method, envelope.get("params"), RpcReply.parse(response));
        }
    }

    private static void recordSingleEvent(
            final String method, final Object params, final RpcReply reply) {
        if (Set.of("events", "eventsSince", "clearEvents").contains(method)) return;
        synchronized (events) {
            final var timestamp = System.currentTimeMillis();
            final var event = new LinkedHashMap<String, Object>();
            event.put("sequence", ++eventSequence);
            event.put("ts", timestamp);
            event.put("method", method);
            event.put("ok", !reply.failed());
            final var actionId =
                    reply.actionId() instanceof Number number ? number.longValue() : -1;
            final var status = actionId < 0 ? null : FxActions.status(actionId);
            final var sensitive =
                    declaresSensitivity(params)
                            || declaresSensitivity(reply.result())
                            || status != null && status.sensitive();
            event.put("sensitive", sensitive);
            if (reply.completion() != null) event.put("completion", reply.completion());
            if (actionId >= 0) event.put("actionId", actionId);
            event.put(
                    "params",
                    sensitive
                            ? Map.of("sensitive", true)
                            : params == null ? Map.of() : redactParams(params));
            events.addLast(new DriverEvent(eventSequence, timestamp, Json.stringify(event)));
            while (events.size() > 500) events.removeFirst();
        }
    }

    private static boolean declaresSensitivity(final Object value) {
        if (value instanceof Map<?, ?> map) {
            return Boolean.TRUE.equals(map.get("sensitive"))
                    || map.values().stream().anyMatch(FxDriverAgent::declaresSensitivity);
        }
        return value instanceof List<?> list
                && list.stream().anyMatch(FxDriverAgent::declaresSensitivity);
    }

    private static Object redactParams(final Object value) {
        if (value instanceof Map<?, ?> map) {
            final var redacted = new LinkedHashMap<String, Object>();
            for (final var entry : map.entrySet()) {
                final var key = String.valueOf(entry.getKey());
                redacted.put(
                        key,
                        Set.of(
                                                "value",
                                                "chars",
                                                "script",
                                                "password",
                                                "token",
                                                "secret",
                                                "authorization",
                                                "credentials")
                                        .contains(key.toLowerCase(java.util.Locale.ROOT))
                                ? "[redacted]"
                                : redactParams(entry.getValue()));
            }
            return redacted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(FxDriverAgent::redactParams).toList();
        }
        return value;
    }

    private static String httpBody(final String request) {
        final var bodyStart = request.indexOf("\r\n\r\n");
        return bodyStart < 0 ? request : request.substring(bodyStart + 4);
    }

    private static boolean isClientDisconnect(final IOException exception) {
        final var message = exception.getMessage();
        return message != null
                && (message.contains("Broken pipe") || message.contains("Connection reset"));
    }

    private static String readRequest(final Socket socket) throws IOException {
        socket.setSoTimeout(2000);
        final var input = socket.getInputStream();
        final var headers = new ByteArrayOutputStream();
        var matched = 0;
        final var boundary = new byte[] {13, 10, 13, 10};
        while (matched < boundary.length) {
            final var next = input.read();
            if (next < 0) throw new java.io.EOFException("Incomplete HTTP headers");
            headers.write(next);
            if (headers.size() > 16384) throw new IOException("HTTP headers exceed limit");
            matched = next == boundary[matched] ? matched + 1 : next == 13 ? 1 : 0;
        }
        final var prefix = headers.toString(StandardCharsets.ISO_8859_1);
        if (!prefix.startsWith("POST /rpc HTTP/"))
            throw new IOException("Only POST /rpc is supported");
        final var length = contentLength(prefix);
        if (length < 0 || length > MAX_REQUEST_BYTES)
            throw new IOException("Request body exceeds limit");
        final var body = input.readNBytes(length);
        if (body.length != length) throw new java.io.EOFException("Incomplete request body");
        return prefix
                + StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(body));
    }

    private static int contentLength(final String headers) throws IOException {
        Integer length = null;
        for (final var line : headers.split("\\r\\n")) {
            final var colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).equalsIgnoreCase("transfer-encoding")) {
                throw new IOException("Chunked request bodies are unsupported");
            }
            if (colon > 0 && line.substring(0, colon).equalsIgnoreCase("content-length")) {
                if (length != null) throw new IOException("Duplicate Content-Length");
                try {
                    length = Integer.valueOf(line.substring(colon + 1).strip());
                } catch (final NumberFormatException invalid) {
                    throw new IOException("Invalid Content-Length");
                }
            }
        }
        return length == null ? 0 : length;
    }

    private static String extractId(final String body) {
        try {
            final var id = Json.object(httpBody(body)).get("id");
            return id == null || id instanceof String || id instanceof Number
                    ? Json.stringify(id)
                    : "null";
        } catch (final IllegalArgumentException invalid) {
            return "null";
        }
    }

    private static int extractInt(final String body, final String key, final int fallback) {
        final var value = extractLong(body, key, fallback);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
            throw new IllegalArgumentException(key + " is outside integer range");
        return (int) value;
    }

    private static long extractLong(final String body, final String key, final long fallback) {
        final var value = namedParams(body).get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number))
            throw new IllegalArgumentException(key + " must be an integer");
        try {
            return new java.math.BigDecimal(value.toString()).longValueExact();
        } catch (final RuntimeException invalid) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }

    private static boolean hasKey(final String body, final String key) {
        return namedParams(body).containsKey(key);
    }

    private static boolean extractBoolean(
            final String body, final String key, final boolean fallback) {
        final var value = namedParams(body).get(key);
        if (value == null) return fallback;
        if (value instanceof Boolean bool) return bool;
        if ("returnState".equals(key) && value instanceof String) return true;
        throw new IllegalArgumentException(key + " must be a boolean");
    }

    private static Map<String, Object> namedParams(final String body) {
        return body.isBlank() ? Map.of() : Json.object(httpBody(body));
    }

    private static String extractString(final String body, final String key) {
        final var value = namedParams(body).get(key);
        if (value == null || ("returnState".equals(key) && value instanceof Boolean)) return "";
        if (value instanceof String string) return string;
        throw new IllegalArgumentException(key + " must be a string");
    }

    private static List<String> extractStringArray(final String body, final String key) {
        final var value = namedParams(body).get(key);
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)
                || list.stream().anyMatch(item -> !(item instanceof String))) {
            throw new IllegalArgumentException(key + " must be a string array");
        }
        return list.stream().map(String.class::cast).toList();
    }

    private static String jsonStrings(final List<String> values) {
        return Json.strings(values);
    }

    private static String jsonEscape(final String value) {
        return Json.escape(value);
    }
}
