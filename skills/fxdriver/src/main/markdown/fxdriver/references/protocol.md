# fxdriver protocol

Complete [runtime preflight](runtime.md) first.
`<driver-java>`, `<app-java>`, `<driver-jar>`, `<endpoint-json>` and `<temp-dir>` are verified absolute paths.
Examples are argv templates; apply the caller's shell quoting.
Require JDK 26+ for driver/app and JavaFX 26+ for the app.

## Transport and identity

Send localhost HTTP `POST /rpc` with `content-type: application/json` and `fxdriver-token` authentication.
Requests use the JSON-RPC 2.0 envelope with an explicit string, number or null `id` and object-valued named `params`.
Omitted/null `params` means an empty parameter object.
Positional parameters and notifications are unsupported.
Malformed JSON, duplicate object keys, invalid envelopes and excessive nesting are rejected before dispatch.
Bodies are limited to 1 MiB; raw batches and `run` plans to 1000 entries; JSON nesting to 128; nested `run` depth to 16.
Use advertised `capabilities.limits` rather than assuming larger requests are accepted.

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "query",
  "params": { "role": "BUTTON", "accessible": "Save" }
}
```

```text
<driver-java> -jar <driver-jar> launch --quiet -- <app-java> [existing-JVM-options] <main-or-jar> [app-args]
<driver-java> -jar <driver-jar> attach --json <pid>
<driver-java> -jar <driver-jar> rpc <endpoint-json> capabilities '{}'
<driver-java> -jar <driver-jar> rpc <endpoint-json> snapshotSummary '{}'
```

`launch` supervises its child and blocks until app exit; use a persistent process facility.
Killing the launcher kills its app.
Keep endpoint JSON, credentials and evidence in private OS temp.
The machine-readable startup line contains `pid`, `port`, `token`, `endpoint`, `endpointFile`, `startupMs`, `agentJar` and `agentSha256`.
`rpc` reads authentication directly from this saved JSON file; port-only calls require a token argument or `FXDRIVER_TOKEN`.
Human-readable startup output does not repeat token/export commands or suggest a cwd-relative JAR.

`version` and `capabilities` report the loaded agent's captured JAR path/SHA-256 and actual JVM/JavaFX/backend metadata.
The CLI verifies that running-agent SHA-256 matches its selected JAR before reporting attach/launch readiness.
A classes-directory agent, unidentifiable agent or older loaded JAR is not silently substituted.
An already-loaded agent cannot be upgraded merely by attaching another snapshot JAR; restart only an owned or explicitly authorized target.
Endpoint readiness is separate from GUI/application readiness.

## Outcomes, modal actions and batching

`rpc` and CLI snapshot/screenshot commands exit nonzero for top-level protocol errors or `result.ok:false`.
Text containing `"error"` is ordinary data, not failure classification.
Action counts describe dispatch, not business success or completed rendering.
Verify the resulting property, selection, dialog or application state independently.
Never retry a mutation merely because its caller timed out or a post-action observation failed.

An action opening a nested JavaFX modal loop can return `completion:"pending"` plus `actionId` instead of blocking dialog automation.
Use `actionStatus` to inspect that invocation after closing its dialog.
States include `queued`, `preparing`, `running`, `pending`, `cancelling`, `unknown`, `completed`, `failed` and `cancelled`.
`completed` means callback and driver cleanup returned, not background application work finished.
`cancelled` means the queued action body will not execute and required setup cleanup has finished.
`cancelling` means the body has not started but setup/cleanup remains outstanding.
Already-started work cannot safely be interrupted; a timeout can report `unknown`.
Active invocations remain tracked; only bounded terminal history expires.
An `actionStatus` lookup succeeding does not turn a nonterminal invocation into success.

```text
<driver-java> -jar <driver-jar> rpc <endpoint-json> run '{"steps":[{"op":"fire","nodeId":"edit"},{"op":"wait","nodeId":"dialog-name"},{"op":"setText","nodeId":"dialog-name","value":"Ada"},{"op":"fire","nodeId":"dialog-save"},{"op":"wait","nodeId":"dialog-name","present":false}],"returnState":"compact"}'
<driver-java> -jar <driver-jar> rpc <endpoint-json> actionStatus '{"actionId":42}'
```

`run` steps use `op` or `method` plus normal method parameters.
Replies include `completion`, requested `count`, actual `executed`, `failedStep`, `pendingActions` and per-step `steps`.
Default behavior stops on failure; `continueOnError:true` permits known failures, not unsafe dispatch past unresolved timeout/cancellation/unwind.
The driver reconciles modal unwind before later steps and before publishing the batch result.
`RECONCILIATION_TIMEOUT` means later steps were not dispatched and completion is unknown.
A raw JSON-RPC request array is also supported; `batch` is not an RPC method.
Prefer `run` for dependent steps and fail-fast behavior.

`returnState:true` or `returnState:"compact"` adds bounded post-request state.
`afterLayout:true` with `returnState` observes a layout pulse before collecting that state.
If a requested follow-up observation fails, the reply preserves action fields, reports `invocationReturned:true`, `stateError` and `ok:false`.
Do not repeat the action to recover missing observation evidence.

## Discovery and selectors

Start with `snapshotSummary`; use `query` for exact context and full `snapshot` for raw hierarchy/skin details.
`capabilities` lists actual method names and optional features; do not infer availability from a role or documentation for another artifact.

```text
<driver-java> -jar <driver-jar> rpc <endpoint-json> query '{"role":"BUTTON","accessible":"Save","window":"w1","limit":10}'
<driver-java> -jar <driver-jar> rpc <endpoint-json> query '{"eid":"n42","mode":"full"}'
<driver-java> -jar <driver-jar> rpc <endpoint-json> snapshot '{"includeTypes":["Button","TextField"]}'
```

Selector fields are conjunctive, not alternatives.
Supported fields: `handle`, `eid`, `nodeId`, `cssId`, `styleClass`, `textExact`, `text`, `regexText`, `type`, `role`, `accessible`, `selector`, `window`.
Explicit blank IDs/selectors and invalid numeric handles are not broadened into fallback targets.
CSS IDs can be duplicated; mutations reject ambiguous, hidden, disabled or modal-blocked targets.
Use fresh observed `eid`/`handle` and `wid` values for precise active-view targeting.
Handles use weak identity, are never reused, and detached/replaced nodes are not valid targets.
Missing IDs are normal; role/name/text discovery and raw hierarchy remain available.

```text
@42                  observed numeric handle
eid=n42              observed node identity
#save                JavaFX node ID
.primary             style class
=Save                exact control/accessibility text
text=Open            contains control/accessibility text
regexText=Open.*     text regex
type=Button          class name
role=BUTTON          accessible role
accessible=Save      accessible name contains
window=w2            observed window scope
```

`selectorPath` and table column `path` explain context; they are not executable node selectors.
Unrealized virtualized cells are not fabricated or treated as row identities.
Visibility follows ancestors and showing windows, including SubScene hosts; it does not prove physical clickability through clipping or occlusion.

### Metadata

Windows expose ownership, modality, `blockedBy`, bounds and geometry diagnostics.
`withinSingleScreen` and `headlessGeometryRisk` are diagnostics, not automatic resize permission or rendering-health proof.
Nodes expose identity/parent/window, class/ID, accessible role/name, `nameSource`, structural state, bounds and control semantics.
`operations` describes typed/control-handler operations; `accessibleOperations` lists role-derived candidates.
Roles nominate possible operations, not implementation support or successful outcome.
`eligible` covers logical visibility, enabled state and modal blocking; operation-specific editability/bindings still matter.
`valueBound`, `textBound` and sorted `pseudoClasses` support targeted follow-up.
Editable values are not used as accessibility-name fallbacks.

`query` defaults to compact metadata, limit 120, capped at 500; `count` and `truncated` report matching scope.
`mode:"full"` requests richer node semantics, not a recursive application-object dump.
Summary bounds windows, controls, menus, selected tabs and text samples; follow up rather than treating omission as absence.
Snapshot type filters use exact case-sensitive simple names; `excludeTypes` wins.

## Sensitive data

Native password roles and `PasswordField` values are automatically protected.
Applications can mark the actual value control, including a revealed password editor, with `getProperties().put("fxdriver.sensitive", Boolean.TRUE)`.
Marked ancestry also protects descendant metadata and pixels.
Use `sensitive:true` for requests handling secrets, especially custom controls and expected-value assertions.
Request sensitivity survives FX dispatch and nested `run`; reentrant action errors inherit sensitive context.

Sensitive text metadata is `[redacted]`, independent of content length.
Length, caret, selection offsets/content, accessible names/help and value-bearing selection state are suppressed.
Input payload echoes and event payload fields are redacted before retention.
Sensitive exceptions do not expose original throwable chains through RPC.
This is not arbitrary-secret detection in unmarked labels, URLs, application logs or ordinary model values.
Do not log credentials, endpoint tokens or raw secret-bearing client requests.

```text
<driver-java> -jar <driver-jar> rpc <endpoint-json> setText '{"nodeId":"password","value":"<secret>","sensitive":true}'
<driver-java> -jar <driver-jar> rpc <endpoint-json> assert '{"nodeId":"password","value":"<secret>","sensitive":true}'
```

## Actions and value controls

Targets must resolve uniquely; input payload is `value`, while `text` selects a node.
Prefer typed public JavaFX APIs before accessibility candidates or synthesized events.
Dispatch exceptions propagate; an uncertain accessible dispatch is not followed by a second mouse/input attempt.

| Method                   | Purpose                                                                                           |
| ------------------------ | ------------------------------------------------------------------------------------------------- |
| `fire`                   | Native button fire or allowlisted accessibility action                                            |
| `click`                  | Native semantic activation where supported; otherwise synthetic JavaFX mouse events               |
| `focus`                  | Request window/node focus; verify `focused` separately                                            |
| `setText`                | Replace editable input/editor text                                                                |
| `type`                   | Set/append text; `replace:false` appends, not physical typing                                     |
| `clear`                  | Clear editable input/editor text                                                                  |
| `setValue`               | Typed choice/combo/date/color/spinner/range/toggle value operation                                |
| `commit`                 | Commit text/editor into TextFormatter or combo/date/spinner model                                 |
| `showPopup`, `hidePopup` | Public popup model operations or accessibility expansion/collapse candidates                      |
| `increment`, `decrement` | Native step operations; default one step, bounded to 1–1000                                       |
| `expand`, `collapse`     | Titled/tree expansion and allowlisted accessibility candidates                                    |
| `scrollToIndex`          | Indexed virtualized-control scroll                                                                |
| `scroll`                 | Public ScrollPane range adjustment or synthetic JavaFX scroll event; never global cursor movement |

```text
<driver-java> -jar <driver-jar> rpc <endpoint-json> setValue '{"nodeId":"country","value":"Germany"}'
<driver-java> -jar <driver-jar> rpc <endpoint-json> setText '{"nodeId":"editable-choice","value":"New value"}'
<driver-java> -jar <driver-jar> rpc <endpoint-json> commit '{"nodeId":"editable-choice"}'
```

Choice/combo matching uses public converters and rejects duplicate rendered matches.
Date assignment accepts ISO LocalDate before the configured converter.
Model-string `value`, converter/default-derived `displayValue` and actual `editorText` are separate.
These model strings do not prove arbitrary custom-cell presentation.
Compact model/editor strings are bounded and expose truncation; use precise control inspection when more detail is required.

### Keyboard and accelerators

```text
<driver-java> -jar <driver-jar> rpc <endpoint-json> key '{"nodeId":"search","chars":"hello"}'
<driver-java> -jar <driver-jar> rpc <endpoint-json> key '{"window":"w2","key":"ESCAPE"}'
<driver-java> -jar <driver-jar> rpc <endpoint-json> press '{"window":"w1","key":"F1"}'
```

`key` sends synthetic JavaFX events, not OS/global input.
It resolves/guards the target at dispatch, reports `focusStrategy`, `sent` and target metadata, and redacts character payloads.
Named keys/chords include `ENTER`, `ESCAPE`/`ESC`, `TAB`, `F1`, `CTRL+S`, `SHIFT+TAB` and `META+K`.
Without a selector, explicit window scope wins; otherwise focused eligible windows precede top-context fallback.
`press` invokes only the selected scene's accelerator and does not broadcast to other windows.
Missing accelerator returns `NO_ACCELERATOR`; modal-blocked background accelerators are rejected.

### WebView

```text
<driver-java> -jar <driver-jar> rpc <endpoint-json> webExecuteScript '{"nodeId":"browser","script":"document.title"}'
```

`webExecuteScript` resolves one WebView and calls its public WebEngine API with normal modal/sensitivity tracking.
The returned JS-string representation is capped at 512 characters with `valueTruncated`; script dispatch does not verify DOM/application success.
Web metadata reports bounded location/title, zoom, font scale and load state.
The optional `javafx.web` adapter is loaded only for actual WebView targets.

## Selection and bounded model reads

```text
<driver-java> -jar <driver-jar> rpc <endpoint-json> selectIndex '{"nodeId":"projects","index":2}'
<driver-java> -jar <driver-jar> rpc <endpoint-json> selectIndex '{"nodeId":"projects","index":4,"selectionMode":"extend"}'
<driver-java> -jar <driver-jar> rpc <endpoint-json> listItems '{"nodeId":"projects","offset":0,"limit":100}'
<driver-java> -jar <driver-jar> rpc <endpoint-json> tableColumns '{"nodeId":"properties","offset":0,"limit":100}'
<driver-java> -jar <driver-jar> rpc <endpoint-json> tableCell '{"nodeId":"properties","rowIndex":999,"columnIndex":1}'
```

`selectIndex` and `selectListItem` default to replacement; `selectionMode:"extend"` extends supported multiple-selection models.
`selectListItem` uses exact-then-containing model text, rejects ambiguity, and supports `activate:false` to separate selection from activation.
Default activation only fires an available realized row button; inspect both selection and activation outcomes.
Generic `select` is unsupported.

`listItems` requires exactly one list and returns model-string items, total count, offset/limit, tail truncation and text-truncation metadata.
Default page size is 100, maximum 500; item text is capped at 512 characters.
Selection metadata caps indices at 100 and selected items at 20, with total count and truncation flags.

`tableColumns` pages visible leaf columns, including grouped/nested columns, with their actual view indices and contextual paths.
`tableCell` reads model data without requiring a realized row/cell.
Use `rowIndex` or unique `tableText`, and visible-leaf `columnIndex` or unique column ID/text.
Column IDs take precedence over text; duplicate rows/headers are rejected, not resolved by first match.
Replies distinguish model-string representation from verified presentation and bound row/value strings to 512 characters.
Sensitive table data is not read through these model APIs.

### Menus

```text
<driver-java> -jar <driver-jar> rpc <endpoint-json> fireMenuItem '{"nodeId":"main-menu","path":["File","Save"]}'
```

MenuBar/MenuButton/context-menu models can invoke JavaFX menu items without opening their popup.
Exact paths or exact-then-containing `itemText` must resolve uniquely.
Disabled or hidden ancestors block invocation.
This does not provide OS/native-dialog automation or physical system-menu interaction.

## Observable waits and verification

```text
<driver-java> -jar <driver-jar> rpc <endpoint-json> wait '{"nodeId":"status","value":"Saved","timeoutMs":5000}'
<driver-java> -jar <driver-jar> rpc <endpoint-json> assert '{"nodeId":"projects","selectedIndices":[2,4]}'
<driver-java> -jar <driver-jar> rpc <endpoint-json> waitForLayout '{"window":"w1","timeoutMs":2000}'
<driver-java> -jar <driver-jar> rpc <endpoint-json> waitForQuiet '{"untilQuietMs":200,"timeoutMs":5000}'
```

`wait` defaults to present/visible with a 2000 ms deadline, using scoped observable subscriptions and a bounded 100 ms fallback.
Predicates include `present`, `count`, `visible`, `enabled`, `focused`, `value`, `displayValue`, `selected`, `selectedIndex`, `selectedIndices`, `itemsCount`, `expanded`, `editable` and `pseudoClass`.
Value/selection predicates read actual public state; `assert` evaluates the same predicates once.
An absence wait cannot succeed without an executed observation; queued probes are not run after timeout.
Replaced targets are rediscovered and subscriptions cleaned up.
A failed wait returns `TIMEOUT`, observation/poll metadata and bounded near matches when available.
Near matches are diagnostics, not alternate actions or selectors.

`waitForLayout` observes one post-layout pulse, not backend presentation or business completion.
`waitForQuiet` and `click.untilQuietMs` compare sanitized scene-state signatures, including values, selection, transforms and visible realized-cell presentation.
They do not prove application-wide, canvas/WebView pixel, native-dialog or background-work quiescence.
Use explicit semantic outcomes and actual screenshots for those claims.

## Screenshots and images

```text
<driver-java> -jar <driver-jar> rpc <endpoint-json> screenshot '{"path":"<temp-dir>/window.png","window":"w2"}'
<driver-java> -jar <driver-jar> rpc <endpoint-json> screenshot '{"target":"node","nodeId":"save","path":"<temp-dir>/save.png"}'
<driver-java> -jar <driver-jar> rpc <endpoint-json> screenshot '{"target":"rect","x":0,"y":0,"w":320,"h":200,"path":"<temp-dir>/crop.png"}'
```

Supply an absolute output path; omission allocates a private OS-temp image directory.
Window, popup-window, node and intersecting rectangle captures use native JavaFX snapshots, not OS-composited screenshots.
Modal-blocked background captures are rejected.
Rectangles require positive dimensions, intersect image bounds and do not silently shift an entirely off-image request to an edge pixel.
Capture/metadata collection runs on FX; PNG encoding and filesystem I/O run afterward.

Replies include absolute `path`, `bytes`, `target`, image/source bounds and units, scale, `captureType`, redaction count and bounded visible-text orientation.
The historical `activeWindow` field describes the captured window; it is not proof of desktop focus or lack of occlusion.
`physicalOcclusionVerified:false` is explicit.
Sensitive pixels are masked by default; disabling `maskSensitive` requires permission appropriate to the evidence.
Read the actual image before making a visual claim.

CLI `image-summary` reports dimensions, bytes, average color and average hash.
CLI `image-diff` reports changed pixels/bounds, size mismatch and an optional lossless diff image.
These image operations do not establish domain success.

## Events, tracing and cleanup

`events`, `eventsSince` and `clearEvents` expose a bounded 500-event diagnostic buffer.
Events include monotonic `sequence`, timestamp, method, immediate outcome, sensitivity and redacted parameters; pending outcomes can also carry action identity/completion.
`eventsSince.cursor` is the last observed sequence, not a mutable list offset.
`nextCursor` stays monotonic across rollover and clear; `overflow`/`historyTruncated` warn about gaps.
`since` filters by epoch milliseconds and overrides cursor filtering.
Event-read/clear methods do not record themselves.
Use `clearEvents`, not an undocumented `events.clear` flag.

`configure` enables optional non-layout visual tracing; per-action `highlightMs` overrides the default.
`highlight` marks raw matches and a no-match call clears prior highlights.
Application-owned bound/replaced effects are not overwritten during restoration.
`mark` decorates stage titles; driver shutdown attempts restoration of its own visual changes.

`shutdown` stops the server; launched apps exit by default, attached apps remain running.
`exitApp:true` explicitly exits either, so use it only for owned or authorized instances.
A shutdown inside `run` or raw batch is honored after the response; later actions are not dispatched.
Verify owned process exit and preserve attached user sessions.

Video APIs are unavailable.
Check capabilities rather than inventing `videoStart`, `videoStep` or `videoStop`.
