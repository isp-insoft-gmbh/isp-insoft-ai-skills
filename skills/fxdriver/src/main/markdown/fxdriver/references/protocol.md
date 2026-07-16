# fxdriver protocol

Transport: JSON-RPC 2.0 over localhost HTTP POST.

Prototype CLI:

```sh
java -jar @fxdriver.skill.jar@ attach <pid> [port]
java -jar @fxdriver.skill.jar@ launch [port] -- \
  java [java-options...] <main-or-jar> [args...]
java -jar @fxdriver.skill.jar@ rpc <port> <method> '<params-json>' [token]
java -jar @fxdriver.skill.jar@ snapshot <port> [--summary] [token]
```

Attach/launch default to port `0`, so the OS chooses a free loopback port. The
first fxdriver-owned CLI output line is machine-readable JSON with `pid`,
`port`, `token`, `endpoint`, and `endpointFile`. Compatibility lines still print
the chosen port, an endpoint file, and `FXDRIVER_TOKEN`. RPC calls require that
token via `FXDRIVER_TOKEN` or the optional final token argument.

## Attach vs launch

Use `attach` for already-running apps:

```sh
java -jar @fxdriver.skill.jar@ attach <pid>
export FXDRIVER_TOKEN=<printed-token>
```

Use `launch` for repeatable runs where you control the Java command:

```sh
java -jar @fxdriver.skill.jar@ launch -- \
  java --module-path <mods> --add-modules javafx.controls \
  -cp <cp> app.Main
export FXDRIVER_TOKEN=<printed-token>
```

Current launch mode injects `-javaagent` after `java` and supports direct Java
commands only. It supervises the child and blocks until the app exits. Run it
as a persistent/background process, capture its first JSON stdout line, and keep
the launcher alive: killing it also kills the app.

Example launch JSON:

```json
{
  "pid": 12345,
  "port": 43123,
  "token": "...",
  "endpoint": "http://127.0.0.1:43123/rpc",
  "endpointFile": "/abs/target/fxdriver-endpoints/launch-1.json",
  "startupMs": 822
}
```

## Core methods

### `ping`

```sh
java -jar @fxdriver.skill.jar@ rpc <port> ping '{}'
```

Returns liveness.

### `version`, `capabilities`

```sh
java -jar @fxdriver.skill.jar@ rpc <port> version '{}'
java -jar @fxdriver.skill.jar@ rpc <port> capabilities '{}'
```

Use these before relying on optional methods or selector forms.

### `configure`

```sh
java -jar @fxdriver.skill.jar@ rpc <port> configure '{"visualTrace":true,"highlightMs":250,"effect":"invert"}'
java -jar @fxdriver.skill.jar@ rpc <port> configure '{"visualTrace":false}'
```

Controls optional visual tracing. When enabled, supported actions highlight the
resolved target briefly before acting. Default is off. Effects are
non-layout-affecting and apply to any JavaFX `Node`: `invert` (default;
theme-independent), `colorAdjust`, `glow`, `bloom`.

Per-action override:

```sh
java -jar @fxdriver.skill.jar@ rpc <port> click '{"nodeId":"save","highlightMs":250}'
```

`highlightMs:0` disables trace for that action.

### `run`, batch, and `mark`

Group dependent actions with `run`; each step uses `op` (or `method`) plus the
normal method parameters. It stops on the first failed step unless
`continueOnError:true`. `returnState:"compact"` adds bounded state.

```sh
java -jar @fxdriver.skill.jar@ rpc <port> run '{"steps":[{"op":"setText","nodeId":"name","value":"Ada"},{"op":"wait","textExact":"Ada"}],"returnState":"compact"}'
java -jar @fxdriver.skill.jar@ rpc <port> mark '{}'
```

The HTTP endpoint also accepts a raw JSON-RPC request array as a batch. `mark`
visually marks showing stages and needs no parameters.

### `snapshot`

```sh
java -jar @fxdriver.skill.jar@ rpc <port> snapshot '{}'
java -jar @fxdriver.skill.jar@ snapshot <port> --summary
```

Optional type filters (exact simple class name, case-sensitive):

