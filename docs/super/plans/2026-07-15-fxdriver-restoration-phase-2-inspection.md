# fxdriver Inspection Restoration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use /skill:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the kitchen-sink probe with focused test applications and restore bounded snapshot summary, keyboard, menu, table-cell, near-match, and screenshot-orientation APIs.

**Architecture:** A package-private test harness owns only process launch, endpoint discovery, RPC, artifact paths, and cleanup. Focused forms, data, and WebView-smoke applications provide deterministic workflows with semantic JavaFX IDs. Restored RPCs remain small methods inside the current agent, reuse existing selector/window/JSON helpers, and preserve current `run`, `returnState`, action diagnostics, and explicit value-selection APIs.

**Tech Stack:** Java 21, JavaFX 21/26, JUnit 6, Maven Failsafe, existing `Json` and JSON-RPC transport, Spotless, mise.

**Roadmap:** `docs/super/roadmaps/2026-07-15-fxdriver-restoration-roadmap.md`

**Phase:** Phase 2: Replace the Kitchen-Sink Fixture and Restore Inspection APIs

---

## Scope boundary

This plan restores only Phase 2. It does not change agent bootstrap, modal dispatch acknowledgement, event severity/tagging, native-dialog boundaries, GIF/APNG recording, or video APIs. Keep `setValue`, `selectIndex`, `selectListItem`, `scrollToIndex`, `expand`, and `collapse`; never add generic `select`.

The phase adds two focused applications and retains a reduced compatibility probe:

- `FxDriverFormsApp`: settings/editor workflow and value controls.
- `FxDriverDataApp`: project browser workflow and data/menu/table diagnostics.
- `FxDriverProbeApp`: only process, WebView, secondary-window, popup, SubScene, escaped-diagnostic, and existing protocol smoke coverage that belongs to later phases. Phase 5 performs final deletion.

All fixtures stay under test sources/resources and are never packaged.

### Task 1: Extract the shared process/RPC harness

**Files:**

- Create: `skills/fxdriver/src/test/java/fxdriver/FxDriverTestHarness.java`
- Modify: `skills/fxdriver/src/test/java/fxdriver/FxDriverProbeIT.java`
- Modify: `skills/fxdriver/src/test/java/fxdriver/FxDriverFailureExamplesIT.java`
- Modify: `skills/fxdriver/src/test/java/fxdriver/FxDriverBenchmarkIT.java`

- [ ] **Step 1: Add the shared harness without changing behavior**

Create `FxDriverTestHarness` as a package-private test utility. It owns only process, endpoint, RPC, artifacts, JavaFX command construction, and cleanup:

```java
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

    private FxDriverTestHarness() {}

    static Session launch(final Class<?> application, final String artifactName, final String... args)
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

    static Process startDirect(
            final Class<?> application, final Path out, final String... args) throws IOException {
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

    static String rpc(final Endpoint endpoint, final String method, final String params)
            throws Exception {
        return rawRpc(endpoint, method, params).body();
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

    static Response rawRpc(final Endpoint endpoint, final String method, final String params)
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
                        .timeout(Duration.ofSeconds(10))
                        .header("content-type", "application/json")
                        .header("fxdriver-token", endpoint.token())
                        .method(
                                "POST",
                                HttpRequest.BodyPublishers.ofByteArray(
                                        body.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .build();
        final var response =
                HttpClient.newHttpClient()
                        .send(request, HttpResponse.BodyHandlers.ofByteArray());
        return new Response(
                response.statusCode(),
                new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
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
        return Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        isWindows() ? "java.exe" : "java")
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
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    record Endpoint(int port, String token) {}

    record Response(int status, String body) {}

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
```

- [ ] **Step 2: Migrate current integration tests to the harness**

Replace private process/RPC/module-path/cleanup helpers in `FxDriverProbeIT`, `FxDriverFailureExamplesIT`, and `FxDriverBenchmarkIT` with `FxDriverTestHarness`. Keep every current `FxDriverProbeIT` assertion in place. In particular, retain `press`, secondary-window, popup, SubScene, configure/highlight/assert/events, escaped target diagnostics, attach, noisy launch, and WebView coverage. Preserve benchmark response byte/timing measurements by using `rawRpc(...).status()` and `.body()`.

Each IT class defines only its domain helpers. For example:

```java
private String call(final String method, final String params) throws Exception {
    return FxDriverTestHarness.rpc(session.endpoint(), method, params);
}

private void assertOk(final String method, final String params) throws Exception {
    final var response = call(method, params);
    assertTrue(response.contains("\"ok\":true"), response);
}
```

Run:

```sh
cd skills/fxdriver
node ../../scripts/sh.mjs ./mvnw -q spotless:apply
node ../../scripts/sh.mjs ./mvnw --batch-mode -Dit.test=FxDriverProbeIT,FxDriverFailureExamplesIT,FxDriverBenchmarkIT verify
```

Expected: PASS; the benchmark remains skipped unless `-Dfxdriver.benchmark=true` is supplied.

- [ ] **Step 3: Commit the harness extraction**

```sh
git add skills/fxdriver/src/test/java/fxdriver/FxDriverTestHarness.java \
  skills/fxdriver/src/test/java/fxdriver/FxDriverProbeIT.java \
  skills/fxdriver/src/test/java/fxdriver/FxDriverFailureExamplesIT.java \
  skills/fxdriver/src/test/java/fxdriver/FxDriverBenchmarkIT.java
git commit -m "test(fxdriver): extract process harness"
```

### Task 2: Add the realistic forms fixture and migrate form workflows

**Files:**

- Create: `skills/fxdriver/src/test/java/fxdriver/FxDriverFormsApp.java`
- Create: `skills/fxdriver/src/test/java/fxdriver/FxDriverFormsIT.java`
- Create: `skills/fxdriver/src/test/resources/fxdriver/fixtures.css`

- [ ] **Step 1: Build the deterministic settings/editor fixture**

Create `FxDriverFormsApp` as a `BorderPane` settings screen:

- top `ToolBar`: `settings-save`, `settings-reset`;
- center `ScrollPane` containing grouped `GridPane` form sections;
- bottom status label `settings-status`;
- controls: `profile-name`, `profile-password`, `profile-notes`, `notifications-enabled`, `expert-mode`, radio buttons `release-channel-stable`/`release-channel-beta`, `theme-choice`, `density-combo`, `review-date`, `accent-color`, `retention-days`, `zoom-level`;
- validation label `profile-validation` and an intentionally disabled `settings-export` button;
- fixed initial values: `Ada`, `secret`, `Release notes`, selected notifications, disabled expert mode, stable release channel, `System`, `Comfortable`, `2026-07-15`, `#336699`, `30`, and `100`;
- bind `settings-save.disableProperty()` to an empty trimmed name and update `profile-validation` to `Name is required`/empty as the name changes;
- save sets `settings-status` to `Saved settings for <name>`;
- reset restores every initial value, clears validation, and sets status to `Reset settings`.

