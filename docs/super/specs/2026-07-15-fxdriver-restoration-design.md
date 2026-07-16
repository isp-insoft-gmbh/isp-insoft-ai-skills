# fxdriver capability restoration

## Problem

The packaged skill describes APIs that the packaged JAR does not expose. The drift is historical, not merely unfinished documentation:

- VCS commit `85288348a9c02685218e07b907b1375ea8127e33` contained working snapshot-summary, video, keyboard, menu, table, selector-diagnostic, and visual-artifact code.
- VCS commit `7f35d80862d24799cbb5169bf7d8d32ceebb8a1d` removed much of that code during a real-world simplification.
- VCS commit `38b165dd33236ad9f8bb4d110d9c741b298e52fc` restored a different subset of advanced controls, leaving the older APIs absent while their documentation survived.
- The GitHub monorepo copied the partial tree before later VCS commits restored machine-readable CLI output, richer selector diagnostics, and the WebView test dependency.

Real KKG testing exposed the consequences: `snapshot --summary` printed usage, video methods returned `METHOD_NOT_FOUND`, launch failed before JavaFX became visible to the agent, and modal actions timed out although the dialogs opened.

## Source policy

Keep the current GitHub monorepo implementation as the base. Restore useful behavior by direct edits and normal commits. Do not graft Git history, replace current files wholesale, preserve obsolete aliases, or copy historical plans and reports.

Use the old VCS implementations as references for behavior and tests. Preserve newer current behavior such as `run`, `query`, `returnState`, `setValue`, `selectIndex`, tree/table actions, WebView scripting, failure examples, and current packaging.

## Restored capabilities

### Snapshot

Implement the documented CLI forms:

```text
fxdriver snapshot <port> [--summary] [token]
```

Full mode delegates to the existing `snapshot` RPC. Summary mode returns a bounded orientation view containing windows, buttons, text inputs, tables/tree tables, selected tabs, focus, and visible text. It must remain small enough for an agent to inspect before requesting a full snapshot.

The CLI exits non-zero on protocol errors and supports `FXDRIVER_TOKEN` or the final token argument consistently with `rpc` and `screenshot`.

### Video

Restore `videoStart`, `videoStep`, and `videoStop` using the existing `Gif`, `Apng`, and `AnimationSink` implementations.

Required behavior:

- one active recording per attached JVM;
- GIF by default and APNG for high quality;
- fixed, screen, and first-frame canvases;
- stable background composition when windows change size;
- optional band/overlay step titles;
- bounded FPS, scale, duration, and dimensions;
- delta frames and accumulated unchanged-frame delay;
- background encoding without blocking the FX thread;
- explicit counters for captured, encoded, dropped, scaled, and titled steps;
- automatic finalization on duration limit, shutdown, and normal JVM exit;
- clear `NO_VIDEO`, `NO_FRAMES`, `VIDEO_FAILED`, and `VIDEO_STALLED` results.

No external encoder is required. External recording remains valid when evidence must include native dialogs or other applications outside the JavaFX scene graph.

### Interaction and diagnostics

Restore useful missing primitives:

- `key` for focused named keys, chords, and literal characters;
- `fireMenuItem` for menu bars, menu buttons, split-menu buttons, and context menus;
- `tableCell` for table/tree-table value inspection;
- compact screenshot metadata including active window and visible text sample;
- bounded near matches for failed waits;
- action target, match count, and ambiguity diagnostics;
- optional quiet-after-click waiting;
- event severity and request tags/scenarios;
- pure JSON attach/launch output through `--json`/`--quiet`;
- the missing `jdk-jsobject` test dependency required by WebView on affected JDK/JavaFX combinations.

Do not restore the old generic `select` method. Current `setValue`, `selectIndex`, `selectListItem`, `scrollToIndex`, `expand`, and `collapse` are clearer replacements.

Do not make selector paths the normal solution for writable applications. Semantic JavaFX IDs are the durable contract.

## Launch

The premain entry point must not eagerly resolve JavaFX classes. Some applications make JavaFX visible only during their own launcher/bootstrap sequence; loading `FxDriverAgent` too early causes `NoClassDefFoundError` before the application starts.