```sh
java -jar @fxdriver.skill.jar@ rpc <port> snapshot '{"includeTypes":["Button","TextField"]}'
java -jar @fxdriver.skill.jar@ rpc <port> snapshot '{"excludeTypes":["LabeledText","FontIcon"]}'
```

Both can be combined. `excludeTypes` wins over `includeTypes`. Default (no
filters) returns all visible nodes.

Returns:

- `windows[]`: visible JavaFX windows with bounds and nested nodes.
- `nodes[]`: flat compatibility list.

Important node fields:

- `eid` / `handle`
- `parent`
- `window`
- `type`
- `id`
- `text`
- `accessibleText`, `accessibleHelp`, `accessibleRole`,
  `accessibleRoleDescription`
- `styleClasses`
- `visible`, `disabled`, `focused`, `managed`, `mouseTransparent`,
  `pickOnBounds`, `opacity`, `cursor`, `actionable`
- `bounds` (screen coordinates), `boundsInLocal`, `layoutBounds`
- optional `semantic` object

Initial `semantic` support:

- `kind`: `labeled`, `button`, `hyperlink`, `toggle`, `checkbox`, `radio`,
  `textInput`, `list`, `tree`, `table`, `treeTable`, `comboBox`, `choiceBox`,
  `spinner`, `tabPane`, `text`, `control`
- `ComboBox`/`ChoiceBox`: `value`, `itemsCount`, `selectedIndex` (+
  `editable`/`showing` for ComboBox); `Spinner`: `value`, `editable`; `TabPane`:
  `value` (selected tab text), `tabsCount`, `selectedIndex`
- `Labeled`: `text`, `graphicType`, `contentDisplay`, `mnemonicParsing`,
  `wrapText`, `textOverrun`
- `ButtonBase`: `armed`; `Button`: `defaultButton`, `cancelButton`; `Hyperlink`:
  `visited`
- `ToggleButton`/`RadioButton`: `selected`, `toggleGroup`
- `CheckBox`: `selected`, `indeterminate`, `allowIndeterminate`
- `TextInputControl`: `value`, `prompt`, `editable`, `selectedText`,
  `selectionStart`, `selectionEnd`, `caretPosition`, `anchor`, `length`,
  `masked`
- `TextField`: `prefColumnCount`; `TextArea`: `prefColumnCount`, `prefRowCount`,
  `wrapText`, `scrollTop`, `scrollLeft`
- `ListView`: `itemsCount`, `selectionMode`, `selectedIndices`, `selectedItems`,
  `focusedIndex`, `editable`, `fixedCellSize`, `orientation`, `placeholderText`
- `TreeView`: `itemsCount` (expanded items), `selectionMode`, `selectedIndices`,
  `selectedItems`, `focusedIndex`, `editable`, `fixedCellSize`, `rootVisible`,
  `rootValue`
- `TableView`: `itemsCount`, `selectionMode`, `selectedIndices`,
  `selectedItems`, `focusedIndex`, `focusedCell`, `editable`, `fixedCellSize`,
  `placeholderText`, `columns`, `sortOrder`
- `TreeTableView`: `itemsCount` (expanded items), `selectionMode`,
  `selectedIndices`, `selectedItems`, `focusedIndex`, `focusedCell`, `editable`,
  `fixedCellSize`, `rootVisible`, `rootValue`, `placeholderText`, `columns`,
  `sortOrder`

`PasswordField` text/value/selectedText are redacted by default
(`masked: true`). Virtualized controls expose model/selection metadata;
unrealized rows/cells are not emitted as nodes.

### Snapshot summary

```sh
java -jar @fxdriver.skill.jar@ snapshot <port> --summary
```

The CLI summary command returns a compact first view. It caps windows at 8,
buttons at 24, text fields at 16, value controls at 24, menus at 16, tables at
12, selected tabs at 12, and the distinct visible-text sample at 40 strings. Integration tests enforce a 16 KiB
response bound for the focused fixture.

- `windows[]`: window id/title/focus/bounds.
- `buttons[]`: visible `ButtonBase` controls with text and disabled state.
- `textFields[]`: text inputs with value/prompt/editability.
- `controls[]`: toggles and choice/date/spinner/range values.
- `menus[]`: menu controls and context-menu targets with nested item paths.
- `tables[]`: visible `TableView` and `TreeTableView` row/column summaries.
- `selectedTabs[]`: selected tab text per visible `TabPane`.
- `focusedNode`: target metadata or `null`.
- `visibleTextSample[]`: bounded visible text for orientation.