Use `BorderPane`, `ToolBar`, `ScrollPane`, `VBox`, `GridPane`, and titled section labels. Give only workflow controls semantic IDs. Load `/fxdriver/fixtures.css`. The stylesheet must contain only stable fixture sizing/spacing:

```css
.root { -fx-font-size: 13px; }
.fixture-page { -fx-padding: 16px; -fx-spacing: 14px; }
.fixture-section { -fx-padding: 14px; -fx-hgap: 12px; -fx-vgap: 10px; }
.fixture-status { -fx-padding: 8px 14px; }
.fixture-table { -fx-pref-height: 240px; }
```

Do not open a confirmation dialog in this phase; JavaFX modal dispatch belongs to Phase 3.

- [ ] **Step 2: Add forms integration coverage for existing APIs**

Create `FxDriverFormsIT` with one shared harness session (`@TestInstance(PER_CLASS)`, `@BeforeAll`, `@AfterAll`). `@BeforeAll` launches the app and calls `FxDriverTestHarness.awaitVisible(session.endpoint(), "profile-name")` before any action. Add tests that:

```java
assertOk("setText", "{\"nodeId\":\"profile-name\",\"value\":\"Grace\"}");
assertOk("type", "{\"nodeId\":\"profile-name\",\"value\":\" Hopper\",\"replace\":false}");
assertOk("clear", "{\"nodeId\":\"profile-notes\"}");
assertOk("setValue", "{\"nodeId\":\"theme-choice\",\"value\":\"Dark\"}");
assertOk("setValue", "{\"nodeId\":\"review-date\",\"value\":\"2026-07-16\"}");
assertOk("setValue", "{\"nodeId\":\"zoom-level\",\"value\":\"125\"}");
assertOk("increment", "{\"nodeId\":\"retention-days\",\"steps\":2}");
assertOk("showPopup", "{\"nodeId\":\"density-combo\"}");
assertOk("hidePopup", "{\"nodeId\":\"density-combo\"}");
assertOk("fire", "{\"nodeId\":\"settings-save\"}");
assertContains("wait", "{\"textExact\":\"Saved settings for Grace Hopper\"}", "\"ok\":true");
```

Also assert radio/toggle/check state, disabled `settings-export`, name validation and save disabled state, compact snapshot redaction for `profile-password`, per-step target diagnostics in `run`, and `profile-name` in `returnState`.

Define the test helpers in `FxDriverFormsIT`:

```java
private String call(final String method, final String params) throws Exception {
    return FxDriverTestHarness.rpc(session.endpoint(), method, params);
}

private void assertOk(final String method, final String params) throws Exception {
    final var response = call(method, params);
    assertTrue(response.contains("\"ok\":true"), response);
}

private void assertContains(final String method, final String params, final String expected)
        throws Exception {
    final var response = call(method, params);
    assertTrue(response.contains(expected), response);
}

private void assertWait(final String text) throws Exception {
    assertContains("wait", "{\"textExact\":\"" + text + "\",\"timeoutMs\":1000}", "\"ok\":true");
}
```

- [ ] **Step 3: Run the focused forms suite**

```sh
cd skills/fxdriver
node ../../scripts/sh.mjs ./mvnw -q spotless:apply
node ../../scripts/sh.mjs ./mvnw --batch-mode -Dit.test=FxDriverFormsIT verify
```

Expected: PASS using only existing production methods.

- [ ] **Step 4: Commit the forms fixture**

```sh
git add skills/fxdriver/src/test/java/fxdriver/FxDriverFormsApp.java \
  skills/fxdriver/src/test/java/fxdriver/FxDriverFormsIT.java \
  skills/fxdriver/src/test/resources/fxdriver/fixtures.css
git commit -m "test(fxdriver): add focused forms workflow"
```

### Task 3: Add the realistic data fixture and reduce the kitchen-sink probe

**Files:**

- Create: `skills/fxdriver/src/test/java/fxdriver/FxDriverDataApp.java`
- Create: `skills/fxdriver/src/test/java/fxdriver/FxDriverDataIT.java`
- Modify: `skills/fxdriver/src/test/java/fxdriver/FxDriverFailureExamplesIT.java`
- Modify: `skills/fxdriver/src/test/java/fxdriver/FxDriverBenchmarkIT.java`
- Modify: `skills/fxdriver/src/test/java/fxdriver/FxDriverProbeApp.java`
- Modify: `skills/fxdriver/src/test/java/fxdriver/FxDriverProbeIT.java`

- [ ] **Step 1: Build the deterministic project-browser fixture**

Create `FxDriverDataApp` with:

- `BorderPane` root and title `Project Browser`;
- top `MenuBar` (`project-menu`) plus `MenuButton` (`project-actions`) and `SplitMenuButton` (`project-more-actions`);
- left navigation `TreeView` (`project-tree`) and `ListView` (`recent-projects`);
- center `TabPane` (`project-tabs`) containing `TableView<Project>` (`project-table`) and `TreeTableView<Project>` (`project-tree-table`);
- table columns `project-number`, `project-name`, `project-status`;
- top filter `project-filter`, `ChoiceBox` (`project-view`), and two buttons with the duplicate text `Open` and IDs `open-primary`/`open-secondary`;
- bottom `Pagination` (`project-pages`), `TitledPane` (`project-details`), and status label `project-status`;
- a button `project-refresh` and state label `project-refresh-state` whose 20 ms `Timeline` changes text for two seconds;
- a `ProgressIndicator` (`project-loading`) that is visible only while the refresh timeline runs;
- a `ToolBar` containing an intentionally ID-less `Refresh toolbar` button used only to verify missing-ID diagnostics;
- a context menu on `project-table` containing `Archive`;
- an enabled `Delete` item and a separate disabled `Delete permanently` item under `project-actions`;
- fixed rows `PRJ-101 Atlas Active`, `PRJ-102 Borealis Review`, and `PRJ-103 Cygnus Archived`.

Menu handlers update status to `Saved workspace`, `Deleted project`, `Exported project`, or `Archived project`. Clicking a realized recent-project row updates status to `Opened <name>`. `Project.toString()` must include number, name, and status so table row-text lookup is deterministic.

Keep the active refresh timeline in a one-element holder. Add `project-reset`; its handler stops the active timeline, hides `project-loading`, restores `project-refresh-state` to `stable`, resets status/filter/selection, and is safe before the first refresh.

- [ ] **Step 2: Migrate existing data/control diagnostics**

Create `FxDriverDataIT` with a shared harness session. `@BeforeAll` launches the app and calls `FxDriverTestHarness.awaitVisible(session.endpoint(), "project-table")` before any action. Define:

```java
private String call(final String method, final String params) throws Exception {
    return FxDriverTestHarness.rpc(session.endpoint(), method, params);
}

private void assertOk(final String method, final String params) throws Exception {
    final var response = call(method, params);
    assertTrue(response.contains("\"ok\":true"), response);
}

private void assertContains(final String method, final String params, final String expected)
        throws Exception {
    final var response = call(method, params);
    assertTrue(response.contains(expected), response);
}

private void assertWait(final String text) throws Exception {
    assertContains("wait", "{\"textExact\":\"" + text + "\",\"timeoutMs\":1000}", "\"ok\":true");
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
```

