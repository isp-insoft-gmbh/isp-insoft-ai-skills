---
name: fxdriver-instructions
description:
  "Clarify and harden human instructions before using fxdriver to run a real
  JavaFX app workflow, especially when credentials, data setup, app state, or
  video evidence are involved."
license: MIT
compatibility:
  "Use alongside the fxdriver skill; this skill is about test instructions and
  acceptance clarity, not low-level driver commands."
---

# fxdriver-instructions

Use this skill before a non-trivial fxdriver run when the user's request
describes an app workflow, acceptance test, demo recording, data-creation task,
or real-app verification and important details are missing or ambiguous.

The goal is not to slow the run down. Ask only for details that affect
correctness, reproducibility, data safety, or the final evidence artifact. If a
reasonable assumption is safe, proceed and state it.

## Clarify Only What Matters

Before driving the app, check whether the request clearly defines:

- App entrypoint: binary, launcher, working directory, required environment
  variables.
- Starting state: fresh profile/data, existing user state, reset/demo-data
  setup, required license or debug mode.
- Credentials: username, password, first-login password reset behavior, whether
  remembered login is acceptable.
- Data setup: manual creation vs fixture/demo generator, exact counts vs "at
  least", total counts vs per-parent counts, names/identifiers that should be
  searchable later.
- Workflow evidence: which screens must be shown, whether searches must merely
  show results or open found entities, which dialogs must be visible at the end.
- Artifacts: output directory, GIF/MP4/both, target window size/canvas, whether
  step titles are desired, naming convention for files.
- Cleanup: leave the app running, close gracefully, kill launched process,
  preserve or discard generated data.
- Workarounds: whether attach agents, debug hooks, internal reset buttons, or
  app restarts are allowed when fxdriver primitives cannot drive the requested
  UI directly.

## Ask Compactly

Prefer one short grouped question instead of a long interview. Good examples:

```text
Before I run this: should I use a fresh profile, is generated fixture data
acceptable for the requested entities, and where should I write the final
video?
```

```text
Two details affect correctness: do you need exactly 5 entities total or 5 per
parent, and should the search step open the found records or only show result
lists?
```

If the user already gave enough detail, do not ask. Execute.

## Make Safe Assumptions Explicit

When proceeding without clarification, state the assumption in one sentence:

```text
I will write artifacts under the app repo's target directory and close only the
process I launch.
```

```text
I will use generated fixture data because the request asks for realistic data,
not hand-entered field-by-field data.
```

## Escalate Ambiguity

Ask before acting when a wrong assumption could invalidate the run:

- Destructive data reset or production-like data.
- Ambiguous credentials or password reset.
- Exact entity counts when the app UI exposes per-parent counts.
- Artifact location that the user will immediately consume.
- Internal/debug workarounds that alter normal user behavior.

## Pair With fxdriver

After instructions are clear enough, switch to the `fxdriver` skill for the
actual observe/act/wait loop. For long video evidence, prefer fixed-canvas
`videoStart` with step titles:

```sh
java -jar fxdriver.jar rpc <port> videoStart \
  '{"path":"/abs/run/flow.gif","canvas":"fixed","width":1280,"height":900,"titleMode":"band","title":"Login"}'
java -jar fxdriver.jar rpc <port> videoStep '{"title":"Search"}'
```
