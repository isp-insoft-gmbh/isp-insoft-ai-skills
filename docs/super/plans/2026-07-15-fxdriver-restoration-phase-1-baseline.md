# fxdriver Baseline Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use /skill:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore useful later-VCS CLI and selector-diagnostic improvements that the GitHub monorepo never received, without changing fxdriver’s advertised method set.

**Architecture:** Keep the current monorepo files and transplant only the proven behavior from VCS commits `8fcd61d` and `56d650e`. Machine-readable CLI handling remains in `FxDriver`; resolved-target and quiet-state diagnostics remain in `FxDriverAgent`; integration tests drive the built JAR against the existing probe for this phase.

**Tech Stack:** Java 21, JavaFX 21/26, JDK Attach API, JSON-RPC over localhost HTTP, Maven 4, JUnit 6

**Roadmap:** `docs/super/roadmaps/2026-07-15-fxdriver-restoration-roadmap.md`

**Phase:** Phase 1: Recover the Strongest Existing Baseline

---

## File map

- Modify `skills/fxdriver/src/main/java/fxdriver/FxDriver.java`: parse `--json`/`--quiet`, print one endpoint JSON object, and keep launched child output off machine-readable stdout.
- Modify `skills/fxdriver/src/main/java/fxdriver/FxDriverAgent.java`: return resolved action targets, bounded ambiguity data, and optional quiet-after-click evidence.
- Modify `skills/fxdriver/src/test/java/fxdriver/FxDriverProbeApp.java`: add two intentional duplicate-label actions for ambiguity coverage.
- Modify `skills/fxdriver/src/test/java/fxdriver/FxDriverProbeIT.java`: prove machine output, target diagnostics, ambiguity, and quiet waiting through the packaged JAR.
- Modify `skills/fxdriver/src/test/java/fxdriver/FxDriverBenchmarkIT.java`: resolve the correct JavaFX Web module set on Java 21/26.
- Modify `skills/fxdriver/pom.xml`: provide `jdk-jsobject` to tests when the JDK does not ship that module.

Do not modify the ignored/generated `dist/` or `target/` trees. Do not stage existing unrelated edits, especially `skills/fxdriver/src/main/markdown/fxdriver/references/failures.md`.

### Task 1: Make attach and launch output machine-readable

**Files:**

- Modify: `skills/fxdriver/src/test/java/fxdriver/FxDriverProbeApp.java`
- Modify: `skills/fxdriver/src/test/java/fxdriver/FxDriverProbeIT.java`
- Modify: `skills/fxdriver/src/main/java/fxdriver/FxDriver.java`

- [ ] **Step 1: Add failing integration assertions for pure endpoint output**

In `attachToRunningProbeApp`, pass `--json` before the PID and assert the output file is one JSON line before parsing it:

```java
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
assertTrue(attach.waitFor(Duration.ofSeconds(20)));
assertEquals(0, attach.exitValue(), Files.readString(out.resolve("attach.err")));
assertPureEndpointOutput(out.resolve("attach.out"));
final var endpoint = waitForEndpoint(out.resolve("attach.out"));
```

In `startProbe`, add `--quiet` between `launch` and `--`. In `launchAndDriveProbeApp`, call the same assertion immediately after `waitForEndpoint`.

Add JSON-first endpoint parsing while retaining compatibility parsing for non-machine output:

```java
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
    final var lines = Files.readAllLines(output);
    assertEquals(1, lines.size(), Files.readString(output));
    final var line = lines.getFirst();
    assertTrue(Json.hasKey(line, "pid"), line);
    assertTrue(Json.hasKey(line, "port"), line);
    assertTrue(Json.hasKey(line, "token"), line);
    assertTrue(Json.hasKey(line, "endpoint"), line);
    assertTrue(Json.hasKey(line, "endpointFile"), line);
}
```

Add an `--output-probe` application argument. Before `launch(args)`, `FxDriverProbeApp.main` writes 256 numbered lines to stdout and stderr, including terminal `FXDRIVER_STDOUT_DONE` and `FXDRIVER_STDERR_DONE` sentinels. Add a separate integration test that launches with `--quiet --output-probe`, shuts down the driver, waits for the launcher process to exit, and asserts:

```java
assertPureEndpointOutput(out.resolve("noisy-launch.out"));
final var stderr = Files.readString(out.resolve("noisy-launch.err"));
assertTrue(stderr.contains("FXDRIVER_STDOUT_DONE"), stderr);
assertTrue(stderr.contains("FXDRIVER_STDERR_DONE"), stderr);
```

