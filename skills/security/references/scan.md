# Scan

Review authorized first-party product code and configuration for likely security defects.
Return bounded, source-backed candidates that the regression-testing phase can confirm.

## Boundaries

- Work only within the repository, product, or subtree requested by the user.
- Do not mutate project files, Jira, GitHub, production systems, or external services.
- Limit output to source evidence, uncertainty, and local regression-test handoffs.
- Do not run dependency/CVE, secret, compliance, or generic SAST scanners unless separately requested.
- A candidate is not a confirmed finding.

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

### 1. Preflight

1. Resolve the requested repository, product, or subtree.
2. Record canonical repository identity, current ref, exact commit, and initial working-tree state.
3. Identify explicit scope limits and unavailable surfaces.

### 2. Understand the system

Infer relevant deployment assumptions, roles, data boundaries, entry points, and security invariants from repository evidence.
Keep the model proportional to the requested scope.
A supplied diff may set priority but never replaces surrounding-flow inspection.

### 3. Inspect behavior

Trace user-controlled data, identity, authorization, state transitions, isolation, and cross-component assumptions through production paths.
Prefer concrete data and control flow over language-specific checklists.
Inspect callers and shared routes before attributing a defect to a leaf function.

### 4. Form candidates

A reportable candidate requires:

- one named security invariant;
- affected production paths or configuration;
- source-backed expected and apparent behavior;
- a plausible security consequence;
- explicit assumptions and uncertainty;
- one minimal local regression-test seam.

Discard generic hardening advice, checklist matches without a concrete flow, and suspicions lacking production-source evidence.
Group manifestations sharing one root cause and invariant.

Do not validate candidates in this phase.
If runtime confirmation is needed, provide a concise handoff for the regression-testing phase.

### 5. Account and report

Confirm the working tree still matches the preflight snapshot.
Report only assessed surfaces, limitations, and source-backed candidates.

## Completion

Status is:

- `complete`: requested surfaces were reviewed to the declared depth and every candidate was resolved for handoff or discarded;
- `partial`: useful review completed, but named surfaces or evidence remained unavailable;
- `blocked`: repository state or missing context prevented meaningful review.

Completion means the bounded review finished.
It does not mean the product is secure or that candidates are confirmed defects.

## Report schema

Return exactly one concise Markdown report in the session.
Do not create a local report file.

### Header

```markdown
# Defensive security review: <target>

**Status:** complete | partial | blocked
**Revision:** <repository> · <ref> · <commit>
**Working tree:** unchanged | changed unexpectedly
```

Add one direct warning when source state or review integrity is unreliable.

### Coverage and limitations

```markdown
## Coverage and limitations

- Examined: <product surfaces and boundaries>
- Partial: <surface and reason>
- Skipped: <surface and reason>
```

Name behavior surfaces and boundaries, not file counts.
Never use coverage percentages or claim security assurance.

### Candidate index

Omit this section when no candidate met the evidence gate.

```markdown
## Candidates

| # | Candidate                                | Product surface | Confidence |
| - | ---------------------------------------- | --------------- | ---------- |
| 1 | Session rotation may preserve old access | Authentication  | medium     |
```

Confidence describes source evidence, not impact severity:

- `high`: production flow and invariant conflict are directly visible;
- `medium`: source flow is concrete, but a deployment or runtime assumption remains;
- `low`: do not report; keep inspecting or discard.

### Candidate cards

Use the same structure for every candidate:

```markdown
---

## 1. Session rotation may preserve old access

**Confidence:** medium

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

- Test seam: <existing test area or smallest suitable fixture>
- Expected property: <observable invariant>
- Control: <nearby allowed or invariant-preserving case>
```

Keep candidate reports source-focused and bounded.
Candidates remain unconfirmed until regression testing exercises the production path.

### No candidates

Use direct language:

```markdown
## Result

No source-backed security-defect candidate met the reporting gate within the assessed scope.

This does not establish that the product is secure.
```
