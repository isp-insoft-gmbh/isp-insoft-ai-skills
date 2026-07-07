---
name: experimentation
description: "Use when agent is about to create/run throwaway code or generated artifacts—scratch scripts, spikes, probes, PoCs, one-off parsers/converters, microbenchmarks, API/dependency behavior checks—to answer a narrow technical question. Enforces falsifiable hypothesis, controls/baselines, independent verification, provenance, promote-or-delete, and cleanup. Do not use for normal feature implementation/review, permanent project tests, or web research without scratch code/artifacts."
---

# Experimentation

Temp code = experiment.
Learn → verify → promote/delete → clean.

## Trigger

Use if task creates/runs throwaway code/data/artifacts:

- scratch scripts/files, spikes, probes, PoCs
- microbenchmarks, migration dry-runs
- one-off parsers/converters/generators
- API/dependency/env behavior checks
- temp fixtures/logs/output dirs to answer 1 question

Do **not** use for normal impl/review/tests except exploratory phase.

## Protocol

### 1. Hypothesis

Before code/run:

```text
Q: ...
H: if <condition/change/input> → <observable> because <reason>
Falsifier: false if <specific obs>
Control/baseline: ...
Cmd/input/env: ...
Artifacts: <temp paths>
Cleanup: delete <paths> unless promoted
```

Unfalsifiable H ⇒ rewrite.

### 2. Isolation

Default:
outside repo.

```bash
tmp="$(mktemp -d "${TMPDIR:-/tmp}/pi-exp.XXXXXX")"
printf '%s\n' "$tmp"
```

Repo only when needed:

- single obvious dir:
  `.pi/tmp/<slug>/` | `tmp/<slug>/`
- record every ignored/untracked path
- `git status --short` before+after

No scattered scratch files.

### 3. Provenance

Record if result matters:

- exact cmd(s), inputs, sample data
- tool/runtime/dependency versions; lockfile state
- env/config/flags/params
- random seed(s); nondeterminism notes
- commit hash if git
- timing context:
  hw/load/warmup/repeats

Prefer executable protocol over prose:
cmd/test/mise task/script/fixture.

### 4. Verification ladder

Use ≥2 independent checks when possible.

1. Predict output before run.
2. Positive control:
   known-good passes.
3. Negative control:
   known-bad fails.
4. Edge cases:
   empty/min/max/weird.
5. Assertions/invariants fail loud.
6. Cross-check via 2nd method/tool/manual sample.
7. Rerun:
   deterministic or bounded variance.
8. Seed randomness or report variance.
9. Compare baseline/current vs changed.
10. Explain mismatch; unresolved ⇒ inconclusive.

1 green run = weak evidence.
Log/screenshot without cmd provenance = weak.

### 5. Promote | summarize | delete

Classify every artifact:

- **Promote**:
  durable test/fixture/doc/bench harness/script/repro.
- **Summarize**:
  learned fact only → response/issue/commit msg.
- **Delete**:
  temp script/output/cache/log/download/build product.

Promotion requirements:

- named project location; no local abs paths/debug prints
- minimal fixture size
- conforms to fmt/lint/test conventions

## Cleanup

Before final answer:

1. Stop started processes.
2. Delete created scratch files/dirs/outputs/logs/caches/dumps/ad-hoc fixtures.
3. Keep only explicitly promoted artifacts.
4. `git status --short` if git.
5. Explain/ask about unknown leftovers.

Delete rule:

```text
Only delete explicit paths created by this experiment.
Never delete user data, research cache, repo files, broad globs, or git-clean without confirmation.
```

Final report shape:

```text
Result: supported | falsified | inconclusive
Evidence: cmd(s) + checks
Promoted: <paths + why>
Cleaned: <paths>
Remaining: <intentional dirty paths>
```

## Smells

- `test.py`/`scratch.js`/`out.json`/`log.txt` in repo root
- hidden generated dir no owner
- real data mutated vs copied sample
- manual step not captured
- bench no warmup/repeats/context
- randomness no seed/variance
- claim from 1 happy path
- broad cleanup:
  `rm -rf *`, `git clean -fdx` sans consent
