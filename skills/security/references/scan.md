# Scan

Review authorized first-party product code and configuration for likely security defects.
Return bounded, source-backed candidates and identify which still need regression testing.
Read-only: change no file, branch, or external system in this phase.

## Scope

Include:

- first-party production source;
- runtime and deployment configuration;
- migrations and shipped scripts;
- first-party trust-boundary glue;
- unsafe first-party use or configuration of dependencies.

Exclude:

- defects residing solely in dependencies;
- generated and vendored code;
- CI infrastructure;
- test-only defects;
- correctness or reliability bugs without a plausible security-boundary consequence.

## Review loop

### 1. Understand the system

Infer deployment assumptions, roles, data boundaries, entry points, and security invariants from repository evidence.
Keep the model proportional to the requested scope.
A supplied diff may set priority but never replaces surrounding-flow inspection.

### 2. Inspect behavior

Trace user-controlled data, identity, authorization, state transitions, isolation, and cross-component assumptions through production paths.
Prefer concrete data and control flow over language-specific checklists.
Inspect callers and shared routes before attributing a defect to a leaf function.

### 3. Form candidates

A reportable candidate requires:

- one named security invariant;
- affected production paths or configuration;
- source-backed expected and apparent behavior;
- a plausible security consequence;
- explicit assumptions and uncertainty;
- one minimal local regression-test seam, unless reusable evidence later supports skipping regression.

Discard generic hardening advice, checklist matches without a concrete flow, and suspicions lacking production-source evidence.
Group manifestations sharing one root cause and invariant into one candidate with one codename.
Do not validate candidates in this phase.

### 4. Check known issues

When authorized read-only Jira access is available, compare every candidate with existing Bugs labelled `ai-sec-scan` in the default `RD` project or an explicitly selected destination project.
Fetch detailed ticket evidence only for semantically related summaries.
Match by root cause and security invariant, not codename or wording alone.
Make no Jira mutation.

Classify each candidate:

- `unavailable`: Jira access is unavailable or unauthorized;
- `none`: no semantically matching ticket exists;
- `covered RD-…`: existing evidence establishes the same root cause and invariant, and current source inspection reveals no change that invalidates that evidence;
- `needs-refresh RD-…`: an existing ticket is relevant, but one material assumption, affected path, or consequence needs current evidence.

Do not require new regression evidence solely because the commit, surrounding code, ticket status, or wording changed.
Require it only when a change could invalidate the existing proof or materially alter triage.
The lookup is advisory; private disclosure repeats authoritative deduplication because Jira state may change.

### 5. Account and report

Confirm the working tree is still clean.
Report only assessed surfaces, limitations, and source-backed candidates, in the session-report format.

## Candidate cards

The scan phase body is a list of candidate cards.

Number cards sequentially.
Every card repeats every section below, in this order, even when a section is short.

```markdown
---

## <n>. <codename>: <short description>

**Confidence:** high | medium
**Known issue:** unavailable | none | covered `RD-…` | needs-refresh `RD-…`

### Security invariant

<One sentence describing required behavior.>

### Source evidence

- `<path>` · `<symbol>` · <role in the relevant flow>

<Concise expected-versus-apparent source behavior.>

### Security consequence

<Concrete consequence if the source interpretation and assumptions hold.>

### Assumptions and uncertainty

<Only facts that affect whether the candidate is real.>

### Regression-test handoff

- Decision: full | focused | skip
- Reason: <why this amount of regression work remains>
- Test seam: <existing test area or smallest suitable fixture | not needed>
- Expected property: <observable invariant | not needed>
- Control: <nearby allowed or invariant-preserving case | not needed>
```

Confidence describes source evidence, not impact severity:

- `high`: production flow and invariant conflict are directly visible;
- `medium`: source flow is concrete, but a deployment or runtime assumption remains;
- `low`: do not report; keep inspecting or discard.

Regression decisions follow directly from known-issue classification:

- `unavailable` or `none` → `full`;
- `needs-refresh RD-…` → `focused` on the unresolved difference;
- `covered RD-…` → `skip`.

For `skip`, use `not needed` for test seam, expected property, and control.

## No candidates

```markdown
## Result

No source-backed security-defect candidate met the reporting gate within the assessed scope.

This does not establish that the product is secure.
```