This proves both child pipes drain fully without contaminating machine stdout. Keep the existing `addProbeJvmArgs` and conditional Java-version behavior; do not copy the old VCS test’s removal of current headless/CI arguments.

- [ ] **Step 2: Run the integration test and verify RED**

Run from `skills/fxdriver`:

```sh
./mvnw -DskipTests package failsafe:integration-test -Dit.test=FxDriverProbeIT
```

Expected: FAIL because current `attach` treats `--json` as the PID or current `launch` treats `--quiet` as a port.

- [ ] **Step 3: Implement command-option parsing and endpoint JSON**

Add `InputStream` and these option records/helpers to `FxDriver`:

```java
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
```

Use `commandOptions(args, 1)` in `attach` and `launchOptions(args)` in `launch`. In machine mode, start the child without `inheritIO()` and drain both streams to stderr:

```java
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
```

Retain both drainer thread handles. After `process.waitFor()`, join both before returning the child exit code. On endpoint/bootstrap failure, destroy the process tree, wait for child termination, then join both drainers before rethrowing. This preserves final output rather than relying on daemon-thread shutdown.

Replace `printEndpoint` with a version that always prints endpoint JSON first and suppresses compatibility lines in machine mode:

```java
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
```

Update usage text to show `[--json|--quiet]` for attach and launch.

- [ ] **Step 4: Format and verify GREEN**

Run:

```sh
./mvnw -q spotless:apply
./mvnw -DskipTests package failsafe:integration-test -Dit.test=FxDriverProbeIT
```

Expected: `FxDriverProbeIT` PASS; `attach.out` and `launch.out` contain one JSON line in machine mode.

- [ ] **Step 5: Commit the machine-output restoration**

```sh
git add skills/fxdriver/src/main/java/fxdriver/FxDriver.java skills/fxdriver/src/test/java/fxdriver/FxDriverProbeApp.java skills/fxdriver/src/test/java/fxdriver/FxDriverProbeIT.java
git commit -m "fix(fxdriver): restore machine-readable launch output"
```

### Task 2: Restore resolved-target and ambiguity diagnostics

**Files:**

- Modify: `skills/fxdriver/src/test/java/fxdriver/FxDriverProbeApp.java`
- Modify: `skills/fxdriver/src/test/java/fxdriver/FxDriverProbeIT.java`
- Modify: `skills/fxdriver/src/main/java/fxdriver/FxDriverAgent.java`

- [ ] **Step 1: Add duplicate controls and failing action assertions**

Add two buttons next to the primary probe button:

```java
final var duplicateA = new Button("Duplicate action");
duplicateA.setId("probe-duplicate-a");
final var duplicateB = new Button("Duplicate action");
duplicateB.setId("probe-duplicate-b");
```

Include both in the root `VBox`.

Add one assertion helper:

```java
private static String assertActionTarget(
        final Endpoint endpoint, final String method, final String params) throws Exception {
    final var response = rpc(endpoint.port(), endpoint.token(), method, params);
    assertTrue(response.contains("\"ok\":true"), response);
    assertTrue(response.contains("\"target\":{\"eid\":\"n"), response);
    assertTrue(response.contains("\"matches\":"), response);
    return response;
}
```

Use it for every current `ActionResult` family: `fire`, `click`, `type`, `clear`, `setText`, `setValue`, `showPopup`, `hidePopup`, `increment`, `decrement`, `scroll`, `selectIndex`, `scrollToIndex`, `expand`, and `collapse`. Existing assertions already provide valid selectors/values; replace their count-only checks rather than adding duplicate actions.

Add these focused cases:

```java
final var duplicateClick =
        assertActionTarget(
                endpoint, "click", "{\"textExact\":\"Duplicate action\"}");
assertTrue(duplicateClick.contains("\"matches\":2"), duplicateClick);
assertTrue(duplicateClick.contains("\"ambiguity\""), duplicateClick);
assertTrue(duplicateClick.contains("\"selectorPath\""), duplicateClick);

final var miss = rpc(endpoint.port(), endpoint.token(), "click", "{\"nodeId\":\"missing\"}");
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

final var state =
        rpc(
                endpoint.port(),
                endpoint.token(),
                "setText",
                "{\"nodeId\":\"probe-field\",\"value\":\"state-target\","
                        + "\"returnState\":\"compact\"}");
assertTrue(state.contains("\"target\":"), state);
assertTrue(state.contains("\"state\":"), state);

final var run =
        rpc(
                endpoint.port(),
                endpoint.token(),
                "run",
                "{\"steps\":[{\"op\":\"fire\",\"nodeId\":\"probe-button\"}],"
                        + "\"returnState\":\"compact\"}");
assertTrue(run.contains("\"target\":"), run);
assertTrue(run.contains("\"state\":"), run);
```

