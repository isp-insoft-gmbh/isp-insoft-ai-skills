# fxdriver failure examples

These examples are based on integration tests that intentionally drive the
probe app into failed or ambiguous states. Keep the pattern: observe the exact
tool result, gather the next artifact, then change one thing.

## Wait timed out

Observed result:

```json
{"result":{"query":"text=Probe buton","present":true,"count":0,"ok":false,"error":{"code":"TIMEOUT","message":"Wait predicate not satisfied"}}}
```

Useful recovery:

1. Do not retry the same typo.
2. Run `snapshot` or CLI `snapshot --summary`.
3. Compare visible text and control ids, then retry with the corrected selector.

Verified recovery signal: a full snapshot after the timeout contained
`Probe button`.

## Input payload was wrong

Observed command:

```sh
java -jar fxdriver.jar rpc <port> setText '{"nodeId":"probe-field","text":"wrong field"}'
```

Observed result included:

```text
setText/type require value
```

Useful recovery: for `setText` and `type`, put the input in `value`. `text` is a
selector field.

```sh
java -jar fxdriver.jar rpc <port> setText '{"nodeId":"probe-field","value":"right field"}'
```

## Wrong primitive for a choice control

Observed behavior:

1. `click {"nodeId":"probe-choice"}` returned `ok:true`.
2. `wait {"text":"blue"}` still timed out.
3. `setValue {"nodeId":"probe-choice","value":"blue"}` returned `ok:true`.
4. `wait {"text":"blue"}` then passed.

Useful recovery: `ok:true` on an action means the primitive ran, not that the
intended state changed. For choice-like controls, prefer `setValue` or
`selectIndex`, then wait/assert the resulting value.

## Useful control has no id

Observed snapshot fragment:

```json
{"type":"Button","id":"","nodeId":"","text":"Tool action","actionable":true}
```

Useful recovery:

1. If this is a one-off run, target by exact text or role plus text and verify
   with `highlight`.
2. If this flow will be repeated, ask permission to add a minimal semantic
   JavaFX id such as `tool-action`.
3. Add only ids that describe user intent, follow local style, and leave them in
   the app for future navigation.

## Visual change needs image evidence

Verified sequence:

```sh
java -jar fxdriver.jar rpc <port> screenshot '{"path":"/abs/before.png"}'
java -jar fxdriver.jar rpc <port> click '{"textExact":"charlie"}'
java -jar fxdriver.jar rpc <port> wait '{"text":"clicked charlie","timeoutMs":2000}'
java -jar fxdriver.jar rpc <port> screenshot '{"path":"/abs/after.png"}'
java -jar fxdriver.jar image-diff /abs/before.png /abs/after.png /abs/diff.png
```

The screenshot RPC returned image metadata with `source`, and the files existed.
`image-diff` returned `changedPixels` and wrote the diff PNG.

Useful recovery: read the screenshots. The diff proves where pixels changed,
not whether the UI is correct.

## Use events as a trail

Observed events included method names, params, and `ok` values:

```json
{"method":"wait","ok":false,"params":{"text":"Probe buton","timeoutMs":250}}
{"method":"setText","ok":false,"params":{"nodeId":"probe-field","text":"wrong field"}}
```

Useful recovery: use `events {"clear":true}` at failure boundaries to preserve a
short trail of what the agent actually tried. Treat `ok:false` entries as the
first questions to explain.