Use full `snapshot` when you need complete semantics or raw node hierarchy.

### `highlight`

```sh
java -jar @fxdriver.skill.jar@ rpc <port> highlight '{"selector":"type=Button"}'
java -jar @fxdriver.skill.jar@ rpc <port> highlight '{"selector":"type=Button","effect":"invert"}'
java -jar @fxdriver.skill.jar@ rpc <port> highlight '{"selector":"type=Button","effect":"glow"}'
```

Applies a temporary non-layout JavaFX `Effect` to raw matching `Node`s,
including non-control nodes such as `Text`, layout panes, and `SubScene`.
Default `invert` effect uses blend-difference against white over the node's
local bounds, so it is visible on both light and dark themes without changing
layout. Use to verify selector ambiguity or show humans what target will be
used. Calling `highlight` with no matches clears prior highlights. Visual trace
for actions highlights the resolved actionable target instead.

### `click`

```sh
java -jar @fxdriver.skill.jar@ rpc <port> click '{"nodeId":"find-duplicates"}'
java -jar @fxdriver.skill.jar@ rpc <port> click '{"textExact":"Keep duplicates"}'
java -jar @fxdriver.skill.jar@ rpc <port> click '{"textExact":"Projects","untilQuietMs":500,"timeoutMs":10000}'
```

Fires `ButtonBase` directly when possible; otherwise uses JavaFX Robot center
click. With `untilQuietMs`, waits after the click until the visible UI signature
has stayed stable for that many milliseconds, or returns `QUIET_TIMEOUT`.

### `fire`

```sh
java -jar @fxdriver.skill.jar@ rpc <port> fire '{"textExact":"Find duplicates..."}'
```

Calls `ButtonBase.fire()` on first matching button-like control.

### `type`, `setText`, `clear`

```sh
java -jar @fxdriver.skill.jar@ rpc <port> setText '{"nodeId":"filter-duplicates","value":"portrait"}'
java -jar @fxdriver.skill.jar@ rpc <port> clear '{"nodeId":"filter-duplicates"}'
java -jar @fxdriver.skill.jar@ rpc <port> type '{"nodeId":"filter-duplicates","value":"abc","replace":false}'
```

Prefer `setText` for deterministic tests; use `type` when simulating user input
matters. These set the control's text directly; for real keystrokes (Enter to
submit a dialog, Tab to move focus, accelerators), use `key`.

### `key`

```sh
java -jar @fxdriver.skill.jar@ rpc <port> key '{"key":"ENTER"}'
java -jar @fxdriver.skill.jar@ rpc <port> key '{"key":"CTRL+S"}'
java -jar @fxdriver.skill.jar@ rpc <port> key '{"nodeId":"search","chars":"hello"}'
```

Dispatches synthetic JavaFX `KeyEvent`s to a node; it is not OS/global input.
`key` accepts one named key or chord such as `ENTER`, `ESCAPE`/`ESC`, `TAB`,
`F1`, `CTRL+S`, `SHIFT+TAB`, or `META+K`. `chars` emits one `KEY_TYPED` event
per Unicode code point. With a selector (`nodeId`, `textExact`, …), the first
match is focused; otherwise the current JavaFX focus owner is used. The result
contains `key`, `chars`, `sent`, and `target`; no target returns `NO_FOCUS`.

### `setValue`, `selectIndex`, `selectListItem`

```sh
java -jar @fxdriver.skill.jar@ rpc <port> setValue '{"nodeId":"country","value":"Germany"}'
java -jar @fxdriver.skill.jar@ rpc <port> selectIndex '{"nodeId":"projects","index":2}'
java -jar @fxdriver.skill.jar@ rpc <port> selectListItem '{"nodeId":"duplicates-list","itemText":"portrait"}'
```