- [ ] **Step 2: Run the focused integration test and verify RED**

Run:

```sh
./mvnw -DskipTests package failsafe:integration-test -Dit.test=FxDriverProbeIT
```

Expected: FAIL because current action responses contain only counts and no `target`, `matches`, or `ambiguity`.

- [ ] **Step 3: Introduce one action result type and target JSON**

Replace the boolean-returning `NodeAction` route with a node-returning route and one result record:

```java
private record ActionResult(int count, String targetJson, int matches, String ambiguityJson) {
    static ActionResult miss() {
        return new ActionResult(0, "null", 0, "");
    }
}

@FunctionalInterface
private interface NodeAction {
    Node apply(Node node);
}

private static ActionResult actionResult(final Node target, final List<Node> matches) {
    if (target == null) {
        return new ActionResult(0, "null", matches.size(), ambiguityJson(matches));
    }
    return new ActionResult(1, targetJson(target), matches.size(), ambiguityJson(matches));
}
```

Make `withFirstMatch`/`withFirstRawMatch` return `ActionResult`, resolve all matches once, and return the node acted on rather than `true`. Convert `fire`, `click`, text/value mutation, popup, increment/decrement, scroll, index selection, scroll-to-index, and expand/collapse helpers to return the acted-on node or `null`.

Add bounded target serialization:

```java
private static String ambiguityJson(final List<Node> matches) {
    if (matches.size() <= 1) {
        return "";
    }
    return ",\"ambiguity\":["
            + String.join(
                    ",", matches.stream().limit(8).map(FxDriverAgent::targetJson).toList())
            + "]";
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
```

Add these missing helpers exactly once:

```java
private static Window windowOf(final Node node) {
    return node.getScene() == null ? null : node.getScene().getWindow();
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
```

Reuse the current `okOrError` shape by extracting its existing inline `ok/error` concatenation into one helper; do not change JSON-RPC error semantics.

Define diagnostic selector paths as `window[index] > segment > segment`, where a segment is semantic/simple type plus optional `#id` or escaped `[text=…]` and same-class sibling index. Cap traversal at 32 ancestors and the final string at 2,048 characters. A SubScene root starts a diagnostic path at its own root because it has no parent link to the containing SubScene; IDs remain authoritative there. Do not add `path` or `selectorPath` to accepted selector capabilities.

Add assertions for one exact stable-ID path suffix, a secondary-window index, a SubScene node’s nonempty bounded path, quote/backslash escaping, exactly eight ambiguity entries from nine duplicate buttons, and absence of path selectors from `capabilities`.

Update `actionResponse`, `textActionResponse`, and `scrollResponse` to emit count, target, matches, and optional ambiguity while preserving `stateSuffix(request)`. Convert every action-producing helper and call site in this checklist: `fire`, `click`, `type`, `setText`, `setValue`, `popup`, `stepValue`, `scroll`, `selectIndex`, `scrollToIndex`, and `expand`. `selectListItem`, `listItems`, `wait`, `assert`, screenshots, and WebView scripting retain their existing result types.

- [ ] **Step 4: Format and verify action diagnostics GREEN**

Run:

```sh
./mvnw -q spotless:apply
./mvnw -DskipTests package failsafe:integration-test -Dit.test=FxDriverProbeIT
```

Expected: PASS; every listed action family reports a target, misses report `target:null`, delayed highlighting preserves diagnostics, `run`/`returnState` preserve state composition, paths remain bounded/diagnostic-only, and ambiguity emits exactly eight of nine matches.

- [ ] **Step 5: Commit action diagnostics**

```sh
git add skills/fxdriver/src/main/java/fxdriver/FxDriverAgent.java skills/fxdriver/src/test/java/fxdriver/FxDriverProbeApp.java skills/fxdriver/src/test/java/fxdriver/FxDriverProbeIT.java
git commit -m "fix(fxdriver): restore action target diagnostics"
```

