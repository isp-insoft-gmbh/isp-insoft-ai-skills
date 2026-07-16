# fxdriver Capability Restoration Roadmap

> **For agentic workers:** Use /skill:writing-plans to create one detailed implementation plan per phase. Start with Phase 1 and proceed sequentially unless the user explicitly changes the order.

**Goal:** Restore useful lost fxdriver capabilities, fix launch and modal behavior, replace the kitchen-sink probe with realistic focused fixtures, and make the packaged skill match the tested JAR.

**Design Spec:** [`docs/super/specs/2026-07-15-fxdriver-restoration-design.md`](../specs/2026-07-15-fxdriver-restoration-design.md)

**Planning Strategy:** Historical recovery, fixture redesign, launch/modal concurrency, and video encoding are distinct risk areas. Five green phases keep each change reviewable and prevent a single oversized plan from mixing process infrastructure, UI fixtures, protocol restoration, concurrency, media encoding, and documentation.

---

## Phase 1: Recover the Strongest Existing Baseline

**Outcome:** The GitHub monorepo includes useful later VCS improvements that were never copied, with matching tests and no change to the advertised method set.

**Why now:** Better machine output, action diagnostics, quiet waiting, and the missing WebView dependency are already proven and provide clearer evidence for every later phase.

**Scope:**

- Restore pure JSON attach/launch output and child-output routing from VCS commit `8fcd61d`.
- Restore action target/match/ambiguity diagnostics and optional quiet-after-click waiting.
- Restore the missing `jdk-jsobject` test dependency from VCS commit `56d650e`.
- Add regression coverage before each production restoration.

**Out of scope:**

- New snapshot, keyboard, menu, table-cell, modal, launch-bootstrap, and video behavior.
- Test-application redesign.

**Key files/areas likely affected:**

- `skills/fxdriver/src/main/java/fxdriver/FxDriver.java`: machine-readable CLI output.
- `skills/fxdriver/src/main/java/fxdriver/FxDriverAgent.java`: diagnostics, quiet waiting, events.
- `skills/fxdriver/src/test/java/fxdriver/`: focused regressions.
- `skills/fxdriver/pom.xml`: WebView test dependency.

**Dependencies:**

- Approved design spec.
- Read-only reference to VCS commits `8fcd61d` and `56d650e`.

**Verification:**

- Unit and existing integration suites pass.
- JSON CLI output parses without compatibility-line scraping.
- Ambiguous and failed selectors return bounded useful diagnostics.
- JavaFX 21 and configured JavaFX 26 builds resolve WebView test dependencies.

**Phase boundary health:** The current API remains intact; later phases start from the most capable tested baseline rather than the older GitHub snapshot.

**Risks:**

- Old diagnostics may conflict with newer `run`/`returnState`; transplant only response fields and preserve current state suffixes.
- Machine mode can deadlock on child pipes; keep dedicated draining threads and integration coverage.

**Context notes:** Use direct edits, not Git-history surgery or whole-file replacement. Preserve unrelated dirty work already present in the repository.

## Phase 2: Replace the Kitchen-Sink Fixture and Restore Inspection APIs

**Outcome:** Focused forms and data applications replace most kitchen-sink coverage, and snapshot summary, keyboard, menu, table-cell, and screenshot metadata APIs work through realistic workflows.

**Why now:** These APIs need realistic controls and duplicate labels to test selector quality. Building fixtures and restoring behavior together avoids temporary test-only abstractions with no consumer.

**Scope:**

- Add shared process/endpoint/RPC/cleanup test infrastructure.
- Add realistic test-only forms and data applications with deterministic state and semantic IDs.
- Migrate current form, value-control, list, tree, table, menu, async-state, and WebView-independent assertions out of `FxDriverProbeApp`.
- Restore the CLI `snapshot <port> [--summary] [token]` path and bounded summary result.
- Restore `key`, `fireMenuItem`, `tableCell`, near-match diagnostics, and active-window/visible-text screenshot metadata.
- Keep current explicit value/selection methods; do not restore generic `select`.
- Update capabilities and protocol documentation only for methods now implemented and tested.

**Out of scope:**

- JavaFX modal dispatch changes.
- Delayed-JavaFX launch bootstrap.
- GIF/APNG recording.
- Native UI automation.

**Key files/areas likely affected:**

