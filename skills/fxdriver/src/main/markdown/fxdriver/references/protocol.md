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
commands only.

Example launch JSON:

```json
{"pid":12345,"port":43123,"token":"...","endpoint":"http://127.0.0.1:43123/rpc","endpointFile":"/abs/target/fxdriver-endpoints/launch-1.json","startupMs":822}
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
- `selectorPath`
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

### `snapshotSummary`

```sh
java -jar @fxdriver.skill.jar@ rpc <port> snapshotSummary '{}'
java -jar @fxdriver.skill.jar@ snapshot <port> --summary
```

Returns a compact first view:

- `windows[]`: window id/index/title/focus/bounds.
- `buttons[]`: visible `ButtonBase` controls with text and selector path.
- `textFields[]`: text inputs with value/prompt/editability.
- `tables[]`: visible `TableView` and `TreeTableView` row/column summaries.
- `selectedTabs[]`: selected tab text per visible `TabPane`.
- `visibleTextSample[]`: bounded visible text sample for orientation.

Use full `snapshot` when you need bounds, semantics, or raw node hierarchy.
Copy `selectorPath` back as `selectorPath` when repeated labels make text
selectors ambiguous.

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

Sends real keystrokes as synthetic `KEY_PRESSED`/`KEY_TYPED`/`KEY_RELEASED`
events (works headless, unlike OS-level robot keys). `key` is a single named key
or chord — `ENTER`, `ESCAPE`/`ESC`, `TAB`, `UP`/`DOWN`/`LEFT`/`RIGHT`,
`F1`–`F12`, letters, `CTRL+S`, `SHIFT+TAB`, `ALT+`/`META+`/`CMD+`. `chars` types
a literal string. With a selector (`nodeId`, `textExact`, …) the target node is
focused first; otherwise events go to the current focus owner. Returns the
resolved key/chars and a description of the target node.

### `select`

```sh
java -jar @fxdriver.skill.jar@ rpc <port> select '{"nodeId":"country","itemText":"Germany"}'
java -jar @fxdriver.skill.jar@ rpc <port> select '{"nodeId":"size","index":2}'
java -jar @fxdriver.skill.jar@ rpc <port> select '{"nodeId":"count","value":"42"}'
```

Drives choice controls that `click`/`fire` cannot: `ComboBox`, `ChoiceBox`,
`TabPane`, `Spinner`, `DatePicker`, `ColorPicker`, `Slider`, `ScrollBar`,
`TableView`, `TreeView`, and `TreeTableView`. Selects by `itemText` (exact then
contains, using the control's `StringConverter` where available), by `index`,
or by `value` where the control exposes a direct value. For a Spinner, a
positive `index` increments and a negative one decrements. Returns `kind`,
`index`, `value`, and `selected`; `ok:false` with `UNSUPPORTED` if the matched
node is not a selectable control, or `NO_ITEM` if no entry matched. (`ListView`
selection has its own `selectListItem`.)

### `tableCell`

```sh
java -jar @fxdriver.skill.jar@ rpc <port> tableCell '{"nodeId":"projects","tableText":"MDP_40982497","column":"Projektnummer"}'
java -jar @fxdriver.skill.jar@ rpc <port> tableCell '{"nodeId":"projects","rowIndex":2,"columnIndex":0}'
```

Returns a `TableView` or `TreeTableView` cell by row text/row index and
column text, id, or index. The result includes `rowIndex`, `columnIndex`,
resolved `column`, full `rowText`, `value`, and `ok`.

### `fireMenuItem`

```sh
java -jar @fxdriver.skill.jar@ rpc <port> fireMenuItem '{"nodeId":"main-menu","path":["File","Save"]}'
java -jar @fxdriver.skill.jar@ rpc <port> fireMenuItem '{"nodeId":"actions","itemText":"Delete"}'
```

Fires `MenuBar`, `MenuButton`, `SplitMenuButton`, or context-menu items without
showing the popup. Use `path` for nested menu traversal or `itemText` to find
the first matching item recursively. Returns the menu kind, resolved value, and
`fired`.

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
waits include `nearMatches[]` with nearby visible text/id/type candidates and
their `selectorPath`.

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

Returns recent fxdriver calls with `ts`, `method`, `ok`, `severity`, and
`params`. `severity` is `info` for successful calls, `miss` for app-level
`ok:false` responses such as exploratory selector misses, and `error` for
protocol-level JSON-RPC errors. If request params include `tag` or `scenario`,
the event copies them to top-level fields. Use `events {"clear":true}` to read
and clear in one call.

### `screenshot`

CLI shortcut for first visible stage/window:

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
and rect targets default to the first showing stage; pass `window` with a `wid`
from snapshot (`"w2"`) to capture another stage. `press` accepts the same
`window` parameter to scope accelerators.

Captures optimized lossless PNG. RPC results include `path`, `bytes`, `target`,
`image` bounds, `source` bounds/units, `scale`, `activeWindow`, and
`visibleTextSample`. CLI screenshot prints that result plus image summary.
Opaque screenshots may be encoded as RGB; transparent screenshots preserve
alpha.

### `videoStart`, `videoStep`, `videoStop`

```sh
java -jar @fxdriver.skill.jar@ rpc <port> videoStart '{"path":"/tmp/run/run.gif","fps":10,"canvas":"fixed","width":1280,"height":900,"titleMode":"band"}'
java -jar @fxdriver.skill.jar@ rpc <port> videoStep '{"title":"Login"}'
java -jar @fxdriver.skill.jar@ rpc <port> videoStop '{}'
```

Records one stage's scene into GIF by default or APNG with `quality:"high"`.
Frames are captured on a timer and encoded as delta rects on a background
thread: unchanged ticks cost no bytes, and the UI thread is never blocked on
encoding (frames are dropped instead and reported as `dropped`; frame delays
come from capture timestamps, so playback speed stays true to wall clock even
under load). Params: `path` (resolved in the target JVM's working directory —
prefer absolute), `fps` (default 10, clamped to 1-30), `maxMs` (hard cap,
default 120000, clamped to 1000-3600000), `quality` (`low` GIF or `high` APNG),
`window` (a `wid` from snapshot), `scalePercent` (clamped to 25-200, default
100), `canvas`, `width`, `height`, `background`, `titleMode`, `titleHeight`,
and `title`. One recording at a time: a second `videoStart` while recording
returns an error.

Canvas modes:

- `canvas:"fixed"` uses explicit `width` and `height`; use this for
  deterministic test videos.
- `canvas:"screen"` (default) uses a stable primary-screen canvas so
  login/splash windows do not trap the recording at their small size.
- `canvas:"firstFrame"` preserves the older behavior where the first captured
  frame defines the animation canvas; use only for small, single-window
  captures or scalePercent comparisons.

Every source frame is composed into the stable canvas. Oversized source frames
are scaled down to fit and centered; smaller windows are centered on the
`background` color (`#RRGGBB` or `#AARRGGBB`, default white).