### Task 3: Restore optional quiet-after-click evidence

**Files:**

- Modify: `skills/fxdriver/src/test/java/fxdriver/FxDriverProbeApp.java`
- Modify: `skills/fxdriver/src/test/java/fxdriver/FxDriverProbeIT.java`
- Modify: `skills/fxdriver/src/main/java/fxdriver/FxDriverAgent.java`

- [ ] **Step 1: Add the failing quiet-click assertion**

Add after a normal click:

```java
final var quietClick =
        rpc(
                endpoint.port(),
                endpoint.token(),
                "click",
                "{\"nodeId\":\"probe-button\",\"untilQuietMs\":100,"
                        + "\"timeoutMs\":2000}");
assertTrue(quietClick.contains("\"quiet\":{\"ok\":true"), quietClick);
assertTrue(quietClick.contains("\"target\":"), quietClick);
```

Add `probe-unstable` and `probe-unstable-state`. Firing the button starts a 20 ms JavaFX `Timeline` that changes the state label for two seconds. Assert timeout behavior with monotonic elapsed time:

```java
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
```

- [ ] **Step 2: Run the focused integration test and verify RED**

Run:

```sh
./mvnw -DskipTests package failsafe:integration-test -Dit.test=FxDriverProbeIT
```

Expected: FAIL because current `clickResponse` ignores `untilQuietMs`.

- [ ] **Step 3: Add bounded UI quiet detection**

Add this result type and a polling helper that hashes visible window/node state:

```java
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
```

`waitForQuiet(quietMs, timeoutMs)` uses one monotonic deadline, polls every `min(100, max(25, quietMs / 4))` milliseconds, resets the stable start when the signature changes, succeeds after `quietMs`, and fails at `timeoutMs`. Before each FX call and sleep it computes the remaining deadline budget. `uiSignature(remainingNanos)` uses that remaining value in `CompletableFuture.get`; an exhausted or timed-out FX call returns `QuietResult(false, …)` rather than a protocol-level `TimeoutException`. It hashes showing-window identity/focus/size plus each visible node’s handle, disabled/focused state, layout bounds, and text.

Update `clickResponse`:

```java
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
                        clicked.count() > 0 && quiet.ok(),
                        quiet.ok() ? "NO_MATCH" : "QUIET_TIMEOUT",
                        quiet.ok()
                                ? "No actionable node matched query"
                                : "UI did not stay quiet before timeout"),
        request);
```

Keep this opt-in. Normal clicks must not incur quiet polling.

- [ ] **Step 4: Format and verify GREEN**

Run:

```sh
./mvnw -q spotless:apply
./mvnw -DskipTests package failsafe:integration-test -Dit.test=FxDriverProbeIT
```

Expected: PASS; quiet evidence includes a stable signature and positive poll count.

- [ ] **Step 5: Commit quiet waiting**

```sh
git add skills/fxdriver/src/main/java/fxdriver/FxDriverAgent.java skills/fxdriver/src/test/java/fxdriver/FxDriverProbeApp.java skills/fxdriver/src/test/java/fxdriver/FxDriverProbeIT.java
git commit -m "fix(fxdriver): restore quiet click waiting"
```

### Task 4: Restore JavaFX Web module coverage on Java 21 and 26

**Files:**

- Modify: `skills/fxdriver/pom.xml`
- Modify: `skills/fxdriver/src/test/java/fxdriver/FxDriverProbeIT.java`
- Modify: `skills/fxdriver/src/test/java/fxdriver/FxDriverFailureExamplesIT.java`
- Modify: `skills/fxdriver/src/test/java/fxdriver/FxDriverBenchmarkIT.java`

- [ ] **Step 1: Verify the JavaFX 26 module-resolution failure**

Run from the repository root with the explicit Java 26 toolchain:

```sh
FXDRIVER_JAVA_TOOL=temurin-26 FXDRIVER_MAVEN_ARGS="-Djavafx.version=26.0.1 -Dglass.platform=Headless -Djava.awt.headless=true -Dprism.order=sw -Dit.test=FxDriverProbeIT" mise run //skills/fxdriver:verify
```

Expected before the dependency fix: FAIL because the Java 26/JavaFX 26 test runtime cannot resolve `jdk.jsobject` for WebView.

- [ ] **Step 2: Add the test-scoped module dependency**