- `skills/fxdriver/src/test/java/fxdriver/`: shared harness, forms/data apps, focused IT classes.
- `skills/fxdriver/src/test/resources/`: deterministic fixture styling.
- `skills/fxdriver/src/main/java/fxdriver/FxDriver.java`: snapshot CLI.
- `skills/fxdriver/src/main/java/fxdriver/FxDriverAgent.java`: summary and restored inspection/action APIs.
- `skills/fxdriver/src/main/markdown/fxdriver/`: current protocol/workflow text.

**Dependencies:**

- Phase 1 diagnostics and machine-readable launch output.

**Verification:**

- Forms and data integration suites independently pass.
- Summary output stays bounded and contains expected orientation categories.
- Keyboard, menu, and table-cell tests exercise real workflow state changes.
- Duplicate labels produce ambiguity evidence while semantic IDs remain stable.
- Packaged JAR exposes exactly the newly documented methods.

**Phase boundary health:** The driver gains useful inspection and interaction capabilities while all existing non-window/non-video behavior remains covered by clearer fixtures.

**Risks:**

- Fixture realism can create nondeterminism; use fixed data, no network, explicit animation/async triggers, and semantic IDs.
- Shared harness can become a framework; limit it to process, endpoint, RPC, artifacts, and cleanup.

**Context notes:** Prefer deletion of migrated kitchen-sink sections over parallel duplicate assertions. The fixtures are test-only and never packaged.

## Phase 3: Make Launch and JavaFX Modals Truthful

**Outcome:** fxdriver launches delayed-JavaFX applications without eager linkage, and JavaFX modal actions return a dispatch acknowledgement instead of false timeouts.

**Why now:** The windows fixture and restored event diagnostics are prerequisites for proving asynchronous dispatch and startup failures precisely.

**Scope:**

- Add the realistic test-only windows application with stages, popups, tooltips, context menus, alerts, and `showAndWait` dialogs.
- Add a JDK-only agent bootstrap that waits for system-loader-visible JavaFX classes through `Instrumentation`.
- Add endpoint-file startup error payloads and child-exit detection.
- Distinguish missing JavaFX, unsupported classloader, bootstrap failure, child exit, and startup deadline errors.
- Change `fire`, `click`, and keyboard actions to resolve a target, allocate a `dispatchId`, enqueue, acknowledge, then invoke.
- Record correlated asynchronous handler failures in events.
- Add event severity and preserve optional request tag/scenario values for synchronous and asynchronous events.
- Keep direct value mutations synchronous.

**Out of scope:**

- Native chooser, print, credential, or OS window automation.
- Video recording.

**Key files/areas likely affected:**

- `skills/fxdriver/src/main/java/fxdriver/`: bootstrap, endpoint startup protocol, asynchronous action dispatch.
- `skills/fxdriver/src/test/java/fxdriver/`: delayed-JavaFX launcher and windows/modal IT.
- `skills/fxdriver/pom.xml`: manifest bootstrap entry points.
- `skills/fxdriver/src/main/markdown/fxdriver/references/`: dispatch semantics and startup troubleshooting.

**Dependencies:**

- Phase 1 event diagnostics.
- Phase 2 shared harness and keyboard support.

**Verification:**

- A delayed-JavaFX fixture reproduces the old eager-link failure before the fix and launches after it.
- Attach still starts immediately in an already-running JavaFX JVM.
- `showAndWait` opens while the RPC returns promptly; snapshot sees the JavaFX dialog.
- A throwing modal handler produces a correlated asynchronous event.
- Tests never open native platform UI.

**Phase boundary health:** Launch and JavaFX modal workflows become truthful without expanding fxdriver into native desktop automation.

**Risks:**

- Agent bootstrap/classloader behavior differs by launcher; fail explicitly for unsupported loaders and test system-loader visibility.
- Dispatch acknowledgement changes action meaning; update every affected test and document that waits/state determine workflow completion.

**Context notes:** Do not hide unsupported classloader layouts behind retries. The startup error channel is part of the protocol and must stay machine-readable.

## Phase 4: Restore Deterministic GIF/APNG Recording

**Outcome:** `videoStart`, `videoStep`, and `videoStop` produce independently verified GIF/APNG evidence from a realistic visual application.

**Why now:** Video depends on stable machine launch, truthful actions, screenshots, and focused visual fixtures but is otherwise isolated from control restoration.

**Scope:**

- Add the realistic test-only visual application with deterministic animation, Canvas, SubScene, WebView, resize-sensitive cards, and named phases.
- Restore the historical recording pipeline around existing `Gif`, `Apng`, and `AnimationSink` classes.
- Support documented canvas, scale, title, quality, FPS, duration, window-selection, counters, and finalization behavior.
- Add encoder and integration tests for changed/unchanged frames, scaling, titles, autostop, shutdown, errors, and resource cleanup.
- Advertise and document video methods only after their integration tests pass.

