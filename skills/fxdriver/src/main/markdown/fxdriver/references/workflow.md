# fxdriver workflow

## Attach

Find Java processes:

```sh
jcmd -l
```

Attach:

```sh
java -jar @fxdriver.skill.jar@ attach <pid>
export FXDRIVER_TOKEN=<printed-token>
java -jar @fxdriver.skill.jar@ rpc <printed-port> ping '{}'
```

## Observe

Start with the compact summary when orienting:

```sh
java -jar @fxdriver.skill.jar@ snapshot <printed-port> --summary > target/fxdriver-summary.json
```

Use a full snapshot when the summary is not enough:

```sh
java -jar @fxdriver.skill.jar@ rpc <printed-port> snapshot '{}' > target/fxdriver-snapshot.json
```

Look for:

- visible windows;
- stable `nodeId` values;
- `selectorPath` values for repeated labels;
- actionable buttons/text inputs/lists;
- exact text vs duplicate skin/internal text;
- disabled/focused/common node state;
- `semantic.kind` and control-specific state (`selected`, `value`, `prompt`,
  `editable`, checkbox indeterminate state, radio toggle group, list/table
  counts, selected indices/items, focused index, columns, etc.).

## Choose selectors

Preference order:

1. `nodeId` / `#id`
2. `selectorPath` copied from snapshot when labels repeat
3. `eid` if stable within this attached session
4. `textExact`
5. `role` + text/accessibility
6. `type` for broad discovery only
7. bare text contains only as last resort

Verify ambiguous selectors with `highlight`:

```sh
java -jar @fxdriver.skill.jar@ rpc <port> highlight '{"text":"Find"}'
```

## Act

Examples:

```sh
java -jar @fxdriver.skill.jar@ rpc <port> click '{"nodeId":"find-duplicates"}'
java -jar @fxdriver.skill.jar@ rpc <port> click '{"textExact":"Projects","untilQuietMs":500,"timeoutMs":10000}'
java -jar @fxdriver.skill.jar@ rpc <port> setText '{"nodeId":"filter-duplicates","value":"portrait"}'
java -jar @fxdriver.skill.jar@ rpc <port> selectListItem '{"nodeId":"duplicates-list","itemText":"portrait"}'
java -jar @fxdriver.skill.jar@ rpc <port> tableCell '{"nodeId":"projects","tableText":"MDP_40982497","column":"Projektnummer"}'
```

## Wait/assert

After action, wait for observable state:

```sh
java -jar @fxdriver.skill.jar@ rpc <port> wait '{"text":"portrait","timeoutMs":30000}'
java -jar @fxdriver.skill.jar@ rpc <port> assert '{"textExact":"Keep duplicates","present":true}'
```

Do not assume immediate UI update after action.

If a wait fails, read `nearMatches[]` before trying another selector.

## Visual review

When task mentions colors, layout, overflow, visual quality, screenshots are
mandatory:

```sh
java -jar @fxdriver.skill.jar@ screenshot <port> target/before.png
# act/change app
java -jar @fxdriver.skill.jar@ screenshot <port> target/after.png
java -jar @fxdriver.skill.jar@ image-diff target/before.png target/after.png target/diff.png
```

Read screenshots. Diff alone tells change amount, not quality.

## Video evidence

For real app flows, prefer a fixed video canvas and native step titles:

```sh
java -jar @fxdriver.skill.jar@ rpc <port> videoStart '{"path":"/abs/run/flow.gif","canvas":"fixed","width":1280,"height":900,"titleMode":"band","title":"Login"}'
java -jar @fxdriver.skill.jar@ rpc <port> videoStep '{"title":"Create project"}'
# act/wait/assert
java -jar @fxdriver.skill.jar@ rpc <port> videoStep '{"title":"Search results"}'
java -jar @fxdriver.skill.jar@ rpc <port> videoStop '{}'
```

Use `canvas:"fixed"` whenever the flow may switch between login, splash, main,
or modal windows. Use absolute paths so artifacts land where the caller expects.
Check `videoStop` for `canvasWidth`, `canvasHeight`, `steps`, and
`scaledFrames`; do not assume a GIF is readable merely because it exists.

## Failure artifacts

On failure, collect:

```sh
java -jar @fxdriver.skill.jar@ rpc <port> snapshot '{}' > target/failure-snapshot.json
java -jar @fxdriver.skill.jar@ screenshot <port> target/failure.png > target/failure-summary.json
java -jar @fxdriver.skill.jar@ rpc <port> events '{"clear":true}' > target/failure-events.json
```

Then inspect app logs separately.

## Clean up

```sh
java -jar @fxdriver.skill.jar@ rpc <port> shutdown '{}'
```

This stops only fxdriver RPC server. Kill the app process separately if the run
launched it.