Add the property:

```xml
<jdk.jsobject.version>25.0.2</jdk.jsobject.version>
```

Add after `javafx-web`:

```xml
<dependency>
  <groupId>org.openjfx</groupId>
  <artifactId>jdk-jsobject</artifactId>
  <version>${jdk.jsobject.version}</version>
  <scope>test</scope>
</dependency>
```

- [ ] **Step 3: Make probe and benchmark module lists runtime-aware**

In `FxDriverProbeIT`, `FxDriverFailureExamplesIT`, and `FxDriverBenchmarkIT`, add:

```java
private static String javafxModules() {
    return ModuleLayer.boot().findModule("jdk.jsobject").isPresent()
            ? "javafx.controls,javafx.web"
            : "javafx.controls,javafx.web,jdk.jsobject";
}
```

Replace hard-coded `javafx.controls,javafx.web,jdk.jsobject` module arguments with `javafxModules()`. Preserve the current headless system-property propagation and Java-version-specific JVM flags.

- [ ] **Step 4: Run both compatibility configurations**

Run:

```sh
FXDRIVER_JAVA_TOOL=temurin-21.0.11+10 mise run //skills/fxdriver:verify
FXDRIVER_JAVA_TOOL=temurin-26 FXDRIVER_MAVEN_ARGS="-Djavafx.version=26.0.1 -Dglass.platform=Headless -Djava.awt.headless=true -Dprism.order=sw" mise run //skills/fxdriver:verify
```

Expected: both builds PASS; all three probe launchers resolve the same module set and WebView scripting executes without missing-module errors.

- [ ] **Step 5: Commit the WebView dependency restoration**

```sh
git add skills/fxdriver/pom.xml skills/fxdriver/src/test/java/fxdriver/FxDriverProbeIT.java skills/fxdriver/src/test/java/fxdriver/FxDriverFailureExamplesIT.java skills/fxdriver/src/test/java/fxdriver/FxDriverBenchmarkIT.java
git commit -m "test(fxdriver): restore WebView module coverage"
```

### Task 5: Verify the Phase 1 boundary

**Files:**

- No production changes expected.
- Update this plan’s checkboxes only if the execution workflow tracks them in place.

- [ ] **Step 1: Run the complete fxdriver verification**

From the repository root:

```sh
mise run //skills/fxdriver:verify
```

Expected: unit tests, JavaFX integration tests, compiler warnings-as-errors, and Spotless all pass.

Run the opt-in benchmark explicitly:

```sh
cd skills/fxdriver
./mvnw -DskipTests package -Dfxdriver.benchmark=true -Dit.test=FxDriverBenchmarkIT failsafe:integration-test
```

Expected: benchmark PASS with its configured speedup thresholds.

- [ ] **Step 2: Build and inspect the packaged skill**

```sh
mise run //skills/fxdriver:build
java -jar skills/fxdriver/dist/fxdriver/fxdriver.jar
```

Before the build, change `assertAdvertisedCapabilities` to compare exact arrays:

```java
assertEquals(ADVERTISED_METHODS, Json.stringArray(capabilities, "methods"));
assertEquals(ADVERTISED_FEATURES, Json.stringArray(capabilities, "features"));
```

Add explicit absence assertions for `snapshotSummary`, `key`, `fireMenuItem`, `tableCell`, `videoStart`, `videoStep`, and `videoStop` because those belong to later phases.

Expected: build succeeds; the no-argument command exits 2 and usage includes `attach [--json|--quiet]` and `launch [--json|--quiet]`; capabilities exactly match the Phase 1 method/feature baseline with no later-phase APIs.

- [ ] **Step 3: Check repository scope and diff hygiene**

```sh
git diff --check
git status --short --branch
git log --oneline -5
```

Expected: no unstaged Phase 1 code remains; unrelated pre-existing dirty files remain untouched; Phase 1 consists of the focused commits above.

- [ ] **Step 4: Request focused review**

Review the Phase 1 commit range against the roadmap outcome. Verify machine output, process pipe draining, target diagnostics, bounded ambiguity, quiet-wait termination, state suffix preservation, Java 21/26 WebView resolution, and no accidental restoration of later-phase APIs.

- [ ] **Step 5: Stop at the phase boundary**

Do not begin fixture splitting or restore snapshot/key/menu/table/modal/video APIs. Those belong to later roadmap phases and require separate plans.