Drives controls that `click`/`fire` cannot reliably set. Use `setValue` for
controls with a direct value such as `ChoiceBox`, `ComboBox`, `DatePicker`,
`Slider`, and text-like value controls. Use `selectIndex` for indexed selection
in list/table/tree-style controls. Use `selectListItem` for `ListView` by item
text. A generic `select` method is intentionally unsupported.

### Choice, popup, and step controls

```sh
java -jar @fxdriver.skill.jar@ rpc <port> setValue '{"nodeId":"theme","value":"Dark"}'
java -jar @fxdriver.skill.jar@ rpc <port> showPopup '{"nodeId":"density"}'
java -jar @fxdriver.skill.jar@ rpc <port> hidePopup '{"nodeId":"density"}'
java -jar @fxdriver.skill.jar@ rpc <port> increment '{"nodeId":"days","steps":5}'
java -jar @fxdriver.skill.jar@ rpc <port> decrement '{"nodeId":"days","steps":2}'
```

`setValue` supports choice/combo/date/range/spinner controls. Increment and
decrement default to one step.

### `tableCell`

```sh
java -jar @fxdriver.skill.jar@ rpc <port> tableCell '{"nodeId":"projects","tableText":"MDP_40982497","column":"Projektnummer"}'
java -jar @fxdriver.skill.jar@ rpc <port> tableCell '{"nodeId":"projects","rowIndex":2,"columnIndex":0}'
```

Reads `TableView` or `TreeTableView` model values by row text/row index and
flat top-level column text, id, or index. It does not depend on realized cell
nodes. The result includes `rowIndex`, `columnIndex`, resolved `column`, full
`rowText`, `value`, and `ok`; failures use `NO_TABLE`, `NO_ROW`, or `NO_COLUMN`.

### `fireMenuItem`

```sh
java -jar @fxdriver.skill.jar@ rpc <port> fireMenuItem '{"nodeId":"main-menu","path":["File","Save"]}'
java -jar @fxdriver.skill.jar@ rpc <port> fireMenuItem '{"nodeId":"actions","itemText":"Delete"}'
```

Fires `MenuBar`, `MenuButton`, `SplitMenuButton`, or context-menu items without
showing a JavaFX popup. It cannot operate native menus or dialogs. Use `path`
for nested traversal or `itemText` for exact-then-contains recursive lookup.
The result contains `query`, `kind`, `path`, `value`, and `fired`; failures use
`NO_MENU_ITEM` or `DISABLED`.

### `wait`

```sh
java -jar @fxdriver.skill.jar@ rpc <port> wait '{"text":"Done","timeoutMs":5000}'
java -jar @fxdriver.skill.jar@ rpc <port> wait '{"nodeId":"save","enabled":true,"timeoutMs":5000}'
```

Polls until selector is present by default. Default timeout is 2000 ms; pass
explicit `timeoutMs` for slow app operations. Optional predicates: `present`,
`enabled`, `focused`, `visible`. Like the action commands, `wait` and `assert`
only consider visible nodes unless `visible:false` is passed explicitly, so a
satisfied `wait` means the follow-up `click`/`type` sees the same node. Failed
waits return `ok:false` with `TIMEOUT` and up to five bounded `nearMatches`.
Each candidate has `eid`, `type`, `id`, `text`, and diagnostic `selectorPath`.
Near matches and selector paths explain misses; they are not supported
selectors. Successful waits omit `nearMatches`.

### `assert`

```sh
java -jar @fxdriver.skill.jar@ rpc <port> assert '{"textExact":"Keep duplicates","present":true}'
java -jar @fxdriver.skill.jar@ rpc <port> assert '{"selector":"type=Button","count":3}'
```

Returns `{ok,count}`. The CLI exits non-zero only on a protocol-level JSON-RPC
error (top-level `error` member); app-level `ok:false` results exit zero and
need inspection in scripts.

### `events`, `clearEvents`

```sh
java -jar @fxdriver.skill.jar@ rpc <port> events '{}'
java -jar @fxdriver.skill.jar@ rpc <port> events '{"clear":true}'
java -jar @fxdriver.skill.jar@ rpc <port> clearEvents '{}'
```

Returns recent fxdriver calls with `ts`, `method`, `ok`, and `params`. If
request params include `tag` or `scenario`, the event copies them to top-level
fields. Use `events {"clear":true}` to read and clear in one call.