Native step titles are recorder overlays, not app UI. Set `titleMode:"band"` to
reserve a title bar inside the video canvas, or `titleMode:"overlay"` for a
translucent top-left label. `title` sets the initial title;
`videoStep {"title":"..."}` changes it during recording and forces an immediate
capture tick. `titleMode:"off"` disables drawing titles.

`videoStop` returns `path`, `frames` (encoded), `captured`, `dropped`,
`durationMs`, `bytes`, `startedAt` (epoch ms — correlate with `events`
timestamps to map actions to video time), `canvasWidth`, `canvasHeight`,
`canvas`, `titleMode`, `quality`, `format`, `steps`, `scaledFrames`, and `ok`.
`ok:false` carries an error code: `NO_VIDEO` (nothing to stop), `NO_FRAMES` (no
showing stage ever matched, no file written), `VIDEO_FAILED` (encoder error,
e.g. unwritable path), or `VIDEO_STALLED`. Recording also finalizes on
`shutdown` and on JVM exit. GIF is streamed, so even a killed process can leave
the file playable through the last flushed frame. APNG is patched on close and
requires normal finalization to be playable.

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
path=window[0] ...   selectorPath copied from snapshot
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
{ "selectorPath": "window[0] > control[0] > button#save[0]" }
{ "selector": "type=Button" }
```