Add `@BeforeEach` to isolate shared-session state:

```java
@BeforeEach
void resetDataApp() throws Exception {
    assertOk("fire", "{\"nodeId\":\"project-reset\"}");
    assertWait("stable");
}
```

Test:

- `listItems`, `selectListItem`, `selectIndex`, `scrollToIndex`, `expand`, `collapse`, `scroll`;
- nine deterministic duplicate `Open` buttons are not needed: use the two realistic `Open` buttons and assert `matches:2` plus two bounded ambiguity entries;
- selector-path stable-ID suffix and no path-selector capability;
- normal click has no quiet field;
- quiet click succeeds on a stable action;
- refresh click returns `QUIET_TIMEOUT` between 400 and 1,000 ms for `untilQuietMs:200,timeoutMs:450`;
- misses preserve `target:null,matches:0`;
- delayed highlighting preserves target diagnostics.

- [ ] **Step 3: Point failure examples and benchmark at the data fixture**

Update `FxDriverFailureExamplesIT` selectors to `project-filter`, `project-view`, `recent-projects`, and the intentionally ID-less `Refresh toolbar` item. Change its typo wait to `Refresh toolbr`; before near matches exist it still asserts `TIMEOUT` and uses a full snapshot follow-up to find `Refresh toolbar`. Preserve screenshot/diff and failure-event assertions.

Update `FxDriverBenchmarkIT` to launch `FxDriverDataApp` and benchmark one coherent data workflow: filter text, select recent project, select table row, scroll tree, expand/collapse details, and run the same operations with `returnState`. Remove choice/spinner/WebView actions from the benchmark rather than recreating a mixed fixture.

- [ ] **Step 4: Reduce the old probe and verify coverage**

Delete migrated form, value-control, list, tree, table, async-state, and duplicate-button sections from `FxDriverProbeApp` and their assertions from `FxDriverProbeIT`. Keep only the primary smoke button/field, escaped diagnostic, F1 accelerator, WebView, SubScene, secondary stage, popup, noisy-output mode, and configure/highlight/assert/events/process coverage. Phase 5 deletes this residual fixture after the windows and visual suites exist.

Search migrated IDs:

```sh
rg 'probe-(list|table|tree|choice|combo|spinner|slider|unstable|duplicate)' skills/fxdriver/src/test/java
```

Expected: no matches. `probe-web`, `probe-subscene-button`, `probe-secondary-button`, and process-smoke IDs remain intentionally.

Run:

```sh
cd skills/fxdriver
node ../../scripts/sh.mjs ./mvnw -q spotless:apply
node ../../scripts/sh.mjs ./mvnw --batch-mode -Dit.test=FxDriverDataIT,FxDriverFailureExamplesIT verify
node ../../scripts/sh.mjs ./mvnw --batch-mode package -Dfxdriver.benchmark=true -Dit.test=FxDriverBenchmarkIT failsafe:integration-test
```

Expected: PASS.

- [ ] **Step 5: Commit the data fixture migration**

```sh
git add skills/fxdriver/src/test/java/fxdriver/FxDriverDataApp.java \
  skills/fxdriver/src/test/java/fxdriver/FxDriverDataIT.java \
  skills/fxdriver/src/test/java/fxdriver/FxDriverFailureExamplesIT.java \
  skills/fxdriver/src/test/java/fxdriver/FxDriverBenchmarkIT.java \
  skills/fxdriver/src/test/java/fxdriver/FxDriverProbeApp.java \
  skills/fxdriver/src/test/java/fxdriver/FxDriverProbeIT.java
git commit -m "test(fxdriver): split focused fixture workflows"
```

### Task 4: Restore bounded snapshot summary and its CLI path

**Files:**

- Modify: `skills/fxdriver/src/test/java/fxdriver/FxDriverDataIT.java`
- Modify: `skills/fxdriver/src/test/java/fxdriver/FxDriverProbeIT.java`
- Modify: `skills/fxdriver/src/main/java/fxdriver/FxDriver.java`
- Modify: `skills/fxdriver/src/main/java/fxdriver/FxDriverAgent.java`

- [ ] **Step 1: Add failing summary RPC and CLI assertions**

In `FxDriverDataIT`, first call `setText` on `project-filter` so focus is deterministic, then call `snapshotSummary` and assert:

```java
final var summary = call("snapshotSummary", "{}");
assertTrue(summary.contains("\"windows\":"), summary);
assertTrue(summary.contains("\"buttons\":"), summary);
assertTrue(summary.contains("\"textFields\":"), summary);
assertTrue(summary.contains("\"tables\":"), summary);
assertTrue(summary.contains("\"selectedTabs\":"), summary);
assertTrue(summary.contains("\"focusedNode\":"), summary);
assertTrue(summary.contains("\"visibleTextSample\":"), summary);
assertTrue(summary.contains("Project Browser"), summary);
assertTrue(summary.contains("project-filter"), summary);
assertTrue(summary.length() < 16_384, "summary bytes=" + summary.length());
```

In `FxDriverProbeIT`, start an additional data session for this test and execute both CLI forms against it:

```text
java -jar target/fxdriver.jar snapshot <port> <token>
java -jar target/fxdriver.jar snapshot <port> --summary <token>
```

Assert exit `0`, JSON output, and that malformed/missing port exits `2`.

- [ ] **Step 2: Run RED**

```sh
cd skills/fxdriver
node ../../scripts/sh.mjs ./mvnw --batch-mode -Dit.test=FxDriverDataIT,FxDriverProbeIT verify
```

Expected: FAIL with `METHOD_NOT_FOUND` and snapshot CLI usage.

- [ ] **Step 3: Add the CLI command**

In `FxDriver.main`, add `case "snapshot" -> snapshot(args);`. Implement:

```java
private static void snapshot(final String... args) throws Exception {
    final var summary = args.length >= 3 && "--summary".equals(args[2]);
    final var unknownOption = args.length >= 3 && args[2].startsWith("--") && !summary;
    if (args.length < 2 || unknownOption || summary && args.length > 4 || !summary && args.length > 3) {
        snapshotUsage();
        return;
    }
    final int port;
    try {
        port = Integer.parseInt(args[1]);
    } catch (final NumberFormatException exception) {
        snapshotUsage();
        return;
    }
    final var tokenIndex = summary ? 3 : 2;
    final var body =
            rpcBody(port, tokenArg(args, tokenIndex), summary ? "snapshotSummary" : "snapshot", "{}");
    System.out.println(body);
    if (body.contains("\"error\"")) {
        System.exit(1);
    }
}

private static void snapshotUsage() {
    System.err.println("usage: fxdriver snapshot <port> [--summary] [token]");
    System.exit(2);
}
```

Add the exact command to `usage()`.

- [ ] **Step 4: Add bounded summary generation**