### `screenshot`

CLI shortcut for the focused showing stage, then first showing stage:

```sh
java -jar @fxdriver.skill.jar@ screenshot <port> target/fxdriver.png
```

RPC supports window/default, node, and rect targets:

```sh
java -jar @fxdriver.skill.jar@ rpc <port> screenshot '{"path":"/tmp/run/window.png"}'
java -jar @fxdriver.skill.jar@ rpc <port> screenshot '{"target":"node","nodeId":"save","path":"/tmp/run/save.png"}'
java -jar @fxdriver.skill.jar@ rpc <port> screenshot '{"target":"rect","x":0,"y":0,"w":320,"h":200,"path":"/tmp/run/crop.png"}'
java -jar @fxdriver.skill.jar@ rpc <port> screenshot '{"path":"/tmp/run/dialog.png","window":"w2"}'
```

A relative `path` resolves against the target app's working directory, not the
caller's — pass an absolute path (or use the CLI `screenshot` subcommand, which
absolutizes before sending). The result echoes the absolute path written. Window
and rect targets default to the focused showing JavaFX stage, then the first
showing stage; pass `window` with a stage `wid` from snapshot (`"w2"`) to
capture that exact stage. Popup window ids are rejected. `press` accepts the
same parameter to scope accelerators.

Captures optimized lossless PNG. RPC results include `path`, `bytes`, `target`,
`image` bounds, `source` bounds/units, `scale`, `activeWindow`, and a bounded
`visibleTextSample` for orientation. `activeWindow` contains only id, title,
bounds, and focused-node metadata—not a recursive snapshot. CLI screenshot
prints that result plus image summary. Opaque screenshots may be encoded as
RGB; transparent screenshots preserve alpha.

### Video and modal dispatch

`videoStart`, `videoStep`, and `videoStop` are not available in this phase.
Modal-safe asynchronous action dispatch is also deferred. Check `capabilities`
rather than assuming either API exists.

### `image-summary`

```sh
java -jar @fxdriver.skill.jar@ image-summary target/fxdriver.png
```

Reports path, bytes, width, height, average color, and average hash.

### `image-diff`

```sh
java -jar @fxdriver.skill.jar@ image-diff before.png after.png target/diff.png
```

Reports changed pixels, percent, changed bounds, size mismatch, and optional
optimized lossless diff PNG path.

### `listItems`

```sh
java -jar @fxdriver.skill.jar@ rpc <port> listItems '{"nodeId":"duplicates-list"}'
```

Returns `ListView` item strings.

### `selectListItem`

```sh
java -jar @fxdriver.skill.jar@ rpc <port> selectListItem '{"nodeId":"duplicates-list","itemText":"portrait"}'
```

Selects matching item. Default `activate=true` also fires a matching realized
row button if available.

### `scroll`

```sh
java -jar @fxdriver.skill.jar@ rpc <port> scroll '{"selector":"type=ScrollPane","amount":6}'
```

Uses control-specific scroll when possible; Robot fallback otherwise.

### `shutdown`

```sh
java -jar @fxdriver.skill.jar@ rpc <port> shutdown '{}'
```

Stops the target JVM's fxdriver RPC server, not the app itself.

## Selectors

Supported forms:

```text
@handle              capability label for numeric handles
@42                  handle
eid=n42              node eid
#node-id             JavaFX node id
.style-class         JavaFX style class
=exact text          exact visible/control text
text=Open            contains visible/control text
regexText=Open.*     regex over visible/control text
type=Button          Java class simple name
role=BUTTON          accessible role
accessible=Save      accessible text contains
```

JSON params that map to selectors:

```json
{ "handle": 42 }
{ "nodeId": "save" }
{ "cssId": "save" }
{ "styleClass": "primary" }
{ "textExact": "Save" }
{ "text": "Save" }
{ "regexText": "Sa.*" }
{ "type": "Button" }
{ "role": "BUTTON" }
{ "accessible": "Save" }
{ "selector": "type=Button" }
```

Snapshot and near-match `selectorPath` values are diagnostics only and cannot
be passed back as selectors. Generic `select` is unsupported; use the explicit
selection methods above.
