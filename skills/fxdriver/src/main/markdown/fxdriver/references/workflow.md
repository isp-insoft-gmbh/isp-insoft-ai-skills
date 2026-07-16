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
- actionable buttons/text inputs/lists;
- exact text vs duplicate skin/internal text;
- disabled/focused/common node state;
- `semantic.kind` and control-specific state (`selected`, `value`, `prompt`,
  `editable`, checkbox indeterminate state, radio toggle group, list/table
  counts, selected indices/items, focused index, columns, etc.).

## Choose selectors

Preference order:

1. `nodeId` / `#id`
2. `eid` if stable within this attached session
3. `textExact`
4. `role` + text/accessibility
5. `type` for broad discovery only
6. bare text contains only as last resort

Do not copy snapshot or near-match `selectorPath` diagnostics into requests.
They are deliberately not selectors.

Verify ambiguous selectors with `highlight`:

```sh
java -jar @fxdriver.skill.jar@ rpc <port> highlight '{"text":"Find"}'
```

If the workflow needs repeated or future automation and important controls lack
stable ids, ask the human for permission to add JavaFX ids to the app under
test. When approved, add the smallest useful set of semantic ids, follow local
style and naming conventions, and leave them in the app. Do not add broad,
mechanical ids to every node; target controls that represent user intent,
navigation landmarks, form fields, menus, tables/lists, and primary actions.

## Act

Examples:

```sh
java -jar @fxdriver.skill.jar@ rpc <port> click '{"nodeId":"find-duplicates"}'
java -jar @fxdriver.skill.jar@ rpc <port> click '{"textExact":"Projects","untilQuietMs":500,"timeoutMs":10000}'
java -jar @fxdriver.skill.jar@ rpc <port> setText '{"nodeId":"filter-duplicates","value":"portrait"}'
java -jar @fxdriver.skill.jar@ rpc <port> selectListItem '{"nodeId":"duplicates-list","itemText":"portrait"}'
java -jar @fxdriver.skill.jar@ rpc <port> key '{"nodeId":"filter-duplicates","key":"ENTER"}'
java -jar @fxdriver.skill.jar@ rpc <port> fireMenuItem '{"nodeId":"main-menu","path":["File","Save"]}'
java -jar @fxdriver.skill.jar@ rpc <port> tableCell '{"nodeId":"projects","tableText":"MDP_40982497","column":"Projektnummer"}'
```

`key` is synthetic JavaFX input, not OS/global input. Menu firing resolves and
fires the model item without showing its JavaFX popup. `tableCell` reads model
values from flat columns. There is no generic `select`; choose the explicit
selection method for the control.

## Wait/assert

After action, wait for observable state:

```sh
java -jar @fxdriver.skill.jar@ rpc <port> wait '{"text":"portrait","timeoutMs":30000}'
java -jar @fxdriver.skill.jar@ rpc <port> assert '{"textExact":"Keep duplicates","present":true}'
```

Do not assume immediate UI update after action.

If a wait fails, read the returned `ok:false`/`TIMEOUT` and its at-most-five
`nearMatches`, then take a fresh summary or full snapshot if needed. Near-match
ids/text/types help diagnose a typo; their selector paths are not selectors.

## Visual review

When task mentions colors, layout, overflow, visual quality, screenshots are
mandatory:

```sh
java -jar @fxdriver.skill.jar@ screenshot <port> target/before.png
# act/change app
java -jar @fxdriver.skill.jar@ screenshot <port> target/after.png
java -jar @fxdriver.skill.jar@ image-diff target/before.png target/after.png target/diff.png
```

Read screenshots. Diff alone tells change amount, not quality. Screenshot JSON
also identifies `activeWindow` and provides a bounded `visibleTextSample` for
orientation; it is not a replacement for reading the image.

## Video evidence

Video RPCs are not available in this phase. Check `capabilities`; use repeated
screenshots when still-image evidence is sufficient. Modal-safe asynchronous
action dispatch is also outside this phase.

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