Add `snapshotSummary` to `METHODS` and `dispatch`; add `snapshot-summary` to production `capabilitiesResponse`; and update the exact method/feature arrays in `FxDriverProbeIT` in the same task (`snapshotSummary` and `snapshot-summary`). Build the summary on the FX thread. Use fixed caps: 8 windows, 24 buttons, 16 text fields, 12 tables/tree tables, 12 selected tabs, one focused node, and 40 distinct visible text strings. Truncate every emitted text/value to 160 characters.

Use these helpers and shapes:

```java
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
    final var tables = new ArrayList<String>();
    final var selectedTabs = new ArrayList<String>();
    final var visibleText = new java.util.LinkedHashSet<String>();
    String focusedNode = "null";
    for (final var window : Window.getWindows()) {
        if (!window.isShowing() || window.getScene() == null) {
            continue;
        }
        addCapped(windows, orientationWindowJson(window), 8);
        final var focusOwner = window.getScene().getFocusOwner();
        if (focusOwner != null && focusOwner.isVisible() && "null".equals(focusedNode)) {
            focusedNode = summaryNodeJson(window, focusOwner);
        }
        for (final var node : flatten(window.getScene().getRoot())) {
            if (!node.isVisible()) {
                continue;
            }
            final var text = truncate(textOf(node), 160);
            if (!text.isBlank() && visibleText.size() < 40) {
                visibleText.add(text);
            }
            if (node instanceof ButtonBase button) {
                addCapped(buttons, summaryButtonJson(window, button), 24);
            } else if (node instanceof TextInputControl input) {
                addCapped(textFields, summaryInputJson(window, input), 16);
            } else if (node instanceof TableView<?> table) {
                addCapped(tables, summaryTableJson(window, table), 12);
            } else if (node instanceof TreeTableView<?> table) {
                addCapped(tables, summaryTreeTableJson(window, table), 12);
            } else if (node instanceof TabPane tabs && tabs.getSelectionModel().getSelectedItem() != null) {
                addCapped(selectedTabs, summaryTabJson(window, tabs), 12);
            }
        }
    }
    return "{\"windows\":["
            + String.join(",", windows)
            + "],\"buttons\":["
            + String.join(",", buttons)
            + "],\"textFields\":["
            + String.join(",", textFields)
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

private static String orientationWindowJson(final Window window) {
    final var title = window instanceof Stage stage ? stage.getTitle() : "";
    return "{\"wid\":\"w" + windowHandle(window)
            + "\",\"title\":\"" + jsonEscape(truncate(title, 160))
            + "\",\"focused\":" + window.isFocused()
            + ",\"bounds\":{" + boundsJson(
                    Math.round(window.getX()), Math.round(window.getY()),
                    Math.round(window.getWidth()), Math.round(window.getHeight())) + "}}";
}

private static String summaryNodeJson(final Window window, final Node node) {
    return "{\"eid\":\"n" + handle(node)
            + "\",\"window\":\"w" + windowHandle(window)
            + "\",\"type\":\"" + jsonEscape(node.getClass().getSimpleName())
            + "\",\"id\":\"" + jsonEscape(truncate(node.getId(), 160))
            + "\",\"text\":\"" + jsonEscape(truncate(textOf(node), 160)) + "\"}";
}

private static String summaryButtonJson(final Window window, final ButtonBase button) {
    return summaryNodeJson(window, button).replaceFirst("}$", "")
            + ",\"disabled\":" + button.isDisabled() + "}";
}

private static String summaryInputJson(final Window window, final TextInputControl input) {
    final var value = input instanceof PasswordField ? maskedValue(input.getText()) : input.getText();
    return summaryNodeJson(window, input).replaceFirst("}$", "")
            + ",\"value\":\"" + jsonEscape(truncate(value, 160))
            + "\",\"prompt\":\"" + jsonEscape(truncate(input.getPromptText(), 160))
            + "\",\"editable\":" + input.isEditable() + "}";
}

private static String summaryTableJson(final Window window, final TableView<?> table) {
    return summaryNodeJson(window, table).replaceFirst("}$", "")
            + ",\"kind\":\"table\",\"rows\":" + table.getItems().size()
            + ",\"columns\":" + summaryColumnsJson(table.getColumns()) + "}";
}

private static String summaryTreeTableJson(final Window window, final TreeTableView<?> table) {
    return summaryNodeJson(window, table).replaceFirst("}$", "")
            + ",\"kind\":\"treeTable\",\"rows\":" + table.getExpandedItemCount()
            + ",\"columns\":" + summaryColumnsJson(table.getColumns()) + "}";
}

private static String summaryTabJson(final Window window, final TabPane tabs) {
    return summaryNodeJson(window, tabs).replaceFirst("}$", "")
            + ",\"selected\":\""
            + jsonEscape(truncate(tabs.getSelectionModel().getSelectedItem().getText(), 160))
            + "\"}";
}

private static String summaryColumnsJson(
        final List<? extends TableColumnBase<?, ?>> columns) {
    final var values = new ArrayList<String>();
    for (var i = 0; i < columns.size() && i < 16; i++) {
        final var column = columns.get(i);
        values.add("{\"index\":" + i + ",\"id\":\""
                + jsonEscape(truncate(column.getId(), 160)) + "\",\"text\":\""
                + jsonEscape(truncate(column.getText(), 160)) + "\"}");
    }
    return "[" + String.join(",", values) + "]";
}
```

`windowJson` is intentionally an open object fragment used by full snapshots; summary and screenshot orientation metadata must use the fully closed, text-bounded `orientationWindowJson`. Do not include node hierarchy or selector paths in summary mode.

- [ ] **Step 5: Run GREEN and commit**

```sh
cd skills/fxdriver
node ../../scripts/sh.mjs ./mvnw -q spotless:apply
node ../../scripts/sh.mjs ./mvnw --batch-mode -Dit.test=FxDriverDataIT,FxDriverProbeIT verify
git add src/main/java/fxdriver/FxDriver.java src/main/java/fxdriver/FxDriverAgent.java \
  src/test/java/fxdriver/FxDriverDataIT.java src/test/java/fxdriver/FxDriverProbeIT.java
git commit -m "feat(fxdriver): restore snapshot summary"
```

### Task 5: Restore focused named keys, chords, and literal characters

**Files:**

- Modify: `skills/fxdriver/src/test/java/fxdriver/FxDriverFormsApp.java`
- Modify: `skills/fxdriver/src/test/java/fxdriver/FxDriverFormsIT.java`
- Modify: `skills/fxdriver/src/test/java/fxdriver/FxDriverProbeIT.java`
- Modify: `skills/fxdriver/src/main/java/fxdriver/FxDriverAgent.java`

- [ ] **Step 1: Add deterministic key observers and failing tests**

In `FxDriverFormsApp`, add event filters to `profile-name`:

```java
name.addEventFilter(
        KeyEvent.KEY_PRESSED,
        event -> {
            if (event.getCode() == KeyCode.S && event.isControlDown()) {
                status.setText("Keyboard save");
            } else if (event.getCode() == KeyCode.ENTER) {
                status.setText("Submitted " + name.getText());
            }
        });
name.addEventFilter(
        KeyEvent.KEY_TYPED,
        event -> status.setText("Typed " + event.getCharacter()));
```

Add `@BeforeEach` so every keyboard test has deterministic state regardless of JUnit order:

```java
@BeforeEach
void resetForm() throws Exception {
    assertOk("fire", "{\"nodeId\":\"settings-reset\"}");
    assertOk("setText", "{\"nodeId\":\"profile-name\",\"value\":\"Grace Hopper\"}");
}
```

Add forms IT assertions for:

```java
assertOk("key", "{\"nodeId\":\"profile-name\",\"key\":\"ENTER\"}");
assertWait("Submitted Grace Hopper");
assertOk("key", "{\"nodeId\":\"profile-name\",\"key\":\"CTRL+S\"}");
assertWait("Keyboard save");
final var chars = call("key", "{\"nodeId\":\"profile-name\",\"chars\":\"xy\"}");
assertTrue(chars.contains("\"sent\":2"), chars);
assertTrue(chars.contains("\"target\":"), chars);
```

Also test focused-target fallback with no selector and `ESC` alias. An explicit missing selector (`nodeId:"missing"`) returns `ok:false` with `NO_FOCUS`, not a protocol error.

- [ ] **Step 2: Run RED**

```sh
cd skills/fxdriver
node ../../scripts/sh.mjs ./mvnw --batch-mode -Dit.test=FxDriverFormsIT verify
```

Expected: FAIL with `METHOD_NOT_FOUND` for `key`.

- [ ] **Step 3: Implement synchronous key dispatch**

Add `key` to `METHODS`/`dispatch`, add `keyboard` to production `capabilitiesResponse`, and update the exact method/feature arrays in `FxDriverProbeIT` in the same task (`key` and `keyboard`). Parse modifiers explicitly; aliases are `ESC -> ESCAPE`, `CMD -> META`, and hyphens normalize to underscores. The last non-modifier token is the `KeyCode`.

```java
private record ParsedKey(
        String value, KeyCode code, boolean shift, boolean control, boolean alt, boolean meta) {}

private record KeyResult(Node target, String key, String chars, int sent) {}

private static ParsedKey parseKey(final String value) {
    var shift = false;
    var control = false;
    var alt = false;
    var meta = false;
    String code = "";
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
    return new ParsedKey(value, KeyCode.valueOf(code), shift, control, alt, meta);
}
```

Resolve target and dispatch with these concrete helpers:

```java
private static boolean hasTargetSelector(final String request) {
    return hasKey(request, "handle") || hasKey(request, "eid") || hasKey(request, "nodeId")
            || hasKey(request, "cssId") || hasKey(request, "styleClass")
            || hasKey(request, "textExact") || hasKey(request, "text")
            || hasKey(request, "regexText") || hasKey(request, "accessible")
            || hasKey(request, "role") || hasKey(request, "selector") || hasKey(request, "type");
}

private static Node keyTarget(final String request) {
    if (hasTargetSelector(request)) {
        final var matches = matchesFor(highlightQueryOrDefault(request, ""));
        return matches.isEmpty() ? null : matches.getFirst();
    }
    final var windows = new ArrayList<>(Window.getWindows());
    Collections.reverse(windows);
    for (final var window : windows) {
        if (window.isShowing() && window.isFocused() && window.getScene() != null
                && window.getScene().getFocusOwner() != null) {
            return window.getScene().getFocusOwner();
        }
    }
    for (final var window : windows) {
        if (window.isShowing() && window.getScene() != null
                && window.getScene().getFocusOwner() != null) {
            return window.getScene().getFocusOwner();
        }
    }
    return null;
}

private static String keyResponse(final String id, final String request) throws Exception {
    final var target = onFxThread(() -> keyTarget(request));
    if (target == null) {
        return resultResponse(id, "\"key\":\"\",\"chars\":\"\",\"sent\":0,\"target\":null"
                + okOrError(false, "NO_FOCUS", "No keyboard target was focused or matched"), request);
    }
    final var chars = extractString(request, "chars");
    final var key = extractString(request, "key");
    final var sent = onFxThread(() -> dispatchKeyboard(target, key, chars));
    return resultResponse(id, "\"key\":\"" + jsonEscape(key) + "\",\"chars\":\""
            + jsonEscape(chars) + "\",\"sent\":" + sent + ",\"target\":" + targetJson(target)
            + okOrError(sent > 0, "NO_KEY", "key requires key or chars"), request);
}

private static <T> T onFxThread(final java.util.concurrent.Callable<T> action) throws Exception {
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

private static int dispatchKeyboard(final Node target, final String key, final String chars) {
    target.requestFocus();
    if (!chars.isBlank()) {
        var sent = 0;
        for (final var codePoint : chars.codePoints().toArray()) {
            final var character = new String(Character.toChars(codePoint));
            target.fireEvent(new KeyEvent(
                    KeyEvent.KEY_TYPED, character, character, KeyCode.UNDEFINED,
                    false, false, false, false));
            sent++;
        }
        return sent;
    }
    if (key.isBlank()) return 0;
    final var parsed = parseKey(key);
    target.fireEvent(new KeyEvent(
            KeyEvent.KEY_PRESSED, "", "", parsed.code(), parsed.shift(), parsed.control(),
            parsed.alt(), parsed.meta()));
    target.fireEvent(new KeyEvent(
            KeyEvent.KEY_RELEASED, "", "", parsed.code(), parsed.shift(), parsed.control(),
            parsed.alt(), parsed.meta()));
    return 2;
}
```

Keep existing `press` unchanged for accelerator compatibility. Phase 3 changes modal-capable keyboard dispatch acknowledgement; this phase remains synchronous.

- [ ] **Step 4: Run GREEN and commit**

```sh
cd skills/fxdriver
node ../../scripts/sh.mjs ./mvnw -q spotless:apply
node ../../scripts/sh.mjs ./mvnw --batch-mode -Dit.test=FxDriverFormsIT,FxDriverProbeIT verify
git add src/main/java/fxdriver/FxDriverAgent.java \
  src/test/java/fxdriver/FxDriverFormsApp.java src/test/java/fxdriver/FxDriverFormsIT.java \
  src/test/java/fxdriver/FxDriverProbeIT.java
git commit -m "feat(fxdriver): restore keyboard actions"
```

### Task 6: Restore menu-item firing

**Files:**

- Modify: `skills/fxdriver/src/test/java/fxdriver/FxDriverDataIT.java`
- Modify: `skills/fxdriver/src/test/java/fxdriver/FxDriverProbeIT.java`
- Modify: `skills/fxdriver/src/main/java/fxdriver/FxDriverAgent.java`

- [ ] **Step 1: Add failing real-menu workflow tests**

Exercise all supported roots:

```java
assertMenu("{\"nodeId\":\"project-menu\",\"path\":[\"File\",\"Save Workspace\"]}", "Saved workspace");
assertMenu("{\"nodeId\":\"project-actions\",\"itemText\":\"Delete\"}", "Deleted project");
assertMenu("{\"nodeId\":\"project-more-actions\",\"itemText\":\"Export\"}", "Exported project");
assertMenu("{\"nodeId\":\"project-table\",\"itemText\":\"Archive\"}", "Archived project");
```

Add misses for an unknown path and disabled item; both return `fired:0,ok:false` with `NO_MENU_ITEM` or `DISABLED`. Define:

```java
private void assertMenu(final String params, final String expectedStatus) throws Exception {
    final var response = call("fireMenuItem", params);
    assertTrue(response.contains("\"fired\":1"), response);
    assertTrue(response.contains("\"ok\":true"), response);
    assertWait(expectedStatus);
}
```

- [ ] **Step 2: Run RED**

```sh
cd skills/fxdriver
node ../../scripts/sh.mjs ./mvnw --batch-mode -Dit.test=FxDriverDataIT verify
```

Expected: FAIL with `METHOD_NOT_FOUND`.

- [ ] **Step 3: Implement recursive menu resolution**

Add JavaFX imports for `ContextMenu`, `Menu`, `MenuBar`, `MenuButton`, `MenuItem`, and `SplitMenuButton`. Add `fireMenuItem` to `METHODS`/`dispatch`, add `menus` to production `capabilitiesResponse`, and update the exact method/feature arrays in `FxDriverProbeIT` in the same task (`fireMenuItem` and `menus`).

Resolve the selected node with existing selector logic. Menu roots are:

```java
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
    if (index >= path.size()) return null;
    for (final var item : items) {
        if (!path.get(index).equals(item.getText())) continue;
        if (index == path.size() - 1) return item;
        return item instanceof Menu menu ? menuPath(menu.getItems(), path, index + 1) : null;
    }
    return null;
}

private static MenuItem menuText(final List<MenuItem> items, final String text, final boolean exact) {
    for (final var item : items) {
        if (exact ? text.equals(item.getText()) : item.getText() != null && item.getText().contains(text)) {
            return item;
        }
        if (item instanceof Menu menu) {
            final var nested = menuText(menu.getItems(), text, exact);
            if (nested != null) return nested;
        }
    }
    return null;
}
```

Resolve `path` with `menuPath(items, path, 0)`. Otherwise call `menuText(items,itemText,true)`, then retry with `false`. If no target node/menu root/item exists, return `fired:0,ok:false` with `NO_MENU_ITEM`; if disabled, return `DISABLED`. Call `MenuItem.fire()` and return:

```json
{"query":"#project-menu","kind":"MenuBar","path":["File","Save Workspace"],"value":"Save Workspace","fired":1,"ok":true}
```

Do not show popups and do not add generic menu show/hide APIs.

- [ ] **Step 4: Run GREEN and commit**

```sh
cd skills/fxdriver
node ../../scripts/sh.mjs ./mvnw -q spotless:apply
node ../../scripts/sh.mjs ./mvnw --batch-mode -Dit.test=FxDriverDataIT,FxDriverProbeIT verify
git add src/main/java/fxdriver/FxDriverAgent.java \
  src/test/java/fxdriver/FxDriverDataIT.java src/test/java/fxdriver/FxDriverProbeIT.java
git commit -m "feat(fxdriver): restore menu actions"
```

### Task 7: Restore table and tree-table cell inspection

**Files:**

- Modify: `skills/fxdriver/src/test/java/fxdriver/FxDriverDataIT.java`
- Modify: `skills/fxdriver/src/test/java/fxdriver/FxDriverProbeIT.java`
- Modify: `skills/fxdriver/src/main/java/fxdriver/FxDriverAgent.java`

- [ ] **Step 1: Add failing table-cell tests**

Test TableView by row text/column ID and index, plus TreeTableView by index:

```java
final var byText = call("tableCell", "{\"nodeId\":\"project-table\",\"tableText\":\"PRJ-102\",\"column\":\"project-name\"}");
assertTrue(byText.contains("\"rowIndex\":1"), byText);
assertTrue(byText.contains("\"columnIndex\":1"), byText);
assertTrue(byText.contains("\"value\":\"Borealis\""), byText);
assertTrue(byText.contains("\"ok\":true"), byText);

final var treeCell = call("tableCell", "{\"nodeId\":\"project-tree-table\",\"rowIndex\":1,\"columnIndex\":0}");
assertTrue(treeCell.contains("PRJ-102"), treeCell);
```

Add `NO_ROW`, `NO_COLUMN`, and `NO_TABLE` app-level failure assertions.

- [ ] **Step 2: Run RED**

```sh
cd skills/fxdriver
node ../../scripts/sh.mjs ./mvnw --batch-mode -Dit.test=FxDriverDataIT verify
```

Expected: FAIL with `METHOD_NOT_FOUND`.

- [ ] **Step 3: Implement model-level cell lookup**

Add `tableCell` to `METHODS`/`dispatch`, add `table-cell` to production `capabilitiesResponse`, and update the exact method/feature arrays in `FxDriverProbeIT` in the same task (`tableCell` and `table-cell`). Resolve only `TableView` and `TreeTableView`; do not inspect realized cell nodes.

Define the result completely:

```java
private record TableCellResult(
        boolean ok,
        String code,
        int rowIndex,
        int columnIndex,
        String column,
        String rowText,
        String value) {
    static TableCellResult found(
            final int row, final int columnIndex, final String column,
            final String rowText, final String value) {
        return new TableCellResult(true, "", row, columnIndex, column, rowText, value);
    }

    static TableCellResult failure(final String code, final int row) {
        return new TableCellResult(false, code, row, -1, "", "", "");
    }

    String json() {
        return "\"rowIndex\":" + rowIndex + ",\"columnIndex\":" + columnIndex
                + ",\"column\":\"" + jsonEscape(column) + "\",\"rowText\":\""
                + jsonEscape(rowText) + "\",\"value\":\"" + jsonEscape(value)
                + "\",\"ok\":" + ok
                + (ok ? "" : ",\"error\":{\"code\":\"" + code
                        + "\",\"message\":\"Table cell could not be resolved\"}");
    }
}
```

For TableView:

```java
private static TableCellResult tableCell(
        final TableView<?> table,
        final String rowText,
        final int requestedRow,
        final String column,
        final int requestedColumn) {
    final var row = resolveRow(table.getItems(), rowText, requestedRow);
    final var columnIndex = resolveColumn(table.getColumns(), column, requestedColumn);
    if (row < 0) return TableCellResult.failure("NO_ROW", -1);
    if (columnIndex < 0) return TableCellResult.failure("NO_COLUMN", row);
    final var selected = table.getColumns().get(columnIndex);
    return TableCellResult.found(
            row,
            columnIndex,
            columnName(selected),
            String.valueOf(table.getItems().get(row)),
            String.valueOf(selected.getCellData(row)));
}
```

