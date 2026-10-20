# Troubleshooting fxdriver

## Artifact or Java identity

Repeat [runtime preflight](runtime.md) before retrying launch or RPC.
Use the active skill's absolute bundled JAR path, not a workspace or cwd-relative `target` substitute.
Missing bundled JAR means incomplete installation, not permission to build another driver.
Compare file SHA-256 with loaded `version.agentSha256`; matching snapshot version strings are insufficient.
A stale already-loaded agent or classes-directory shadow is refused by attach/launch identity verification.
Restart only a test-owned or explicitly authorized target; do not kill user sessions to replace an agent.

## Java starts a tool installer

Resolve real driver/app Java installation executables before cwd or app-data isolation.
A tool-manager shim can bootstrap tools after `LOCALAPPDATA`, HOME or XDG overrides.
Inspect exit status and captured stderr before calling this a JavaFX timeout.
Do not bootstrap a toolchain into the test profile or modify unrelated global configuration.

## Attach fails

Confirm the exact process identity with the configured JDK's actual `jcmd` binary or existing project process tooling.
Common causes include exited/wrong PID, incompatible JVM/JavaFX, restricted dynamic loading, missing instrumentation modules or a stale loaded agent.
If the target configuration requires `-XX:+EnableDynamicAgentLoading` or `java.instrument`, preserve project configuration and obtain restart permission rather than inventing a replacement runtime.
Endpoint readiness alone does not establish application readiness.

## RPC timeout or incomplete action

Use the saved endpoint JSON and inspect private startup/runtime logs.
`pending` plus `actionId` means a tracked invocation may be inside a modal loop.
Inspect current windows, close only the intended authorized dialog, then query `actionStatus` and verify semantic state.
`cancelling` means setup/cleanup is unfinished; `unknown` means already-started work could still be running.
Do not blindly repeat mutations or invent concurrent-HTTP retries as a workaround.
`stateError` after an action means observation failed, not that the action was undone.

## Ambiguous, hidden, disabled or blocked target

IDs can be absent or duplicated; text queries can also match skin nodes.
Combine role/name or type/text filters, scope the observed window, or use a fresh observed `eid`.
After view replacement, rediscover rather than retaining cell/old-node handles.
`selectorPath` explains context but is not a supported selector.
`BLOCKED` means a modal owner/application relationship prevents dispatch into that window.
Logical eligibility does not establish physical visibility through clipping or occlusion.

## Dispatch succeeded but application state did not

Inspect typed operations, accessibility candidates, converter display, editor text, bindings and selection.
Role-derived actions can be ignored by a custom control even when dispatch returns normally.
Use native control operations, separate editor `commit`, and assert the actual result.
Never fallback-retry after a dispatch exception or uncertain outcome.
A `setText`/`type` payload uses `value`; `text` is a selector.
Synthetic JavaFX key/mouse/scroll events are not global OS input.

## Password or custom secret input

Native password fields and marked actual value controls redact content, length/selection metadata and screenshot pixels.
Mark both masked and revealed editors in application source when authorized.
Use `sensitive:true` on secret-bearing requests and expected-value assertions; successful assertions can verify content without returning it.
Ordinary unmarked values, URLs and application logs are not automatically classified as secrets.
Do not log raw credential requests or endpoint tokens.

## Virtualized and grouped controls

Unrealized cells are absent from the scene graph; absence is not missing model data.
Use bounded `listItems`, `tableColumns` and `tableCell`, plus selection/count metadata.
Table indices address visible leaf columns, including nested groups, rather than top-level group headers.
Custom cell presentation may differ from model strings or converter-derived display text.
Inspect realized presentation only when needed; do not fabricate cells or assume a recycled cell handle is row identity.

## Layout and rendering

`waitForLayout` observes a post-layout pulse, not completed backend presentation.
`waitForQuiet` observes sanitized scene-state stability, not all application work or arbitrary canvas/WebView pixels.
Capture and read the actual image; image hashes alone do not prove layout quality.
Snapshots are JavaFX scene/node images, not OS-composited desktop captures.

### HeadlessWindow.blit and geometry

A driver-free Java/JavaFX 26.0.2 comparison reproduced three `HeadlessWindow.blit` errors with a 620×1220 stage at `(190,-73)` on the reported 1000×1000 headless screen.
The same fixture contained at `(20,20)` with 620×800 bounds produced zero blit errors; both processes exited 0.
This establishes geometry-dependent backend failure independently of fxdriver, not healthy rendering merely from process exit.
Check `withinSingleScreen`, `headlessGeometryRisk`, actual screen bounds, stderr and screenshots.
Constrain only owned fixture geometry or perform a driver-free comparison.
Do not silently resize user windows; choose an authorized headed backend or agree on a geometry change.

## Native dialogs and WebView

Headless Glass does not cover native OS file/color dialogs, AWT tray workflows or global input.
Use an authorized headed/native tool or a project-supported test alternative.
`webExecuteScript` operates through public WebEngine APIs with bounded results and normal modal/sensitivity tracking.
Check advertised support, verify DOM/application effects explicitly, and never infer success from script dispatch alone.
