# fxdriver workflow

## Cross-harness execution

Use the harness's standard shell/process facilities and HTTP, not a custom Pi tool or extension.
Keep endpoint/token and owned process identity in test-owned session state; reuse them across calls.
A persistent HTTP caller is an optimization, not a prerequisite; the Java CLI remains a portable fallback.
Do not introduce a harness-specific workflow engine or app-specific automation into the generic skill.

## Own the lifecycle

Complete [runtime preflight](runtime.md) before preparing isolated app data or changing cwd.

- Inspect project startup instructions and existing processes before launching.
- Use isolated test-owned data and OS-temp artifacts for experiments.
- Launch through a persistent supervisor; record its identity immediately.
- Use verified absolute driver/app Java executables and the installed JAR; do not invoke tool-manager shims under data-isolation overrides.
- Observe app readiness separately from endpoint readiness.
- Compare loaded agent SHA-256 with the selected bundled JAR, then read capabilities; identical snapshot versions are not proof.
- Preserve existing user sessions when an older agent is loaded; do not restart them merely to complete tool installation.

## Spend context on decisions

- Discover navigation by accessible name, form controls by stable ID, unknown controls by filtered role/type/text queries.
- Prefer advertised summary for orientation, targeted queries for decisions, `tableCell` for model values.
  Inspect a full snapshot filtered locally only for missing state or relationships.
  Debug query mode need not expose richer values or traverse table internals.
- Treat hidden ancestors, empty names, disabled state, virtualized rows and truncation as uncertainty, not permission to guess.
- Selector fields are conjunctive; refine role/name/window scope rather than accepting the first plausible match.
- A handle is disposable; rediscover after control replacement and never use recycled virtualized cells as model identity.
- Roles nominate accessibility operations; verify support/outcomes and never fallback-retry an uncertain dispatch.

## Improve source when it pays

Within authorized source scope, fix the smallest missing semantic boundary that obstructs driving.
Use stable domain/input/action IDs, real label associations, named icon actions and keyboard-operable controls.
Keep IDs independent of translated text, row position, object identity and runtime data.
Do not label every layout/skin node or add test-only runtime overlays.
For composite inputs, put the reusable input ID on exactly one active child and keep label association synchronized.
Rebuild and verify actual targets, driver-visible metadata and required interaction behavior, not merely source assignments.
Accessibility APIs are a source of driving semantics; general accessibility audits and screen-reader testing are separate tasks.
Continue to support safe text/role/structural discovery when source cannot be changed.

## Execute short plans

- Batch known dependent primitives with `run`, ending in a meaningful completion predicate.
- Keep discovery boundaries outside a batch when later targets cannot be known yet.
- Prefer one persistent HTTP session over one JVM per command.
- Query the state needed for the next decision; avoid unconditional full snapshots.
- Verify readback after reload for edits; successful primitive or unchanged Save-button state is insufficient.
- Separate editor changes from `commit`, replacement from extended selection, layout pulses from rendered evidence.
- Mark actual secret inputs, including revealed editors, and use sensitive expected-value assertions instead of exposing content.

## Recover without repeated mutations

- Stop at first unexpected failure.
- On timeout, query actual windows/state before retrying: modal action may have completed.
  Reconcile observed `actionId` status after modal closure; `pending`, `cancelling` and `unknown` are not terminal success.
  Client-side request overlap alone is not a modal workaround.
- Inspect enabled state, selected indices and semantic values explicitly.
- Capture the observed dialog window ID; verify returned `activeWindow` and dimensions.
  Use a dialog-node crop only as a fallback.
- Preserve bounded driver replies and app logs; redact credential-bearing params.
- Use supported shutdown for launched test instances and verify process exit.
  Use normal app UI when required by its shutdown workflow; do not infer lifecycle behavior from a stale artifact.
- Never kill an attached user-owned app as generic cleanup.
