# fxdriver failure examples

Use [runtime preflight](runtime.md) for executable/JAR paths and placeholders.
These patterns are exercised by intentional integration failures, not permission to repeat uncertain mutations.
Observe the exact reply, gather the missing evidence, then change one thing.

## Wait timed out

A ready fixture queried with `text:"Refresh toolbr"` returns `ok:false`, `TIMEOUT`, observation metadata and bounded near matches including `Refresh toolbar`.
A corrected selector or targeted query resolves the actual control.
If the FX thread never executed a probe before deadline, `observed:false`, `count:-1` and empty near matches are honest missing evidence, not proven absence.
First establish app readiness separately from endpoint readiness.

```text
<driver-java> -jar <driver-jar> rpc <endpoint-json> wait '{"nodeId":"project-filter","timeoutMs":10000}'
<driver-java> -jar <driver-jar> rpc <endpoint-json> query '{"role":"BUTTON","text":"Refresh"}'
```

## Wrong input field

`setText`/`type` require `value`; `text` selects a node.
A request containing only `text:"wrong field"` is rejected rather than inserting that selector text.

```text
<driver-java> -jar <driver-jar> rpc <endpoint-json> setText '{"nodeId":"project-filter","value":"right field"}'
```

## Wrong primitive or uncommitted editor

A click dispatch can succeed without selecting the intended choice or opening the intended application view.
Use converter-aware `setValue` or explicit `selectIndex`, then verify actual `displayValue`/selection.
For editable combo/date/spinner controls, editor text and model value are distinct until `commit`.
Duplicate displayed choices are rejected rather than choosing the first matching domain object.

```text
<driver-java> -jar <driver-jar> rpc <endpoint-json> setValue '{"nodeId":"domain-choice","value":"Beta"}'
<driver-java> -jar <driver-jar> rpc <endpoint-json> assert '{"nodeId":"domain-choice","displayValue":"Beta"}'
```

## No stable ID or multiple candidates

An ID-less custom Region can expose its name through accessible `TEXT` and nominate a `FIRE` candidate through its role.
Discover by role/name and verify the resulting state; roles do not prove handler support.
Exact text can also match skin nodes, and IDs can be duplicated.
Refine with role/window scope or a fresh observed `eid` instead of selecting the first plausible match.
Improve source IDs/labels only within authorized scope.

## Dispatch exception after a partial effect

The accessible-action fixture increments its counter and then throws.
The driver reports failure, does not retry through synthetic mouse input, and default `run` stops before later steps.
Inspect actual counters/UI state before considering any further mutation.
A failed callback is not a rollback guarantee.

## Modal work and cleanup

A dialog opener may return `pending` with `actionId`.
Drive the observed dialog, then inspect `actionStatus` and verify domain completion.
`cancelling` can mean the action body never started but its setup/cleanup is still active.
Late setup, cleanup or modal-unwind failure must not be treated as an earlier successful callback.
`RECONCILIATION_TIMEOUT` blocks later batch dispatch even when `continueOnError` permits ordinary known failures.

## Protected values

Password and marked/revealed inputs return `[redacted]`, not real content, length or selection offsets.
Use `sensitive:true` with an expected-value assertion when authorized verification is needed.
Native/custom secret controls must not leak through action echoes, events, help/name metadata or screenshot pixels.
Do not store raw secret client requests in the evidence log.

## Visual evidence

```text
<driver-java> -jar <driver-jar> rpc <endpoint-json> screenshot '{"path":"<temp-dir>/before.png"}'
<driver-java> -jar <driver-jar> rpc <endpoint-json> fire '{"role":"BUTTON","textExact":"Open"}'
<driver-java> -jar <driver-jar> rpc <endpoint-json> wait '{"nodeId":"details","present":true,"timeoutMs":2000}'
<driver-java> -jar <driver-jar> rpc <endpoint-json> screenshot '{"path":"<temp-dir>/after.png"}'
<driver-java> -jar <driver-jar> image-diff <temp-dir>/before.png <temp-dir>/after.png <temp-dir>/diff.png
```

Read actual images; a file existing, image hash or changed-pixel count does not establish visual correctness.
JavaFX scene snapshots do not include unrelated desktop occlusion or native dialogs.
Inspect backend logs too: the driver-free geometry comparison produced blit failures while its process still exited 0.

## Event trail

Events preserve immediate request outcomes with redacted parameters, monotonic sequence cursors and overflow metadata.
They do not turn a pending request into completed application work.
Read `events` or `eventsSince`, preserve the bounded redacted reply, then call `clearEvents` explicitly.
The undocumented `events {"clear":true}` flag is not supported.
