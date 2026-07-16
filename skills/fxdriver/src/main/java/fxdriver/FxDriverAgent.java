package fxdriver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
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
import javafx.scene.robot.Robot;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.Window;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;

public final class FxDriverAgent {
    private static final String MARK = "[fxdriver] ";
    private static final Map<Node, Effect> previousEffects = new IdentityHashMap<>();
    private static volatile boolean visualTrace;
    private static volatile int visualTraceMs = 250;
    private static volatile String visualTraceEffect = "invert";
    private static final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private static final Map<Node, Integer> nodeIds = new IdentityHashMap<>();
    private static final Map<Window, Integer> windowIds = new IdentityHashMap<>();
    private static int nextNodeId = 1;
    private static int nextWindowId = 1;
    private static volatile ServerSocket server;
    private static volatile String authToken = "";
    private static volatile boolean exitOnShutdown;
    private static final String METHODS =
            "ping, version, capabilities, configure, shutdown, batch, run, events, eventsSince,"
                + " clearEvents, mark, highlight, snapshot, snapshotSummary, query, press, key,"
                + " fire, fireMenuItem, tableCell, click, type, clear, setText, setValue,"
                + " showPopup, hidePopup, increment, decrement, scroll, listItems, selectListItem,"
                + " selectIndex, scrollToIndex, expand, collapse, wait, assert, screenshot,"
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
            final var method = payload.startsWith("[") ? "batch" : extractString(request, "method");
            final var allowed = authorized(request);
            final var body =
                    allowed
                            ? dispatchRequest(request)
                            : errorResponse(
                                    id,
                                    -32001,
                                    "UNAUTHORIZED",
                                    "Missing or invalid fxdriver-token header");
            recordEvent(method, request, body);
            final var bytes = body.getBytes(StandardCharsets.UTF_8);
            final var headers =
                    "HTTP/1.1 200 OK\r\n"
                            + "content-type: application/json; charset=utf-8\r\n"
                            + "content-length: "
                            + bytes.length
                            + "\r\nconnection: close\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().write(bytes);
            if (allowed && "shutdown".equals(method)) {
                server.close();
                server = null;
                if (extractBoolean(request, "exitApp", exitOnShutdown)) {
                    final var exit = new Thread(() -> System.exit(0), "fxdriver-exit");
                    exit.setDaemon(true);
                    exit.start();
                }
            }
        }
    }

    private static String dispatchRequest(final String request) {
        final var payload = httpBody(request).stripLeading();
        if (payload.startsWith("[")) {
            return dispatchBatch(payload);
        }
        return dispatch(extractId(request), extractString(request, "method"), request);
    }

    private static String dispatchBatch(final String payload) {
        final var out = new StringBuilder("[");
        var first = true;
        for (final var request : batchRequests(payload)) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append(dispatch(extractId(request), extractString(request, "method"), request));
        }
        out.append(']');
        return out.toString();
    }

    private static List<String> batchRequests(final String payload) {
        final var requests = new ArrayList<String>();
        var depth = 0;
        var start = -1;
        var escaped = false;
        var quoted = false;
        for (var i = 0; i < payload.length(); i++) {
            final var c = payload.charAt(i);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    quoted = false;
                }
                continue;
            }
            if (c == '"') {
                quoted = true;
            } else if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    requests.add(payload.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return requests;
    }

    private static List<String> objectArray(final String json, final String key) {
        final var marker = "\"" + key + "\"";
        final var at = json.indexOf(marker);
        if (at < 0) {
            return List.of();
        }
        final var colon = json.indexOf(':', at + marker.length());
        if (colon < 0) {
            return List.of();
        }
        var start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        if (start >= json.length() || json.charAt(start) != '[') {
            return List.of();
        }
        var depth = 0;
        var escaped = false;
        var quoted = false;
        for (var i = start; i < json.length(); i++) {
            final var c = json.charAt(i);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    quoted = false;
                }
                continue;
            }
            if (c == '"') {
                quoted = true;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return batchRequests(json.substring(start, i + 1));
                }
            }
        }
        return List.of();
    }

    private static String dispatch(final String id, final String method, final String request) {
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
                case "shutdown" ->
                        "{\"jsonrpc\":\"2.0\",\"id\":"
                                + id
                                + ",\"result\":{\"ok\":true,\"shutdown\":true,\"exitApp\":"
                                + extractBoolean(request, "exitApp", exitOnShutdown)
                                + "}}";
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
                case "fire" -> fireResponse(id, request);
                case "fireMenuItem" -> fireMenuItemResponse(id, request);
                case "tableCell" -> tableCellResponse(id, request);
                case "click" -> clickResponse(id, request);
                case "type" -> typeResponse(id, request);
                case "clear" -> clearResponse(id, request);
                case "setText" -> setTextResponse(id, request);
                case "setValue" -> setValueResponse(id, request);
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
                case "assert" -> assertResponse(id, request);
                case "screenshot" -> screenshotResponse(id, request);
                case "webExecuteScript" -> webExecuteScriptResponse(id, request);
                default -> errorResponse(id, -32601, "METHOD_NOT_FOUND", "methods: " + METHODS);
            };
        } catch (final Throwable throwable) {
            return errorResponse(
                    id,
                    -32000,
                    throwable.getClass().getSimpleName(),
                    throwable.getMessage() == null ? "internal error" : throwable.getMessage());
        }
    }

    private static String versionResponse(final String id) {
        final var version = FxDriverAgent.class.getPackage().getImplementationVersion();
        return "{\"jsonrpc\":\"2.0\",\"id\":"
                + id
                + ",\"result\":{\"name\":\"fxdriver\",\"version\":\""
                + jsonEscape(version == null ? "0.1.0-SNAPSHOT" : version)
                + "\",\"protocolVersion\":1}}";
    }

    private static String capabilitiesResponse(final String id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":"
                + id
                + ",\"result\":{\"workingDirectory\":\""
                + jsonEscape(Path.of("").toAbsolutePath().normalize().toString())
                + "\",\"methods\":["
                + jsonStrings(List.of(METHODS.split(", ")))
                + "],\"selectors\":[\"@handle\",\"eid=n\",\"#node-id\",\".style-class\",\"=exact"
                + " text\",\"text=contains\",\"regexText=...\",\"type=Button\",\"role=BUTTON\",\"accessible=label\"],\"features\":[\"attach\",\"launch\",\"batch\",\"run\",\"snapshot\",\"query\",\"returnState\",\"actions\",\"keyboard\",\"menus\",\"table-cell\",\"value-controls\",\"tree-table-actions\",\"snapshot-summary\",\"near-matches\",\"screenshot\",\"screenshot-metadata\",\"image-summary\",\"image-diff\",\"events\",\"eventsSince\",\"visual-trace\",\"webExecuteScript\"]}}";
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
        final var steps = objectArray(request, "steps");
        final var continueOnError = extractBoolean(request, "continueOnError", false);
        final var results = new StringBuilder();
        var ok = true;
        for (var i = 0; i < steps.size(); i++) {
            if (i > 0) {
                results.append(',');
            }
            final var step = steps.get(i);
            var method = extractString(step, "op");
            if (method.isBlank()) {
                method = extractString(step, "method");
            }
            final var response =
                    method.isBlank()
                            ? errorResponse(
                                    String.valueOf(i + 1),
                                    -32600,
                                    "MISSING_OP",
                                    "run step requires op or method")
                            : dispatch(String.valueOf(i + 1), method, step);
            results.append(response);
            if (response.contains("\"error\"") || response.contains("\"ok\":false")) {
                ok = false;
                if (!continueOnError) {
                    break;
                }
            }
        }
        return resultResponse(
                id,
                "\"ok\":" + ok + ",\"count\":" + steps.size() + ",\"steps\":[" + results + "]",
                request);
    }

    private static String eventsResponse(final String id) {
        final List<String> copy;
        synchronized (events) {
            copy = List.copyOf(events);
        }
        return "{\"jsonrpc\":\"2.0\",\"id\":"
                + id
                + ",\"result\":{\"events\":["
                + String.join(",", copy)
                + "]}}";
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
        final List<String> copy;
        synchronized (events) {
            copy = List.copyOf(events);
        }
        final var since = Math.max(0, extractLong(request, "since", 0));
        var start = Math.min(Math.max(0, extractInt(request, "cursor", 0)), copy.size());
        if (hasKey(request, "since")) {
            start = 0;
            while (start < copy.size() && extractLong(copy.get(start), "ts", 0) < since) {
                start++;
            }
        }
        return "{\"jsonrpc\":\"2.0\",\"id\":"
                + id
                + ",\"result\":{\"cursor\":"
                + start
                + (hasKey(request, "since") ? ",\"since\":" + since : "")
                + ",\"nextCursor\":"
                + copy.size()
                + ",\"events\":["
                + String.join(",", copy.subList(start, copy.size()))
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
        for (final var window : Window.getWindows()) {
            if (!window.isShowing() || window.getScene() == null) {
                continue;
            }
            final var owner = window.getScene().getFocusOwner();
            if (owner == null || !owner.isVisible()) {
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
        for (final var window : Window.getWindows()) {
            if (!window.isShowing() || window.getScene() == null) {
                continue;
            }
            addCapped(windows, orientationWindowJson(window), 8);
            for (final var node : flatten(window.getScene().getRoot())) {
                if (!node.isVisible()) {
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
                    final var value =
                            input instanceof PasswordField
                                    ? maskedValue(input.getText())
                                    : input.getText();
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
        return node instanceof Toggle
                || node instanceof ChoiceBox<?>
                || node instanceof ComboBoxBase<?>
                || node instanceof Spinner<?>
                || node instanceof Slider;
    }

    private static String summaryValueControlJson(final Window window, final Node node) {
        final var fields = new ArrayList<String>();
        if (node instanceof Toggle toggle) {
            fields.add("\"selected\":" + toggle.isSelected());
        }
        if (node instanceof ChoiceBox<?> choice) {
            addString(fields, "value", String.valueOf(choice.getValue()));
        } else if (node instanceof ComboBoxBase<?> combo) {
            addString(fields, "value", String.valueOf(combo.getValue()));
            fields.add("\"showing\":" + combo.isShowing());
        } else if (node instanceof Spinner<?> spinner) {
            addString(fields, "value", String.valueOf(spinner.getValue()));
        } else if (node instanceof Slider slider) {
            fields.add("\"value\":" + jsonNumber(slider.getValue()));
        }
        return summaryNodeOpen(window, node)
                + ",\"kind\":\""
                + semanticKind(node)
                + "\""
                + (fields.isEmpty() ? "" : "," + String.join(",", fields))
                + "}";
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
        final var limit = Math.max(1, extractInt(request, "limit", 120));
        return resultResponse(
                id,
                "\"query\":\""
                        + jsonEscape(query)
                        + "\",\"mode\":\""
                        + jsonEscape(mode)
                        + "\",\"limit\":"
                        + limit
                        + ",\"nodes\":"
                        + queryNodes(query, mode, limit),
                "");
    }

    private static String pressResponse(final String id, final String request) throws Exception {
        var key = extractString(request, "key");
        if (key.isBlank()) {
            key = "F1";
        }
        return resultResponse(
                id,
                "\"key\":\"" + jsonEscape(key) + "\",\"acceleratorsFired\":" + press(key),
                request);
    }

    private record ParsedKey(
            KeyCode code, boolean shift, boolean control, boolean alt, boolean meta) {}

    private static String keyResponse(final String id, final String request) throws Exception {
        final var target = onFxThread(() -> keyTarget(request));
        if (target == null) {
            return resultResponse(
                    id,
                    "\"key\":\"\",\"chars\":\"\",\"sent\":0,\"target\":null"
                            + okOrError(
                                    false, "NO_FOCUS", "No keyboard target was focused or matched"),
                    request);
        }
        final var chars = extractString(request, "chars");
        final var key = extractString(request, "key");
        final var hasChars = hasKey(request, "chars");
        final var sent = onFxThread(() -> dispatchKeyboard(target, key, chars, hasChars));
        return resultResponse(
                id,
                "\"key\":\""
                        + jsonEscape(key)
                        + "\",\"chars\":\""
                        + jsonEscape(chars)
                        + "\",\"sent\":"
                        + sent
                        + ",\"target\":"
                        + targetJson(target)
                        + okOrError(sent > 0, "NO_KEY", "key requires key or chars"),
                request);
    }

    private static Node keyTarget(final String request) {
        if (hasTargetSelector(request)) {
            final var matches = matchesFor(highlightQueryOrDefault(request, ""));
            return matches.isEmpty() ? null : matches.getFirst();
        }
        final var windows = new ArrayList<>(Window.getWindows());
        Collections.reverse(windows);
        for (final var window : windows) {
            if (window.isShowing()
                    && window.isFocused()
                    && window.getScene() != null
                    && window.getScene().getFocusOwner() != null) {
                return window.getScene().getFocusOwner();
            }
        }
        for (final var window : windows) {
            if (window.isShowing()
                    && window.getScene() != null
                    && window.getScene().getFocusOwner() != null) {
                return window.getScene().getFocusOwner();
            }
        }
        return null;
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
        final var result = new CompletableFuture<T>();
        Platform.runLater(
                () -> {
                    try {
                        result.complete(action.call());
                    } catch (final Throwable throwable) {
                        result.completeExceptionally(throwable);
                    }
                });
        return result.get(5, TimeUnit.SECONDS);
    }

    private static String fireResponse(final String id, final String request) throws Exception {
        final var query = highlightQuery(request);
        final var fired = fire(query, traceMs(request), highlightEffect(request));
        return actionResponse(id, request, query, "fired", fired);
    }

    private record MenuFireResult(String kind, String value, int fired, String code) {}

    private static String fireMenuItemResponse(final String id, final String request)
            throws Exception {
        final var query = highlightQuery(request);
        final var path = extractStringArray(request, "path");
        final var itemText = extractString(request, "itemText");
        final var result = onFxThread(() -> fireMenuItem(query, path, itemText));
        final var ok = result.fired() > 0;
        return resultResponse(
                id,
                "\"query\":\""
                        + jsonEscape(query)
                        + "\",\"kind\":\""
                        + jsonEscape(result.kind())
                        + "\",\"path\":["
                        + jsonStrings(path)
                        + "],\"value\":\""
                        + jsonEscape(result.value())
                        + "\",\"fired\":"
                        + result.fired()
                        + okOrError(ok, result.code(), "Menu item could not be resolved or fired"),
                request);
    }

    private static MenuFireResult fireMenuItem(
            final String query, final List<String> path, final String itemText) {
        final var matches = matchesFor(query);
        if (matches.isEmpty()) {
            return new MenuFireResult("", "", 0, "NO_MENU_ITEM");
        }
        final var target = matches.getFirst();
        final var items = menuItems(target);
        if ((path.isEmpty() && itemText.isBlank()) || path.stream().anyMatch(String::isBlank)) {
            return new MenuFireResult(target.getClass().getSimpleName(), "", 0, "NO_MENU_ITEM");
        }
        final MenuItem item;
        if (!path.isEmpty()) {
            item = menuPath(items, path, 0);
        } else {
            final var exact = menuText(items, itemText, true);
            item = exact == null ? menuText(items, itemText, false) : exact;
        }
        if (item == null) {
            return new MenuFireResult(target.getClass().getSimpleName(), "", 0, "NO_MENU_ITEM");
        }
        if (item.isDisable()) {
            return new MenuFireResult(
                    target.getClass().getSimpleName(), item.getText(), 0, "DISABLED");
        }
        item.fire();
        return new MenuFireResult(
                target.getClass().getSimpleName(), item.getText(), 1, "NO_MENU_ITEM");
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

    private static MenuItem menuPath(
            final List<MenuItem> items, final List<String> path, final int index) {
        if (index >= path.size()) {
            return null;
        }
        for (final var item : items) {
            if (!path.get(index).equals(item.getText())) {
                continue;
            }
            if (index == path.size() - 1) {
                return item;
            }
            return item instanceof Menu menu ? menuPath(menu.getItems(), path, index + 1) : null;
        }
        return null;
    }

    private static MenuItem menuText(
            final List<MenuItem> items, final String text, final boolean exact) {
        for (final var item : items) {
            final var itemValue = item.getText() == null ? "" : item.getText();
            if (exact ? text.equals(itemValue) : itemValue.contains(text)) {
                return item;
            }
            if (item instanceof Menu menu) {
                final var nested = menuText(menu.getItems(), text, exact);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
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
                    + jsonEscape(rowText)
                    + "\",\"value\":\""
                    + jsonEscape(value)
                    + "\",\"ok\":"
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
                            for (final var node : matchesFor(query)) {
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

    private static TableCellResult tableCell(
            final TableView<?> table,
            final String rowText,
            final int requestedRow,
            final String column,
            final int requestedColumn) {
        final var row = resolveRow(table.getItems(), rowText, requestedRow);
        final var columnIndex = resolveColumn(table.getColumns(), column, requestedColumn);
        if (row < 0) {
            return TableCellResult.failure("NO_ROW", -1);
        }
        if (columnIndex < 0) {
            return TableCellResult.failure("NO_COLUMN", row);
        }
        final var selected = table.getColumns().get(columnIndex);
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
        final var rows = new ArrayList<Object>();
        for (var i = 0; i < table.getExpandedItemCount(); i++) {
            rows.add(table.getTreeItem(i).getValue());
        }
        final var row = resolveRow(rows, rowText, requestedRow);
        final var columnIndex = resolveColumn(table.getColumns(), column, requestedColumn);
        if (row < 0) {
            return TableCellResult.failure("NO_ROW", -1);
        }
        if (columnIndex < 0) {
            return TableCellResult.failure("NO_COLUMN", row);
        }
        final var selected = table.getColumns().get(columnIndex);
        return TableCellResult.found(
                row,
                columnIndex,
                columnName(selected),
                String.valueOf(table.getTreeItem(row).getValue()),
                String.valueOf(selected.getCellData(row)));
    }

    private static int resolveRow(final List<?> rows, final String text, final int requested) {
        if (requested >= 0) {
            return requested < rows.size() ? requested : -1;
        }
        for (var i = 0; i < rows.size(); i++) {
            if (String.valueOf(rows.get(i)).equals(text)) {
                return i;
            }
        }
        for (var i = 0; i < rows.size(); i++) {
            if (String.valueOf(rows.get(i)).contains(text)) {
                return i;
            }
        }
        return -1;
    }

    private static int resolveColumn(
            final List<? extends TableColumnBase<?, ?>> columns,
            final String value,
            final int requested) {
        if (requested >= 0) {
            return requested < columns.size() ? requested : -1;
        }
        for (var i = 0; i < columns.size(); i++) {
            final var column = columns.get(i);
            if (value.equals(column.getId()) || value.equals(column.getText())) {
                return i;
            }
        }
        for (var i = 0; i < columns.size(); i++) {
            if (value.equalsIgnoreCase(columns.get(i).getText())) {
                return i;
            }
        }
        return -1;
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
        final var typed = type(query, value, replace, traceMs(request), highlightEffect(request));
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
        final var changed = setText(query, value, traceMs(request), highlightEffect(request));
        return textActionResponse(id, request, query, "changed", changed, value, true);
    }

    private static String setValueResponse(final String id, final String request) throws Exception {
        final var query = highlightQuery(request);
        final var value = inputValue(request);
        final var changed = setValue(query, value, traceMs(request), highlightEffect(request));
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
        final var steps = Math.max(1, extractInt(request, "steps", 1));
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
        final var items = listItems(query);
        return "{\"jsonrpc\":\"2.0\",\"id\":"
                + id
                + ",\"result\":{\"query\":\""
                + jsonEscape(query)
                + "\",\"items\":["
                + jsonStrings(items)
                + "]}}";
    }

    private static String selectListItemResponse(final String id, final String request)
            throws Exception {
        final var query = highlightQueryOrDefault(request, "ListView");
        final var text = extractString(request, "itemText");
        final var index = extractInt(request, "index", -1);
        final var activate = extractBoolean(request, "activate", true);
        final var result = selectListItem(query, text, index, activate);
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
        final var changed = selectIndex(query, index, traceMs(request), highlightEffect(request));
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
        final var query = highlightQueryOrDefault(request, "type=WebView");
        final var script = extractString(request, "script");
        final var result =
                webExecuteScript(query, script, traceMs(request), highlightEffect(request));
        return resultResponse(
                id,
                "\"query\":\""
                        + jsonEscape(query)
                        + "\",\"script\":\""
                        + jsonEscape(script)
                        + "\",\"value\":\""
                        + jsonEscape(result.value())
                        + "\",\"executed\":"
                        + result.count()
                        + ",\"ok\":"
                        + (result.count() > 0)
                        + (result.count() > 0
                                ? ""
                                : ",\"error\":{\"code\":\"NO_MATCH\",\"message\":\"No WebView"
                                        + " target matched query\"}"),
                request);
    }

    private static String waitResponse(final String id, final String request) throws Exception {
        final var query = highlightQuery(request);
        final var timeoutMs = extractInt(request, "timeoutMs", 2_000);
        final var present = extractBoolean(request, "present", true);
        final var enabled =
                hasKey(request, "enabled")
                        ? Boolean.valueOf(extractBoolean(request, "enabled", true))
                        : null;
        final var focused =
                hasKey(request, "focused")
                        ? Boolean.valueOf(extractBoolean(request, "focused", true))
                        : null;
        final var visible =
                hasKey(request, "visible")
                        ? Boolean.valueOf(extractBoolean(request, "visible", true))
                        : null;
        final var count = waitFor(query, present, timeoutMs, enabled, focused, visible);
        final var ok = present ? count > 0 : count == 0;
        return "{\"jsonrpc\":\"2.0\",\"id\":"
                + id
                + ",\"result\":{\"query\":\""
                + jsonEscape(query)
                + "\",\"present\":"
                + present
                + ",\"count\":"
                + count
                + ",\"ok\":"
                + ok
                + (ok
                        ? ""
                        : ",\"nearMatches\":["
                                + nearMatchesJson(query)
                                + "],\"error\":{\"code\":\"TIMEOUT\",\"message\":\"Wait"
                                + " predicate not satisfied\"}")
                + "}}";
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
                            if (!node.isVisible()) {
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
                });
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
        final var enabled =
                hasKey(request, "enabled")
                        ? Boolean.valueOf(extractBoolean(request, "enabled", true))
                        : null;
        final var focused =
                hasKey(request, "focused")
                        ? Boolean.valueOf(extractBoolean(request, "focused", true))
                        : null;
        final var visible =
                hasKey(request, "visible")
                        ? Boolean.valueOf(extractBoolean(request, "visible", true))
                        : null;
        final var count = countMatches(query, enabled, focused, visible);
        final var ok =
                expectedCount >= 0 ? count == expectedCount : present ? count > 0 : count == 0;
        return "{\"jsonrpc\":\"2.0\",\"id\":"
                + id
                + ",\"result\":{\"query\":\""
                + jsonEscape(query)
                + "\",\"present\":"
                + present
                + ",\"expectedCount\":"
                + expectedCount
                + ",\"count\":"
                + count
                + ",\"ok\":"
                + ok
                + (ok
                        ? ""
                        : ",\"error\":{\"code\":\"ASSERT_FAILED\",\"message\":\"Assert predicate"
                                + " not satisfied\"}")
                + "}}";
    }

    private static String screenshotResponse(final String id, final String request)
            throws Exception {
        var path = extractString(request, "path");
        if (path.isBlank()) {
            path = "target/fxdriver-screenshot.png";
        }
        return "{\"jsonrpc\":\"2.0\",\"id\":"
                + id
                + ",\"result\":"
                + screenshot(Path.of(path), request)
                + "}";
    }

    private static String highlightQuery(final String request) {
        return highlightQueryOrDefault(request, "Button");
    }

    private static String highlightQueryOrDefault(final String request, final String fallback) {
        final var handle = extractInt(request, "handle", -1);
        if (handle >= 0) {
            return "@" + handle;
        }
        final var eid = extractString(request, "eid");
        if (!eid.isBlank()) {
            return eid.startsWith("n") ? "eid=" + eid : "eid=n" + eid;
        }
        final var textExact = extractString(request, "textExact");
        if (!textExact.isBlank()) {
            return "=" + textExact;
        }
        var id = extractString(request, "nodeId");
        if (id.isBlank()) {
            id = extractString(request, "cssId");
        }
        if (!id.isBlank()) {
            return "#" + id;
        }
        final var styleClass = extractString(request, "styleClass");
        if (!styleClass.isBlank()) {
            return "." + styleClass;
        }
        final var text = extractString(request, "text");
        if (!text.isBlank()) {
            return "text=" + text;
        }
        final var regexText = extractString(request, "regexText");
        if (!regexText.isBlank()) {
            return "regexText=" + regexText;
        }
        final var accessible = extractString(request, "accessible");
        if (!accessible.isBlank()) {
            return "accessible=" + accessible;
        }
        final var role = extractString(request, "role");
        if (!role.isBlank()) {
            return "role=" + role;
        }
        final var selector = extractString(request, "selector");
        if (!selector.isBlank()) {
            return selector;
        }
        final var type = extractString(request, "type");
        if (!type.isBlank()) {
            return "type=" + type;
        }
        return fallback;
    }

    private static String inputQuery(final String request) {
        return highlightQueryOrDefault(request, "TextField");
    }

    private static String inputValue(final String request) {
        if (!hasKey(request, "value")) {
            throw new IllegalArgumentException(
                    "setText/type require value; text is a selector field");
        }
        return Json.scalar(request, "value");
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
                                if (!node.isVisible()) {
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
        for (final var window : Window.getWindows()) {
            if (!window.isShowing() || window.getScene() == null) {
                continue;
            }
            if (!firstWindow) {
                windows.append(',');
            }
            firstWindow = false;
            windows.append(windowJson(window)).append('}');
            final var focusOwner = window.getScene().getFocusOwner();
            if (focusOwner != null && focusOwner.isVisible()) {
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

    private static String queryNodes(final String query, final String mode, final int limit)
            throws Exception {
        final var result = new CompletableFuture<String>();
        Platform.runLater(
                () -> {
                    try {
                        final var nodes = new StringBuilder();
                        final var first = new boolean[] {true};
                        final var emitted = new int[] {0};
                        final var singleMatch = singleMatchQuery(query);
                        for (final var window : Window.getWindows()) {
                            if (!window.isShowing() || window.getScene() == null) {
                                continue;
                            }
                            if (appendCssIdQuery(window, query, nodes, first, emitted)) {
                                break;
                            }
                            appendQueryNodes(
                                    window,
                                    window.getScene().getRoot(),
                                    query,
                                    mode,
                                    limit,
                                    singleMatch,
                                    nodes,
                                    first,
                                    emitted);
                            if (singleMatch && emitted[0] > 0) {
                                break;
                            }
                        }
                        result.complete("[" + nodes + "]");
                    } catch (final Throwable throwable) {
                        result.completeExceptionally(throwable);
                    }
                });
        return result.get(30, TimeUnit.SECONDS);
    }

    private static boolean appendCssIdQuery(
            final Window window,
            final String query,
            final StringBuilder nodes,
            final boolean[] first,
            final int[] emitted) {
        if (!query.startsWith("#")) {
            return false;
        }
        final var node = window.getScene().getRoot().lookup(query);
        if (node == null || !node.isVisible()) {
            return false;
        }
        if (!first[0]) {
            nodes.append(',');
        }
        first[0] = false;
        nodes.append(compactNodeJson(window, node));
        emitted[0]++;
        return true;
    }

    private static void appendQueryNodes(
            final Window window,
            final Node node,
            final String query,
            final String mode,
            final int limit,
            final boolean singleMatch,
            final StringBuilder nodes,
            final boolean[] first,
            final int[] emitted) {
        if (emitted[0] >= limit || (singleMatch && emitted[0] > 0)) {
            return;
        }
        if (node.isVisible() && (query.isBlank() ? stateNode(node, mode) : matches(node, query))) {
            if (!first[0]) {
                nodes.append(',');
            }
            first[0] = false;
            nodes.append(compactNodeJson(window, node));
            emitted[0]++;
            if (singleMatch) {
                return;
            }
        }
        if (stateTerminal(node)) {
            return;
        }
        if (node instanceof SubScene subScene && subScene.getRoot() != null) {
            appendQueryNodes(
                    window,
                    subScene.getRoot(),
                    query,
                    mode,
                    limit,
                    singleMatch,
                    nodes,
                    first,
                    emitted);
        }
        if (node instanceof Parent parent) {
            for (final var child : parent.getChildrenUnmodifiable()) {
                appendQueryNodes(
                        window, child, query, mode, limit, singleMatch, nodes, first, emitted);
                if (emitted[0] >= limit || (singleMatch && emitted[0] > 0)) {
                    return;
                }
            }
        }
    }

    private static boolean singleMatchQuery(final String query) {
        return query.startsWith("#")
                || query.startsWith("@")
                || query.startsWith("eid=")
                || query.startsWith("eid=n");
    }

    private static boolean stateNode(final Node node, final String mode) {
        if (!node.isVisible()) {
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
                || actionable(node) == node
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
        final var fields = new ArrayList<String>();
        addString(fields, "kind", semanticKind(node));
        if (node instanceof Labeled labeled) {
            addString(fields, "text", labeled.getText() == null ? "" : labeled.getText());
        }
        if (node instanceof TextInputControl input) {
            final var masked = input instanceof PasswordField;
            addString(fields, "value", masked ? maskedValue(input.getText()) : input.getText());
            addString(fields, "prompt", input.getPromptText() == null ? "" : input.getPromptText());
            fields.add("\"editable\":" + input.isEditable());
            fields.add("\"length\":" + input.getLength());
            fields.add("\"masked\":" + masked);
        }
        if (node instanceof ListView<?> listView) {
            fields.add("\"itemsCount\":" + listView.getItems().size());
            fields.add(
                    "\"selectedIndices\":"
                            + jsonInts(listView.getSelectionModel().getSelectedIndices()));
            fields.add("\"focusedIndex\":" + listView.getFocusModel().getFocusedIndex());
        }
        if (node instanceof TableView<?> tableView) {
            fields.add("\"itemsCount\":" + tableView.getItems().size());
            fields.add(
                    "\"selectedIndices\":"
                            + jsonInts(tableView.getSelectionModel().getSelectedIndices()));
            fields.add("\"columns\":" + jsonColumns(tableView.getColumns()));
        }
        if (node instanceof TreeView<?> treeView) {
            fields.add("\"expandedItemCount\":" + treeView.getExpandedItemCount());
            fields.add(
                    "\"selectedIndices\":"
                            + jsonInts(treeView.getSelectionModel().getSelectedIndices()));
        }
        if (node instanceof TreeTableView<?> treeTableView) {
            fields.add("\"expandedItemCount\":" + treeTableView.getExpandedItemCount());
            fields.add(
                    "\"selectedIndices\":"
                            + jsonInts(treeTableView.getSelectionModel().getSelectedIndices()));
            fields.add("\"columns\":" + jsonColumns(treeTableView.getColumns()));
        }
        return fields.isEmpty() ? "" : ",\"semantic\":{" + String.join(",", fields) + "}";
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
                + "\",\"accessibleRole\":\""
                + jsonEscape(accessibleRoleOf(node))
                + "\",\"disabled\":"
                + node.isDisabled()
                + ",\"focused\":"
                + node.isFocused()
                + ",\"actionable\":"
                + (actionable(node) == node)
                + ",\"bounds\":{"
                + boundsJson(bounds)
                + "}"
                + compactSemanticJson(node)
                + "}";
    }

    private static boolean empty(final String value) {
        return value == null || value.isBlank();
    }

    private static String screenshot(final Path requestedPath, final String request)
            throws Exception {
        final var path = requestedPath.toAbsolutePath();
        final var target = extractString(request, "target");
        final var result = new CompletableFuture<String>();
        Platform.runLater(
                () -> {
                    try {
                        if ("node".equals(target)) {
                            result.complete(saveNodeScreenshot(path, highlightQuery(request)));
                        } else {
                            final var window = screenshotWindow(extractString(request, "window"));
                            if ("rect".equals(target)) {
                                result.complete(
                                        saveRectScreenshot(
                                                path,
                                                window,
                                                extractInt(request, "x", 0),
                                                extractInt(request, "y", 0),
                                                extractInt(request, "w", 0),
                                                extractInt(request, "h", 0)));
                            } else {
                                result.complete(saveWindowScreenshot(path, window));
                            }
                        }
                    } catch (final Throwable throwable) {
                        result.completeExceptionally(throwable);
                    }
                });
        return result.get(5, TimeUnit.SECONDS);
    }

    private static String saveNodeScreenshot(final Path path, final String query)
            throws IOException {
        for (final var node : matchesFor(query)) {
            final var image = toBufferedImage(node.snapshot(null, null));
            saveImage(path, image);
            final var bounds = node.getBoundsInLocal();
            return screenshotJson(
                    path,
                    "node",
                    image.getWidth(),
                    image.getHeight(),
                    Math.round((float) bounds.getMinX()),
                    Math.round((float) bounds.getMinY()),
                    Math.max(1, Math.round((float) bounds.getWidth())),
                    Math.max(1, Math.round((float) bounds.getHeight())),
                    "node-local",
                    node.getScene().getWindow());
        }
        throw new IllegalStateException("no node matched screenshot query: " + query);
    }

    private static String saveRectScreenshot(
            final Path path,
            final Window window,
            final int x,
            final int y,
            final int w,
            final int h)
            throws IOException {
        if (w <= 0 || h <= 0) {
            throw new IllegalArgumentException("rect screenshot requires positive w and h");
        }
        final var image = windowImage(window);
        final var left = Math.max(0, Math.min(x, image.getWidth() - 1));
        final var top = Math.max(0, Math.min(y, image.getHeight() - 1));
        final var width = Math.max(1, Math.min(w, image.getWidth() - left));
        final var height = Math.max(1, Math.min(h, image.getHeight() - top));
        saveImage(path, image.getSubimage(left, top, width, height));
        return screenshotJson(
                path, "rect", width, height, left, top, width, height, "window-image", window);
    }

    private static String saveWindowScreenshot(final Path path, final Window window)
            throws IOException {
        final var image = windowImage(window);
        saveImage(path, image);
        return screenshotJson(
                path,
                "window",
                image.getWidth(),
                image.getHeight(),
                0,
                0,
                image.getWidth(),
                image.getHeight(),
                "scene",
                window);
    }

    private static Stage screenshotWindow(final String requested) {
        Stage fallback = null;
        for (final var window : Window.getWindows()) {
            if (!(window instanceof Stage stage)
                    || !stage.isShowing()
                    || stage.getScene() == null) {
                continue;
            }
            if (("w" + windowHandle(stage)).equals(requested)) {
                return stage;
            }
            if (stage.isFocused() || fallback == null) {
                fallback = stage;
            }
        }
        if (!requested.isBlank()) {
            throw new IllegalArgumentException("unknown screenshot window: " + requested);
        }
        if (fallback != null) {
            return fallback;
        }
        throw new IllegalStateException("no showing JavaFX stage");
    }

    private static java.awt.image.BufferedImage windowImage(final Window window) {
        return toBufferedImage(window.getScene().snapshot(null));
    }

    private static String screenshotJson(
            final Path path,
            final String target,
            final int width,
            final int height,
            final int sourceX,
            final int sourceY,
            final int sourceW,
            final int sourceH,
            final String sourceUnits,
            final Window window)
            throws IOException {
        return "{\"path\":\""
                + jsonEscape(path.toString())
                + "\",\"bytes\":"
                + Files.size(path)
                + ",\"target\":\""
                + jsonEscape(target)
                + "\",\"image\":{"
                + boundsJson(0, 0, width, height)
                + "},\"source\":{"
                + boundsJson(sourceX, sourceY, sourceW, sourceH)
                + ",\"units\":\""
                + jsonEscape(sourceUnits)
                + "\"},\"scale\":{\"x\":"
                + scale(width, sourceW)
                + ",\"y\":"
                + scale(height, sourceH)
                + "},\"activeWindow\":"
                + screenshotOrientationJson(window)
                + ",\"visibleTextSample\":["
                + screenshotVisibleTextJson(window)
                + "]}";
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
            if (!node.isVisible()) {
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

    private static int press(final String key) throws Exception {
        final var result = new CompletableFuture<Integer>();
        Platform.runLater(
                () -> {
                    try {
                        final var combination = KeyCombination.keyCombination(key);
                        var fired = 0;
                        for (final var window : Window.getWindows()) {
                            if (window instanceof Stage stage
                                    && stage.isShowing()
                                    && stage.getScene() != null) {
                                final var action =
                                        stage.getScene().getAccelerators().get(combination);
                                if (action != null) {
                                    action.run();
                                    fired++;
                                }
                            }
                        }
                        if (fired == 0) {
                            fired = dispatchKey(key);
                        }
                        result.complete(fired);
                    } catch (final Throwable throwable) {
                        result.completeExceptionally(throwable);
                    }
                });
        return result.get(5, TimeUnit.SECONDS);
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
                    return null;
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
                    return synthesizeClick(target) ? target : null;
                });
    }

    private static Node clickTarget(final Node node) {
        final var actionable = actionable(node);
        if (actionable instanceof ButtonBase) {
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
            final String effect)
            throws Exception {
        return withFirstMatch(
                query,
                highlightMs,
                effect,
                node -> {
                    final var target = actionable(node);
                    if (target instanceof TextInputControl input) {
                        input.requestFocus();
                        if (replace) {
                            input.setText(text);
                        } else {
                            input.appendText(text);
                        }
                        return target;
                    }
                    return null;
                });
    }

    private static ActionResult setText(
            final String query, final String text, final int highlightMs, final String effect)
            throws Exception {
        return type(query, text, true, highlightMs, effect);
    }

    private static ActionResult setValue(
            final String query, final String value, final int highlightMs, final String effect)
            throws Exception {
        return withFirstMatch(
                query,
                highlightMs,
                effect,
                node -> {
                    final var target = actionable(node);
                    if (target instanceof Slider slider) {
                        slider.setValue(Double.parseDouble(value));
                        return target;
                    }
                    if (target instanceof ScrollBar scrollBar) {
                        scrollBar.setValue(Double.parseDouble(value));
                        return target;
                    }
                    if (target instanceof DatePicker datePicker) {
                        datePicker.setValue(LocalDate.parse(value));
                        return target;
                    }
                    if (target instanceof ColorPicker colorPicker) {
                        colorPicker.setValue(Color.web(value));
                        return target;
                    }
                    if (target instanceof ComboBox<?> comboBox) {
                        final var index = itemIndex(comboBox.getItems(), value);
                        if (index >= 0) {
                            comboBox.getSelectionModel().select(index);
                            return target;
                        }
                    }
                    if (target instanceof ChoiceBox<?> choiceBox) {
                        final var index = itemIndex(choiceBox.getItems(), value);
                        if (index >= 0) {
                            choiceBox.getSelectionModel().select(index);
                            return target;
                        }
                    }
                    try {
                        return setObjectValue(target, value) ? target : null;
                    } catch (final ReflectiveOperationException exception) {
                        return null;
                    }
                });
    }

    private static int itemIndex(final List<?> items, final String value) {
        for (var i = 0; i < items.size(); i++) {
            if (value.equals(String.valueOf(items.get(i)))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean setObjectValue(final Object target, final Object value)
            throws ReflectiveOperationException {
        try {
            target.getClass().getMethod("setValue", Object.class).invoke(target, value);
            return true;
        } catch (final NoSuchMethodException exception) {
            return false;
        } catch (final InvocationTargetException exception) {
            throw reflectiveCause(exception);
        }
    }

    private static ReflectiveOperationException reflectiveCause(
            final InvocationTargetException exception) {
        final var cause = exception.getCause();
        return cause instanceof ReflectiveOperationException reflective
                ? reflective
                : new ReflectiveOperationException(cause == null ? exception : cause);
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
                    return null;
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
                        slider.setValue(slider.getValue() + (increment ? steps : -steps));
                        return target;
                    }
                    if (target instanceof ScrollBar scrollBar) {
                        scrollBar.setValue(scrollBar.getValue() + (increment ? steps : -steps));
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
                                Math.max(0.0, Math.min(1.0, pane.getVvalue() + amount / 100.0)));
                        return target;
                    }
                    final var bounds = target.localToScreen(target.getBoundsInLocal());
                    if (bounds == null) {
                        return null;
                    }
                    final var robot = new Robot();
                    robot.mouseMove(bounds.getCenterX(), bounds.getCenterY());
                    robot.mouseWheel(amount);
                    return target;
                });
    }

    private static List<String> listItems(final String query) throws Exception {
        final var result = new CompletableFuture<List<String>>();
        Platform.runLater(
                () -> {
                    try {
                        for (final var node : matchesFor(query)) {
                            if (node instanceof ListView<?> listView) {
                                result.complete(
                                        listView.getItems().stream().map(String::valueOf).toList());
                                return;
                            }
                        }
                        result.complete(List.of());
                    } catch (final Throwable throwable) {
                        result.completeExceptionally(throwable);
                    }
                });
        return result.get(5, TimeUnit.SECONDS);
    }

    private record ListSelectionResult(int selected, int activated) {}

    private static ListSelectionResult selectListItem(
            final String query, final String text, final int index, final boolean activate)
            throws Exception {
        final var result = new CompletableFuture<ListSelectionResult>();
        Platform.runLater(
                () -> {
                    try {
                        for (final var node : matchesFor(query)) {
                            if (node instanceof ListView<?> listView) {
                                var selection = index;
                                if (selection < 0 && !text.isBlank()) {
                                    for (var i = 0; i < listView.getItems().size(); i++) {
                                        if (String.valueOf(listView.getItems().get(i))
                                                .contains(text)) {
                                            selection = i;
                                            break;
                                        }
                                    }
                                }
                                if (selection >= 0 && selection < listView.getItems().size()) {
                                    final var selectedText =
                                            String.valueOf(listView.getItems().get(selection));
                                    listView.getSelectionModel().select(selection);
                                    listView.scrollTo(selection);
                                    listView.applyCss();
                                    listView.layout();
                                    final var activated =
                                            activate
                                                    ? activateListItem(listView, selectedText, text)
                                                    : 0;
                                    result.complete(new ListSelectionResult(1, activated));
                                    return;
                                }
                            }
                        }
                        result.complete(new ListSelectionResult(0, 0));
                    } catch (final Throwable throwable) {
                        result.completeExceptionally(throwable);
                    }
                });
        return result.get(5, TimeUnit.SECONDS);
    }

    private static int activateListItem(
            final ListView<?> listView, final String selectedText, final String requestedText) {
        final var needle = requestedText.isBlank() ? selectedText : requestedText;
        for (final var node : flatten(listView)) {
            if (!node.isVisible()) {
                continue;
            }
            final var text = textOf(node);
            if (!text.contains(needle) && !text.contains(selectedText)) {
                continue;
            }
            final var target = actionable(node);
            if (target instanceof ButtonBase button) {
                button.fire();
                return 1;
            }
        }
        return 0;
    }

    private static ActionResult selectIndex(
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
                    return selectIndex(target, index) ? target : null;
                });
    }

    private static boolean selectIndex(final Node node, final int index) {
        if (node instanceof ListView<?> listView && index < listView.getItems().size()) {
            listView.getSelectionModel().select(index);
            listView.scrollTo(index);
            layoutNow(listView);
            return true;
        }
        if (node instanceof TreeView<?> treeView && index < treeView.getExpandedItemCount()) {
            treeView.getSelectionModel().select(index);
            treeView.scrollTo(index);
            layoutNow(treeView);
            return true;
        }
        if (node instanceof TableView<?> tableView && index < tableView.getItems().size()) {
            tableView.getSelectionModel().select(index);
            tableView.scrollTo(index);
            layoutNow(tableView);
            return true;
        }
        if (node instanceof TreeTableView<?> treeTableView
                && index < treeTableView.getExpandedItemCount()) {
            treeTableView.getSelectionModel().select(index);
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
                    return expand(target, index, expanded) ? target : null;
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

    private record WebScriptResult(int count, String value) {}

    private static WebScriptResult webExecuteScript(
            final String query, final String script, final int highlightMs, final String effect)
            throws Exception {
        final var result = new CompletableFuture<WebScriptResult>();
        Platform.runLater(
                () -> {
                    try {
                        for (final var node : matchesFor(query)) {
                            if (!isWebView(node)) {
                                continue;
                            }
                            if (highlightMs > 0) {
                                highlightNode(node, effect);
                            }
                            final var engine = node.getClass().getMethod("getEngine").invoke(node);
                            final var value =
                                    engine.getClass()
                                            .getMethod("executeScript", String.class)
                                            .invoke(engine, script);
                            if (highlightMs > 0) {
                                clearHighlights();
                            }
                            result.complete(new WebScriptResult(1, String.valueOf(value)));
                            return;
                        }
                        result.complete(new WebScriptResult(0, ""));
                    } catch (final Throwable throwable) {
                        clearHighlights();
                        result.completeExceptionally(throwable);
                    }
                });
        return result.get(5, TimeUnit.SECONDS);
    }

    private static int waitFor(
            final String query,
            final boolean present,
            final int timeoutMs,
            final Boolean enabled,
            final Boolean focused,
            final Boolean visible)
            throws Exception {
        final var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            final var count = countMatches(query, enabled, focused, visible);
            if (present ? count > 0 : count == 0) {
                return count;
            }
            Thread.sleep(100);
        }
        return countMatches(query, enabled, focused, visible);
    }

    private static int countMatches(final String query) throws Exception {
        return countMatches(query, null, null, null);
    }

    private static int countMatches(
            final String query, final Boolean enabled, final Boolean focused, final Boolean visible)
            throws Exception {
        final var result = new CompletableFuture<Integer>();
        Platform.runLater(
                () -> {
                    try {
                        var count = 0;
                        for (final var node : matchesFor(query, visible)) {
                            if (enabled != null && (enabled.booleanValue() == node.isDisabled())) {
                                continue;
                            }
                            if (focused != null && focused.booleanValue() != node.isFocused()) {
                                continue;
                            }
                            if (visible != null && visible.booleanValue() != node.isVisible()) {
                                continue;
                            }
                            count++;
                        }
                        result.complete(count);
                    } catch (final Throwable throwable) {
                        result.completeExceptionally(throwable);
                    }
                });
        return result.get(5, TimeUnit.SECONDS);
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
                                    .append(window.isFocused());
                            for (final var node : flatten(window.getScene().getRoot())) {
                                if (!node.isVisible()) {
                                    continue;
                                }
                                final var bounds = node.getLayoutBounds();
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
                                        .append(textOf(node));
                            }
                        }
                        result.complete(Integer.toHexString(out.toString().hashCode()));
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
        final var result = new CompletableFuture<ActionResult>();
        Platform.runLater(
                () -> {
                    try {
                        final var matches =
                                raw ? rawMatchesFor(query, Boolean.TRUE) : matchesFor(query);
                        for (final var node : matches) {
                            if (highlightMs > 0) {
                                clearHighlights();
                                highlightNode(raw ? node : actionable(node), effect);
                                final var pause =
                                        new javafx.animation.PauseTransition(
                                                javafx.util.Duration.millis(highlightMs));
                                pause.setOnFinished(
                                        __ -> {
                                            try {
                                                final var target = action.apply(node);
                                                clearHighlights();
                                                result.complete(actionResult(target, matches));
                                            } catch (final Throwable throwable) {
                                                clearHighlights();
                                                result.completeExceptionally(throwable);
                                            }
                                        });
                                pause.play();
                                return;
                            }
                            final var target = action.apply(node);
                            if (target != null) {
                                result.complete(actionResult(target, matches));
                                return;
                            }
                        }
                        result.complete(actionResult(null, matches));
                    } catch (final Throwable throwable) {
                        result.completeExceptionally(throwable);
                    }
                });
        return result.get(5 + Math.max(0, highlightMs / 1000), TimeUnit.SECONDS);
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
        return "{\"jsonrpc\":\"2.0\",\"id\":"
                + id
                + ",\"result\":{"
                + fields
                + stateSuffix(request)
                + "}}";
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
                + value.ambiguityJson();
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
                + "\",\"text\":\""
                + jsonEscape(textOf(node))
                + "\"}";
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
                        + "\",\"text\":\""
                        + jsonEscape(text)
                        + "\",\"replace\":"
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
                            highlightNode(node, effect);
                            matches.add(describe(node));
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
        final Set<Node> matches = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (final var window : Window.getWindows()) {
            if (window.isShowing() && window.getScene() != null) {
                for (final var node : flatten(window.getScene().getRoot())) {
                    if ((visible == null || visible.booleanValue() == node.isVisible())
                            && matches(node, query)) {
                        matches.add(node);
                    }
                }
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
                    || current instanceof Accordion) {
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

    private static boolean matches(final Node node, final String rawQuery) {
        final var query = rawQuery.strip();
        if (query.isEmpty()) {
            return false;
        }
        if (query.startsWith("@")) {
            return matchesHandle(node, query.substring(1));
        }
        if (query.startsWith("eid=n")) {
            return matchesHandle(node, query.substring("eid=n".length()));
        }
        if (query.startsWith("eid=")) {
            return matchesHandle(node, query.substring("eid=".length()));
        }
        if (query.startsWith("=")) {
            return textOf(node).equals(query.substring(1));
        }
        if (query.startsWith("text=")) {
            return textOf(node).contains(query.substring("text=".length()));
        }
        if (query.startsWith("regexText=")) {
            return Pattern.compile(query.substring("regexText=".length()))
                    .matcher(textOf(node))
                    .find();
        }
        if (query.startsWith("type=")) {
            return typeMatches(node, query.substring("type=".length()));
        }
        if (query.startsWith("role=")) {
            return node.getAccessibleRole() != null
                    && node.getAccessibleRole()
                            .name()
                            .equalsIgnoreCase(query.substring("role=".length()));
        }
        if (query.startsWith("accessible=")) {
            return accessibleTextOf(node).contains(query.substring("accessible=".length()));
        }
        if (query.startsWith("#")) {
            return query.substring(1).equals(node.getId());
        }
        if (query.startsWith(".")) {
            return node.getStyleClass().contains(query.substring(1));
        }
        if (typeMatches(node, query)) {
            return true;
        }
        for (final var styleClass : node.getStyleClass()) {
            if (styleClass.equalsIgnoreCase(query)) {
                return true;
            }
        }
        return textOf(node).toLowerCase().contains(query.toLowerCase());
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

    private static boolean matchesHandle(final Node node, final String rawHandle) {
        var value = rawHandle;
        if (value.startsWith("n")) {
            value = value.substring(1);
        }
        try {
            return handle(node) == Integer.parseInt(value);
        } catch (final NumberFormatException exception) {
            return false;
        }
    }

    private static String accessibleTextOf(final Node node) {
        return node.getAccessibleText() == null ? "" : node.getAccessibleText();
    }

    private static String accessibleRoleOf(final Node node) {
        return node.getAccessibleRole() == null ? "" : node.getAccessibleRole().name();
    }

    private static String textOf(final Node node) {
        if (node instanceof TextInputControl input) {
            return (input.getPromptText() == null ? "" : input.getPromptText())
                    + " "
                    + (input instanceof PasswordField
                            ? maskedValue(input.getText())
                            : input.getText() == null ? "" : input.getText());
        }
        if (node instanceof Labeled labeled) {
            return labeled.getText() == null ? "" : labeled.getText();
        }
        if (node instanceof Text text && !hasLabeledAncestor(text)) {
            return text.getText() == null ? "" : text.getText();
        }
        return "";
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

    private static void highlightNode(final Node node, final String effect) {
        previousEffects.putIfAbsent(node, node.getEffect());
        node.setEffect(highlightEffectFor(node, effect));
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
        for (final var entry : previousEffects.entrySet()) {
            entry.getKey().setEffect(entry.getValue());
        }
        previousEffects.clear();
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
        final var actionable =
                actionable(node) != node
                        || node instanceof ButtonBase
                        || node instanceof TextInputControl
                        || node instanceof ListView<?>;
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
                + "\",\"accessibleHelp\":\""
                + jsonEscape(node.getAccessibleHelp() == null ? "" : node.getAccessibleHelp())
                + "\",\"accessibleRole\":\""
                + jsonEscape(accessibleRoleOf(node))
                + "\",\"accessibleRoleDescription\":\""
                + jsonEscape(
                        node.getAccessibleRoleDescription() == null
                                ? ""
                                : node.getAccessibleRoleDescription())
                + "\",\"visible\":"
                + node.isVisible()
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
        final var fields = new ArrayList<String>();
        addString(fields, "kind", semanticKind(node));
        if (node instanceof Control control) {
            addString(
                    fields,
                    "tooltip",
                    control.getTooltip() == null ? "" : control.getTooltip().getText());
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
            final var masked = input instanceof PasswordField;
            final var value = masked ? maskedValue(input.getText()) : input.getText();
            addString(fields, "value", value == null ? "" : value);
            addString(fields, "prompt", input.getPromptText() == null ? "" : input.getPromptText());
            fields.add("\"editable\":" + input.isEditable());
            addString(
                    fields,
                    "selectedText",
                    masked ? maskedValue(input.getSelectedText()) : input.getSelectedText());
            fields.add("\"selectionStart\":" + input.getSelection().getStart());
            fields.add("\"selectionEnd\":" + input.getSelection().getEnd());
            fields.add("\"caretPosition\":" + input.getCaretPosition());
            fields.add("\"anchor\":" + input.getAnchor());
            fields.add("\"length\":" + input.getLength());
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
            addString(fields, "value", String.valueOf(choiceBox.getValue()));
            fields.add("\"itemsCount\":" + choiceBox.getItems().size());
            fields.add("\"selectedIndex\":" + choiceBox.getSelectionModel().getSelectedIndex());
            addString(
                    fields,
                    "selectedItem",
                    String.valueOf(choiceBox.getSelectionModel().getSelectedItem()));
            fields.add("\"showing\":" + choiceBox.isShowing());
        }
        if (node instanceof ComboBoxBase<?> comboBoxBase) {
            addString(fields, "value", String.valueOf(comboBoxBase.getValue()));
            fields.add("\"showing\":" + comboBoxBase.isShowing());
            fields.add("\"editable\":" + comboBoxBase.isEditable());
            addString(fields, "converterClass", converterClass(comboBoxBase));
            if (comboBoxBase instanceof ComboBox<?> comboBox) {
                fields.add("\"itemsCount\":" + comboBox.getItems().size());
                fields.add("\"selectedIndex\":" + comboBox.getSelectionModel().getSelectedIndex());
                addString(
                        fields,
                        "selectedItem",
                        String.valueOf(comboBox.getSelectionModel().getSelectedItem()));
            }
            if (comboBoxBase instanceof DatePicker datePicker && datePicker.getValue() != null) {
                addString(fields, "localDate", datePicker.getValue().toString());
            }
            if (comboBoxBase instanceof ColorPicker colorPicker && colorPicker.getValue() != null) {
                addString(fields, "color", colorString(colorPicker.getValue()));
            }
        }
        if (node instanceof Spinner<?> spinner) {
            addString(fields, "value", String.valueOf(spinner.getValue()));
            fields.add("\"editable\":" + spinner.isEditable());
            addString(fields, "editorText", spinner.getEditor().getText());
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
            fields.add("\"itemsCount\":" + listView.getItems().size());
            fields.add(
                    "\"selectionMode\":\""
                            + listView.getSelectionModel().getSelectionMode().name()
                            + "\"");
            fields.add(
                    "\"selectedIndices\":"
                            + jsonInts(listView.getSelectionModel().getSelectedIndices()));
            fields.add(
                    "\"selectedItems\":"
                            + jsonLimitedStrings(
                                    listView.getSelectionModel().getSelectedItems(), 20));
            fields.add("\"focusedIndex\":" + listView.getFocusModel().getFocusedIndex());
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
            fields.add(
                    "\"selectionMode\":\""
                            + treeView.getSelectionModel().getSelectionMode().name()
                            + "\"");
            fields.add(
                    "\"selectedIndices\":"
                            + jsonInts(treeView.getSelectionModel().getSelectedIndices()));
            fields.add(
                    "\"selectedItems\":"
                            + jsonTreeItems(treeView.getSelectionModel().getSelectedItems(), 20));
            fields.add("\"focusedIndex\":" + treeView.getFocusModel().getFocusedIndex());
            fields.add("\"editable\":" + treeView.isEditable());
            fields.add("\"fixedCellSize\":" + jsonNumber(treeView.getFixedCellSize()));
            fields.add("\"rootVisible\":" + treeView.isShowRoot());
            addString(fields, "rootValue", treeItemValue(treeView.getRoot()));
        }
        if (node instanceof TableView<?> tableView) {
            fields.add("\"itemsCount\":" + tableView.getItems().size());
            fields.add(
                    "\"selectionMode\":\""
                            + tableView.getSelectionModel().getSelectionMode().name()
                            + "\"");
            fields.add(
                    "\"selectedIndices\":"
                            + jsonInts(tableView.getSelectionModel().getSelectedIndices()));
            fields.add(
                    "\"selectedItems\":"
                            + jsonLimitedStrings(
                                    tableView.getSelectionModel().getSelectedItems(), 20));
            fields.add("\"focusedIndex\":" + tableView.getFocusModel().getFocusedIndex());
            addString(
                    fields,
                    "focusedCell",
                    String.valueOf(tableView.getFocusModel().getFocusedCell()));
            fields.add("\"editable\":" + tableView.isEditable());
            fields.add("\"fixedCellSize\":" + jsonNumber(tableView.getFixedCellSize()));
            addString(
                    fields,
                    "placeholderText",
                    tableView.getPlaceholder() == null ? "" : textOf(tableView.getPlaceholder()));
            fields.add("\"columns\":" + jsonColumns(tableView.getColumns()));
            fields.add("\"sortOrder\":" + jsonColumns(tableView.getSortOrder()));
        }
        if (node instanceof TreeTableView<?> treeTableView) {
            fields.add("\"itemsCount\":" + treeTableView.getExpandedItemCount());
            fields.add("\"expandedItemCount\":" + treeTableView.getExpandedItemCount());
            fields.add(
                    "\"selectionMode\":\""
                            + treeTableView.getSelectionModel().getSelectionMode().name()
                            + "\"");
            fields.add(
                    "\"selectedIndices\":"
                            + jsonInts(treeTableView.getSelectionModel().getSelectedIndices()));
            fields.add(
                    "\"selectedItems\":"
                            + jsonTreeItems(
                                    treeTableView.getSelectionModel().getSelectedItems(), 20));
            fields.add("\"focusedIndex\":" + treeTableView.getFocusModel().getFocusedIndex());
            addString(
                    fields,
                    "focusedCell",
                    String.valueOf(treeTableView.getFocusModel().getFocusedCell()));
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
            fields.add("\"columns\":" + jsonColumns(treeTableView.getColumns()));
            fields.add("\"sortOrder\":" + jsonColumns(treeTableView.getSortOrder()));
        }
        if (node instanceof TabPane tabPane) {
            fields.add("\"tabsCount\":" + tabPane.getTabs().size());
            fields.add("\"selectedIndex\":" + tabPane.getSelectionModel().getSelectedIndex());
            if (tabPane.getSelectionModel().getSelectedItem() != null) {
                addString(
                        fields,
                        "selectedTabText",
                        tabPane.getSelectionModel().getSelectedItem().getText());
                addString(
                        fields,
                        "selectedTabId",
                        tabPane.getSelectionModel().getSelectedItem().getId());
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
            addString(fields, "text", titledPane.getText());
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

    private static String converterClass(final Object control) {
        try {
            final var converter = control.getClass().getMethod("getConverter").invoke(control);
            return converter == null ? "" : converter.getClass().getName();
        } catch (final ReflectiveOperationException exception) {
            return "";
        }
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
        try {
            final var engine = node.getClass().getMethod("getEngine").invoke(node);
            addString(fields, "location", stringMethod(engine, "getLocation"));
            addString(fields, "title", stringMethod(engine, "getTitle"));
            fields.add(
                    "\"zoom\":"
                            + jsonNumber(
                                    (double) node.getClass().getMethod("getZoom").invoke(node)));
            fields.add(
                    "\"fontScale\":"
                            + jsonNumber(
                                    (double)
                                            node.getClass()
                                                    .getMethod("getFontScale")
                                                    .invoke(node)));
            final var worker = engine.getClass().getMethod("getLoadWorker").invoke(engine);
            addString(
                    fields,
                    "loadState",
                    String.valueOf(worker.getClass().getMethod("getState").invoke(worker)));
        } catch (final ReflectiveOperationException exception) {
            addString(fields, "webViewError", exception.getClass().getSimpleName());
        }
    }

    private static String stringMethod(final Object target, final String method)
            throws ReflectiveOperationException {
        final var value = target.getClass().getMethod(method).invoke(target);
        return value == null ? "" : String.valueOf(value);
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

    private static String jsonInts(final List<Integer> values) {
        final var out = new StringBuilder();
        out.append('[');
        for (var i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(values.get(i));
        }
        out.append(']');
        return out.toString();
    }

    private static String jsonLimitedStrings(final List<?> values, final int limit) {
        final var out = new StringBuilder();
        out.append('[');
        for (var i = 0; i < values.size() && i < limit; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append('"').append(jsonEscape(String.valueOf(values.get(i)))).append('"');
        }
        out.append(']');
        return out.toString();
    }

    private static String jsonTreeItems(final List<? extends TreeItem<?>> values, final int limit) {
        final var out = new StringBuilder();
        out.append('[');
        for (var i = 0; i < values.size() && i < limit; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append('"').append(jsonEscape(treeItemValue(values.get(i)))).append('"');
        }
        out.append(']');
        return out.toString();
    }

    private static String treeItemValue(final TreeItem<?> item) {
        return item == null ? "" : String.valueOf(item.getValue());
    }

    private static String jsonColumns(final List<? extends TableColumnBase<?, ?>> columns) {
        final var out = new StringBuilder();
        out.append('[');
        for (var i = 0; i < columns.size(); i++) {
            if (i > 0) {
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
                    .append(jsonEscape(column.getText() == null ? "" : column.getText()))
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

    private static void addString(final List<String> fields, final String key, final String value) {
        if (value != null && !value.isBlank()) {
            fields.add("\"" + jsonEscape(key) + "\":\"" + jsonEscape(value) + "\"");
        }
    }

    private static String maskedValue(final String value) {
        return value == null || value.isEmpty() ? "" : "••••";
    }

    private static String jsonNumber(final double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "null";
        }
        return Double.toString(value);
    }

    private static int handle(final Node node) {
        return nodeIds.computeIfAbsent(node, __ -> nextNodeId++);
    }

    private static int windowHandle(final Window window) {
        return windowIds.computeIfAbsent(window, __ -> nextWindowId++);
    }

    private static int markStages() throws Exception {
        final var result = new CompletableFuture<Integer>();
        Platform.runLater(
                () -> {
                    try {
                        var count = 0;
                        for (final var window : Window.getWindows()) {
                            if (window instanceof Stage stage && stage.isShowing()) {
                                final var title = stage.getTitle() == null ? "" : stage.getTitle();
                                if (!title.startsWith(MARK)) {
                                    stage.setTitle(MARK + title);
                                }
                                count++;
                            }
                        }
                        result.complete(count);
                    } catch (final Throwable throwable) {
                        result.completeExceptionally(throwable);
                    }
                });
        return result.get(5, TimeUnit.SECONDS);
    }

    private static void recordEvent(
            final String method, final String request, final String response) {
        if (method == null
                || method.isBlank()
                || "events".equals(method)
                || "eventsSince".equals(method)) {
            return;
        }
        final var ok = !response.contains("\"error\"") && !response.contains("\"ok\":false");
        final var event =
                "{\"ts\":"
                        + System.currentTimeMillis()
                        + ",\"method\":\""
                        + jsonEscape(method)
                        + "\",\"ok\":"
                        + ok
                        + ",\"params\":"
                        + extractParamsJson(request)
                        + "}";
        synchronized (events) {
            events.add(event);
            while (events.size() > 500) {
                events.removeFirst();
            }
        }
    }

    private static String extractParamsJson(final String request) {
        final var body = httpBody(request);
        final var marker = "\"params\"";
        final var at = body.indexOf(marker);
        if (at < 0) {
            return "{}";
        }
        final var colon = body.indexOf(':', at + marker.length());
        if (colon < 0) {
            return "{}";
        }
        var start = colon + 1;
        while (start < body.length() && Character.isWhitespace(body.charAt(start))) {
            start++;
        }
        if (start >= body.length()) {
            return "{}";
        }
        final var first = body.charAt(start);
        if (first != '{' && first != '[') {
            return "{}";
        }
        final var close = first == '{' ? '}' : ']';
        var depth = 0;
        var inString = false;
        var escaped = false;
        for (var i = start; i < body.length(); i++) {
            final var c = body.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = inString;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == first) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    final var params = body.substring(start, i + 1);
                    return Json.hasInvalidStringEscape(params)
                            ? "{\"raw\":\"" + jsonEscape(params) + "\"}"
                            : params;
                }
            }
        }
        return "{}";
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
        socket.setSoTimeout(2_000);
        final var input = socket.getInputStream();
        final var buffer = new ByteArrayOutputStream();
        final var chunk = new byte[1024];
        var headerEnd = -1;
        var contentLength = 0;
        while (true) {
            final var read = input.read(chunk);
            if (read < 0) {
                break;
            }
            buffer.write(chunk, 0, read);
            final var request = buffer.toString(StandardCharsets.UTF_8);
            if (headerEnd < 0) {
                headerEnd = request.indexOf("\r\n\r\n");
                if (headerEnd >= 0) {
                    contentLength = contentLength(request.substring(0, headerEnd));
                }
            }
            if (headerEnd >= 0) {
                final var bodyBytes = buffer.size() - (headerEnd + 4);
                if (bodyBytes >= contentLength) {
                    return request;
                }
            }
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static int contentLength(final String headers) {
        for (final var line : headers.split("\r\n")) {
            final var colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).equalsIgnoreCase("content-length")) {
                return Integer.parseInt(line.substring(colon + 1).strip());
            }
        }
        return 0;
    }

    private static String extractId(final String body) {
        return Json.id(body);
    }

    private static int extractInt(final String body, final String key, final int fallback) {
        return Json.intValue(body, key, fallback);
    }

    private static long extractLong(final String body, final String key, final long fallback) {
        try {
            return Long.parseLong(Json.scalar(body, key));
        } catch (final RuntimeException exception) {
            return fallback;
        }
    }

    private static boolean hasKey(final String body, final String key) {
        return Json.hasKey(body, key);
    }

    private static boolean extractBoolean(
            final String body, final String key, final boolean fallback) {
        return Json.booleanValue(body, key, fallback);
    }

    private static String extractString(final String body, final String key) {
        return Json.string(body, key);
    }

    private static List<String> extractStringArray(final String body, final String key) {
        return Json.stringArray(body, key);
    }

    private static String jsonStrings(final List<String> values) {
        return Json.strings(values);
    }

    private static String jsonEscape(final String value) {
        return Json.escape(value);
    }
}