Add a JavaFX-free agent bootstrap as the manifest `Premain-Class` and `Agent-Class`. It uses `Instrumentation.getAllLoadedClasses()` to wait for `javafx.scene.Node` and `javafx.application.Platform` before loading `FxDriverAgent`. The detected JavaFX classes must be visible from the system loader that defines the agent; an incompatible custom-loader arrangement fails explicitly instead of attempting partial startup. Attach to an already-running JavaFX JVM should normally start immediately.

The endpoint file is also the startup-error channel. Before the RPC server exists, bootstrap failures write JSON containing `error.code` and `error.message`. Attach uses a short JavaFX-detection deadline; launch passes a longer deadline while the child initializes. `waitForEndpoint` checks both successful endpoint and error payloads while also checking whether a launched child exited. It reports `JAVAFX_NOT_FOUND`, `JAVAFX_CLASSLOADER_UNSUPPORTED`, bootstrap failure, child exit, or deadline expiry without masking child stderr.

The bootstrap must contain only JDK types so its own verification cannot trigger JavaFX resolution.

## Modal actions

`ButtonBase.fire()` and synthetic click handlers run synchronously on the FX thread. A handler calling `showAndWait`, `FileChooser.showOpenDialog`, `DirectoryChooser.showDialog`, or a print dialog intentionally does not return until the modal UI closes. Waiting for the handler makes the RPC report a timeout even though the action succeeded.

`fire`, `click`, and keyboard actions therefore resolve and validate their target, allocate a `dispatchId`, enqueue the action, and acknowledge dispatch before invoking the handler. The agent then observes the result with `wait`, `snapshot`, or an OS driver. Direct value mutations remain synchronous because they do not enter nested modal loops.

The queued action catches immediate handler exceptions and appends an asynchronous error event carrying the same `dispatchId`, method, selector, optional request tag/scenario, exception type, and message. A dispatch acknowledgement means “the action was accepted for execution,” not “the application workflow completed.” Agents inspect waits/state first and `eventsSince` when the expected transition fails. Exceptions from unrelated application tasks remain application-log concerns. Action results and skill documentation must say this explicitly.

## Selector policy

When the application source is writable, the agent adds minimal semantic JavaFX IDs to controls required by the requested flow. IDs describe user intent, follow project naming conventions, and remain in the application as testability improvements. The agent does not generate IDs for every node.

Text, role, `eid`, bounds, and ambiguity diagnostics remain discovery and one-off fallbacks. They are not substitutes for IDs in repeated writable-app workflows.

## Scope boundary: when to switch tools

fxdriver owns public JavaFX state: scenes, nodes, JavaFX windows, popups, context menus, tooltips, alerts, dialogs, WebView script execution, and control actions.

The skill must explicitly switch to an OS/browser/media tool when the requested evidence or interaction leaves that boundary:

- `FileChooser` and `DirectoryChooser` platform-native windows;
- native print/page-setup dialogs;
- operating-system permission, credential, UAC, keychain, portal, and notification dialogs;
- window decorations and OS window management: move, resize, minimize, maximize, taskbar/dock, and virtual desktops;
- system tray/AWT/Swing/native embedded components outside the JavaFX scene graph;
- drag-and-drop between JavaFX and the desktop or another application;
- global shortcuts, real hardware input, IME, touch, pen, accessibility tooling, and compositor-specific input restrictions;
- external browsers opened through `HostServices.showDocument` and browser-owned authentication;
- clipboard contents or cross-application state when no JavaFX control exposes the result;
- screenshots or videos that must include native dialogs, desktop chrome, or multiple applications.

JavaFX `Alert`, `Dialog`, secondary `Stage`, `Popup`, `ContextMenu`, and `Tooltip` remain in scope. Their modality is handled by dispatch acknowledgement, not by switching tools.

The skill should present switching tools as expected composition, not fxdriver failure. It should preserve the observe/act/verify loop across the handoff.

## Test-fixture redesign

Delete the single kitchen-sink `FxDriverProbeApp`. Replace it with four test-only JavaFX applications, each with a coherent realistic layout and focused responsibilities.