Implement TreeTableView lookup explicitly:

```java
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
    if (row < 0) return TableCellResult.failure("NO_ROW", -1);
    if (columnIndex < 0) return TableCellResult.failure("NO_COLUMN", row);
    final var selected = table.getColumns().get(columnIndex);
    return TableCellResult.found(
            row,
            columnIndex,
            columnName(selected),
            String.valueOf(table.getTreeItem(row).getValue()),
            String.valueOf(selected.getCellData(row)));
}
```

Implement the shared resolvers:

```java
private static int resolveRow(final List<?> rows, final String text, final int requested) {
    if (requested >= 0) return requested < rows.size() ? requested : -1;
    for (var i = 0; i < rows.size(); i++) {
        if (String.valueOf(rows.get(i)).equals(text)) return i;
    }
    for (var i = 0; i < rows.size(); i++) {
        if (String.valueOf(rows.get(i)).contains(text)) return i;
    }
    return -1;
}

private static int resolveColumn(
        final List<? extends TableColumnBase<?, ?>> columns,
        final String value,
        final int requested) {
    if (requested >= 0) return requested < columns.size() ? requested : -1;
    for (var i = 0; i < columns.size(); i++) {
        final var column = columns.get(i);
        if (value.equals(column.getId()) || value.equals(column.getText())) return i;
    }
    for (var i = 0; i < columns.size(); i++) {
        if (value.equalsIgnoreCase(columns.get(i).getText())) return i;
    }
    return -1;
}

private static String columnName(final TableColumnBase<?, ?> column) {
    return column.getId() == null || column.getId().isBlank() ? column.getText() : column.getId();
}
```

`tableCellResponse` runs lookup on the FX thread, returns `NO_TABLE` when the selector finds neither table type, and otherwise embeds `TableCellResult.json()` through `resultResponse`. Flat top-level columns are sufficient for this phase fixture. If grouped columns are later required, add leaf-column flattening only with a real failing application.

- [ ] **Step 4: Run GREEN and commit**

```sh
cd skills/fxdriver
node ../../scripts/sh.mjs ./mvnw -q spotless:apply
node ../../scripts/sh.mjs ./mvnw --batch-mode -Dit.test=FxDriverDataIT,FxDriverProbeIT verify
git add src/main/java/fxdriver/FxDriverAgent.java \
  src/test/java/fxdriver/FxDriverDataIT.java src/test/java/fxdriver/FxDriverProbeIT.java
git commit -m "feat(fxdriver): restore table cell inspection"
```

### Task 8: Add bounded wait near-matches and screenshot orientation metadata

**Files:**

- Modify: `skills/fxdriver/src/test/java/fxdriver/FxDriverDataIT.java`
- Modify: `skills/fxdriver/src/test/java/fxdriver/FxDriverProbeIT.java`
- Modify: `skills/fxdriver/src/test/java/fxdriver/FxDriverFailureExamplesIT.java`
- Modify: `skills/fxdriver/src/main/java/fxdriver/FxDriverAgent.java`

- [ ] **Step 1: Add failing near-match assertions**

Call:

```java
final var wait = call("wait", "{\"textExact\":\"Boreali\",\"timeoutMs\":100}");
assertTrue(wait.contains("\"ok\":false"), wait);
assertTrue(wait.contains("\"nearMatches\":"), wait);
assertTrue(wait.contains("Borealis"), wait);
assertTrue(wait.contains("\"selectorPath\":"), wait);
assertTrue(occurrences(wait, "\"selectorPath\"") <= 5, wait);
assertTrue(wait.length() < 12_000, wait);
```

Also test a missing ID near `project-table` and verify successful waits omit `nearMatches`.

- [ ] **Step 2: Add failing screenshot metadata assertions**

Capture the data window and a node. Assert both results contain:

```java
assertTrue(result.contains("\"activeWindow\":"), result);
assertTrue(result.contains("\"title\":\"Project Browser\""), result);
assertTrue(result.contains("\"visibleTextSample\":"), result);
assertTrue(result.contains("Borealis"), result);
assertTrue(result.length() < 12_000, result);
```

In `FxDriverProbeIT`, use the existing secondary stage. Read its `wid` from full snapshot by title with:

```java
private static String windowId(final String snapshot, final String title) {
    final var pattern = Pattern.compile(
            "\\\"wid\\\":\\\"(w\\d+)\\\"[^}]*\\\"title\\\":\\\""
                    + Pattern.quote(title) + "\\\"");
    final var matcher = pattern.matcher(snapshot);
    assertTrue(matcher.find(), snapshot);
    return matcher.group(1);
}
```

Request a window screenshot with that `window`, and assert `activeWindow.title` is `fxdriver probe secondary`, the sample contains `Secondary button`, and image dimensions match the secondary stage rather than the primary stage. This is the RED test for requested-window image/metadata alignment without introducing Phase 3 fixture work.

- [ ] **Step 3: Run RED**

```sh
cd skills/fxdriver
node ../../scripts/sh.mjs ./mvnw --batch-mode -Dit.test=FxDriverDataIT,FxDriverProbeIT,FxDriverFailureExamplesIT verify
```

Expected: FAIL because failed waits have no `nearMatches` and screenshots have no orientation metadata or requested-window alignment.

- [ ] **Step 4: Implement bounded near matches**

On failed waits only, collect visible nodes with nonblank ID/text/type, rank against the selector needle, and emit at most five. Normalize query prefixes (`#`, `=`, `text=`, `type=`); cap both needle and candidate strings at 80 characters before edit-distance scoring.

Implement ranking concretely:

```java
private record NearMatch(Node node, int score) {}

private static String nearMatches(final String query) throws Exception {
    return onFxThread(
            () -> {
                final var needle = truncate(query.replaceFirst("^(text=|type=|regexText=|[=#.])", ""), 80)
                        .toLowerCase(java.util.Locale.ROOT);
                final var candidates = new ArrayList<NearMatch>();
                for (final var window : Window.getWindows()) {
                    if (!window.isShowing() || window.getScene() == null) continue;
                    for (final var node : flatten(window.getScene().getRoot())) {
                        if (!node.isVisible()) continue;
                        final var id = truncate(node.getId(), 80).toLowerCase(java.util.Locale.ROOT);
                        final var text = truncate(textOf(node), 80).toLowerCase(java.util.Locale.ROOT);
                        final var type = node.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
                        if (id.isBlank() && text.isBlank()) continue;
                        candidates.add(new NearMatch(node, Math.min(
                                editDistance(needle, id.isBlank() ? type : id),
                                editDistance(needle, text.isBlank() ? type : text))));
                    }
                }
                return "[" + String.join(",", candidates.stream()
                        .sorted(java.util.Comparator.comparingInt(NearMatch::score))
                        .limit(5).map(match -> nearMatchJson(match.node())).toList()) + "]";
            });
}

private static int editDistance(final String left, final String right) {
    final var previous = new int[right.length() + 1];
    final var current = new int[right.length() + 1];
    for (var j = 0; j <= right.length(); j++) previous[j] = j;
    for (var i = 1; i <= left.length(); i++) {
        current[0] = i;
        for (var j = 1; j <= right.length(); j++) {
            final var cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
            current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
        }
        System.arraycopy(current, 0, previous, 0, current.length);
    }
    return previous[right.length()];
}
```