**Out of scope:**

- Desktop-wide or cross-application recording.
- Native-dialog capture.
- External codec dependencies.

**Key files/areas likely affected:**

- `skills/fxdriver/src/main/java/fxdriver/FxDriverAgent.java`: recorder lifecycle and RPC methods.
- `skills/fxdriver/src/main/java/fxdriver/{AnimationSink,Gif,Apng}.java`: verified encoder integration and minimal fixes.
- `skills/fxdriver/src/test/java/fxdriver/`: visual application, encoder tests, video IT.
- `skills/fxdriver/src/test/resources/`: visual fixture styling/content.

**Dependencies:**

- Phase 2 screenshot metadata and shared harness.
- Phase 3 reliable launch and dispatch semantics.

**Verification:**

- GIF and APNG files decode and have expected dimensions, frames, delays, and changed regions.
- Fixed, screen, and first-frame canvases remain stable through resize/window changes.
- Step titles and counters match actions.
- Encoder work does not block the FX thread; dropped-frame accounting is truthful.
- Duration limit, shutdown, and JVM exit finalize or report formats according to contract.

**Phase boundary health:** Video becomes a tested optional capability; external recorders remain expected for evidence outside JavaFX.

**Risks:**

- Timing tests can flake; assert bounded invariants and deterministic phase changes rather than exact wall-clock frame counts.
- APNG requires normal finalization; tests distinguish normal close from truncated-process guarantees.

**Context notes:** Recover the proven historical implementation by transplanting coherent recorder sections, then adapt them to current JSON/events/state helpers rather than recreating the encoder architecture.

## Phase 5: Finish Fixture Migration and Lock Skill/JAR Parity

**Outcome:** The kitchen-sink app is gone, current skill docs explain semantic IDs and every external-tool boundary, and verification prevents future documentation/JAR drift.

**Why now:** Final cleanup can accurately describe only capabilities proven by the preceding phases and remove migration scaffolding without guessing future behavior.

**Scope:**

- Remove remaining `FxDriverProbeApp` and monolithic integration-test code after coverage mapping proves replacement.
- Keep only focused forms, data, windows, and visual applications and their integration suites.
- Shorten `SKILL.md` to the proven observe/act/verify loop and semantic-ID rule.
- Add a dedicated tool-boundary reference for native dialogs, OS chrome/input, external browsers, AWT/Swing/native embeds, cross-app state, and desktop-wide media.
- Update workflow, failure, visual, and troubleshooting references to current behavior.
- Add packaged-JAR tests that compare required documentation method names, capabilities, and exercised integration tests.
- Validate build/install artifacts for both `fxdriver` and `fxdriver-instructions`.

**Out of scope:**

- Historical VCS reports/plans.
- Runnable demos.
- Native automation inside fxdriver.
- Obsolete aliases or broad compatibility layers.

**Key files/areas likely affected:**

- `skills/fxdriver/src/test/java/fxdriver/`: final fixture/test cleanup and parity checks.
- `skills/fxdriver/src/main/markdown/fxdriver/SKILL.md`: concise operational contract.
- `skills/fxdriver/src/main/markdown/fxdriver/references/`: exact protocol and tool-switch guidance.
- `skills/fxdriver/src/main/markdown/fxdriver-instructions/`: consistent preflight instructions.
- `skills/fxdriver/pom.xml` and `skills/fxdriver/mise.toml`: artifact verification if needed.

**Dependencies:**

- Phases 1–4 complete and green.

**Verification:**

- No kitchen-sink fixture remains.
- Every advertised method has focused integration evidence.
- Required documented methods equal packaged JAR capabilities.
- Markdown references contain no aspirational present-tense APIs.
- Full Maven verify, repository build/lint, and Java 21/26 CI-equivalent checks pass.
- Built skill directories contain the expected JAR and current references only.

**Phase boundary health:** The repository ends coherent, minimal, fully tested, and resistant to the drift that caused this restoration.

**Risks:**

- Naive documentation parsing can become brittle; constrain parity checks to one explicit required-method manifest or generated method list.
- Deleting the old fixture can lose obscure coverage; map assertions before deletion and require full-suite green.

**Context notes:** Preserve the existing user edits in `references/failures.md` and other unrelated dirty files. Do not stage or rewrite them accidentally.