### Forms application

A settings/editor screen with toolbar, grouped form sections, validation/status feedback, text/password/area inputs, radio/check/toggle controls, choice/combo/date/color/spinner/slider controls, save/reset actions, and a JavaFX confirmation dialog.

It covers value mutation, keyboard input, focus, masking, disabled state, validation updates, and modal dispatch. Every workflow control has a semantic ID.

### Data application

A project browser with menu bar, menu button, context menu, navigation tree, filter field, tab pane, table/tree-table, list, pagination, async loading indicator, duplicate labels in different regions, and a status bar.

It covers menu traversal, list/tree/table selection, table-cell inspection, scrolling, expansion, quiet waiting, near matches, selector ambiguity diagnostics, and asynchronous state changes.

### Windows application

A workspace with primary and secondary stages, popup, tooltip, context menu, JavaFX alerts/dialogs using `showAndWait`, and explicit state labels recording window transitions.

Automated fxdriver tests cover JavaFX-owned windows and prove modal RPC calls return before closure and asynchronous handler failures appear in correlated events. Tests never open native choosers, print dialogs, or other platform UI; those boundaries are documentation-only and cannot hang CI.

### Visual application

A dashboard with deterministic animated charts/shapes, Canvas, SubScene, WebView, resize-sensitive cards, theme/color variation, and named workflow phases.

It covers screenshots, node/rect/window targeting, image summaries/diffs, WebView scripting, GIF/APNG recording, fixed/screen/first-frame canvases, scaling, unchanged frames, and step-title overlays.

### Fixture quality

The applications use realistic `BorderPane`, `ToolBar`, `SplitPane`, tabs, cards, forms, tables, status bars, deterministic sample data, and Modena-compatible CSS. They are visually credible enough to expose clipping, hierarchy, resize, color, and screenshot defects, but remain deterministic and network-free.

They exist only under test sources/resources and are never packaged in the skill.

## Test structure

Create shared test infrastructure only for process launch, endpoint parsing, authenticated RPC, cleanup, and artifact assertions. Keep application-specific assertions in separate integration-test classes.

Test layers:

1. unit tests for JSON, CLI parsing, frame diff/composition, GIF/APNG encoders, and bootstrap state;
2. focused integration tests for forms, data, windows/modals, and visual/video applications;
3. attach and launch smoke tests against separate processes;
4. packaging tests that execute the built JAR’s CLI shortcuts and compare advertised capabilities with exercised methods;
5. Java 21/JavaFX 21 and Java 26/JavaFX 26 CI verification on the existing OS matrix.

Every restored production behavior starts with a failing regression test. Video output is independently decoded with ImageIO/APNG structure checks and verified for dimensions, frame count, duration, and changed pixels. Modal tests measure prompt RPC return and then assert the JavaFX dialog is visible. Launch tests use a delayed JavaFX bootstrap fixture that reproduces the previous eager-link failure.

## Documentation

Shorten `SKILL.md` to the proven operational loop and invariants. Keep exact current commands and method details in protocol references. Add one explicit “switch tools” reference covering all boundary categories above, with examples of continuing the workflow through Win32, browser, or external video tools.

Generated skill documentation and packaged JAR capabilities must describe the same methods. Verification fails when documentation names a required method absent from `capabilities`, or when capabilities advertise a method without an integration test.

## Failure behavior

- Missing JavaFX during attach/launch reports a JavaFX-specific startup error, not a generic endpoint timeout.
- Dispatched modal actions return successfully; later application failures are observed through state/events/logs.
- Video encoder failures finalize state, close resources, and remain queryable through `videoStop`.
- Native UI is never misreported as absent JavaFX UI; the skill directs the agent to the proper external tool.
- Process, endpoint, recording, temporary artifact, and listener cleanup is idempotent.

## Non-goals

- WebDriver compatibility.
- Automating arbitrary native applications inside fxdriver.
- Bundling JavaFX runtime or platform-native libraries.
- Shipping the test applications as demos.
- Restoring obsolete historical plans, reports, aliases, or Git history.
- Adding IDs mechanically to every application node.