Emit:

```java
private static String nearMatchJson(final Node node) {
    final var window = windowOf(node);
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
```

The wait response appends `,"nearMatches":` plus `nearMatches(query)` only when `ok:false`. Keep the existing `TIMEOUT` error and polling semantics unchanged. Add `near-matches` to production `capabilitiesResponse` and the exact feature array in `FxDriverProbeIT` in this task. Update `FxDriverFailureExamplesIT` in the same commit: replace its old `assertFalse(...nearMatches...)` with assertions that the `Refresh toolbr` typo wait contains bounded `nearMatches`, `Refresh toolbar`, and at most five selector paths.

- [ ] **Step 5: Add active-window and visible-text screenshot metadata**

Resolve the screenshot window once:

- node target: `windowOf(node)`;
- window/rect target: requested `window` wid, then focused showing stage, then first showing stage.

Pass that window to `screenshotJson`. Append `activeWindow` using `orientationWindowJson(window)`—never raw `windowJson(window)`—and `visibleTextSample` using at most 24 distinct visible nonblank strings, each truncated to 160 characters. Replace `firstStageImage()` with `stageImage(Stage)` so image capture and metadata describe the same window. Preserve existing image/source/scale fields.

Resolve a requested stage exactly:

```java
private static Stage screenshotStage(final String request) {
    final var requested = extractString(request, "window");
    Stage fallback = null;
    for (final var window : Window.getWindows()) {
        if (!(window instanceof Stage stage) || !stage.isShowing() || stage.getScene() == null) continue;
        if (("w" + windowHandle(stage)).equals(requested)) return stage;
        if (fallback == null || stage.isFocused()) fallback = stage;
    }
    if (!requested.isBlank()) {
        throw new IllegalArgumentException("unknown screenshot window: " + requested);
    }
    return fallback;
}
```

Add `screenshot-metadata` to production `capabilitiesResponse` and update the exact feature array in `FxDriverProbeIT`.

- [ ] **Step 6: Run GREEN and commit**

```sh
cd skills/fxdriver
node ../../scripts/sh.mjs ./mvnw -q spotless:apply
node ../../scripts/sh.mjs ./mvnw --batch-mode -Dit.test=FxDriverDataIT,FxDriverProbeIT,FxDriverFailureExamplesIT verify
git add src/main/java/fxdriver/FxDriverAgent.java \
  src/test/java/fxdriver/FxDriverDataIT.java src/test/java/fxdriver/FxDriverProbeIT.java \
  src/test/java/fxdriver/FxDriverFailureExamplesIT.java
git commit -m "feat(fxdriver): add orientation diagnostics"
```

### Task 9: Lock capabilities, update protocol text, and verify the phase boundary

**Files:**

- Modify: `skills/fxdriver/src/test/java/fxdriver/FxDriverProbeIT.java`
- Modify: `skills/fxdriver/src/main/java/fxdriver/FxDriverAgent.java`
- Modify: `skills/fxdriver/src/main/markdown/fxdriver/references/protocol.md`
- Modify: `skills/fxdriver/src/main/markdown/fxdriver/references/workflow.md`

- [ ] **Step 1: Update exact capability arrays**

The exact method array now includes, in dispatch order:

```text
snapshotSummary, key, fireMenuItem, tableCell
```

The exact feature array adds:

```text
snapshot-summary, keyboard, menus, table-cell, near-matches, screenshot-metadata
```

Retain explicit absence assertions for `videoStart`, `videoStep`, and `videoStop`. Retain absence of generic `select` and selector-path selector capabilities.

- [ ] **Step 2: Update protocol/workflow documentation**

Make the existing `snapshot --summary`, `key`, `fireMenuItem`, `tableCell`, failed-wait near-match, and screenshot metadata sections match the tested JSON exactly. State:

- summary category caps and 16 KiB test bound;
- key is synthetic JavaFX input, not OS/global input;
- menu firing does not show native or JavaFX popups;
- table-cell reads model values and flat columns;
- near matches are diagnostics, not selectors;
- screenshot visible text is a bounded orientation sample;
- generic `select` is unsupported;
- video and modal dispatch remain outside this phase.

Do not edit `skills/fxdriver/src/main/markdown/fxdriver/references/failures.md`; it contains unrelated user work.

- [ ] **Step 3: Run the complete Java 21 verification**

```sh
mise run //skills/fxdriver:verify
```

Expected: unit tests, focused process/forms/data/failure suites, packaging, and Spotless PASS; benchmark is skipped.

- [ ] **Step 4: Run Java 26/JavaFX 26 verification**

```sh
FXDRIVER_JAVA_TOOL=temurin-26 \
FXDRIVER_MAVEN_ARGS="-Djavafx.version=26.0.1 -Dglass.platform=Headless -Djava.awt.headless=true -Dprism.order=sw" \
mise run //skills/fxdriver:verify
```

Expected: PASS with the same exact capabilities and WebView script coverage.

- [ ] **Step 5: Run the opt-in realistic benchmark**

```sh
cd skills/fxdriver
FXDRIVER_JAVA_TOOL=temurin-21.0.11+10 mise x -- \
  node ../../scripts/sh.mjs ./mvnw --batch-mode package \
  -Dfxdriver.benchmark=true -Dit.test=FxDriverBenchmarkIT failsafe:integration-test
```

Expected: PASS with configured speedup thresholds.

- [ ] **Step 6: Build and inspect the packaged JAR**

```sh
mise run //skills/fxdriver:build
java -jar skills/fxdriver/dist/fxdriver/fxdriver.jar
```

Expected: exit `2`; usage includes `snapshot <port> [--summary] [token]`. Confirm test-only fixture classes are absent:

```sh
jar tf skills/fxdriver/dist/fxdriver/fxdriver.jar | rg 'FxDriver(Forms|Data|Probe|TestHarness)'
```

Expected: no output.

- [ ] **Step 7: Commit documentation and capability lock**

```sh
git add skills/fxdriver/src/main/java/fxdriver/FxDriverAgent.java \
  skills/fxdriver/src/test/java/fxdriver/FxDriverProbeIT.java \
  skills/fxdriver/src/main/markdown/fxdriver/references/protocol.md \
  skills/fxdriver/src/main/markdown/fxdriver/references/workflow.md
git commit -m "docs(fxdriver): publish inspection APIs"
```

- [ ] **Step 8: Check phase scope and stop**

```sh
git diff --check
git status --short --branch
git log --oneline -10
```

Expected: no unstaged Phase 2 files; unrelated pre-existing `.gitignore`, desloppy references, `TODO.md`, and `skills/fxdriver/src/main/markdown/fxdriver/references/failures.md` remain untouched. Stop before Phase 3 launch/modal work.
