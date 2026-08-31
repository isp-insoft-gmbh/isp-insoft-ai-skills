# Regression testing

Turn one source-backed security candidate into confirmed, rejected, or inconclusive local test evidence.
Accept the candidate from session context or an equivalent user-supplied description.

## Required input

Resolve before editing:

- repository, ref, and candidate scope;
- security invariant;
- affected production path or configuration;
- expected and apparent behavior;
- assumptions needing runtime confirmation;
- suggested test seam, when available.

If no concrete production path or invariant exists, stop with `inconclusive` instead of inventing one.

## Boundaries

- Use only authorized local source, synthetic fixtures, and project-owned test facilities.
- Exercise the real production path at the recorded revision.
- Do not remove safeguards, replace the behavior under test with a mock, or alter production code to manufacture failure.
- Keep evidence repository-specific and test-centric.
- Do not access production systems, external accounts, or unrelated services.
- Do not mutate Jira or GitHub.
- Do not hide a failing regression test with disablement, exclusion, or weakened assertions.

## Test loop

### 1. Preflight

1. Record repository identity, ref, exact commit, and initial working-tree state.
2. Inspect the affected production flow, callers, and existing tests.
3. Record the normal focused-test command and its pre-existing result when practical.

### 2. Design the smallest test

Prefer, in order:

1. extend an existing test;
2. add one case to an existing fixture;
3. add one focused test file using existing project facilities.

The test must state the security property as an observable assertion.
Add one meaningful control covering a nearby allowed or invariant-preserving case.
Use synthetic identities, state, and data.

### 3. Execute

Run the focused candidate test and control with exact command provenance.
Run the surrounding relevant test target to detect fixture or setup mistakes.
For probabilistic behavior, use bounded repeated runs and report counts.
A timeout or unavailable environment is `inconclusive`, never rejection.

### 4. Classify

- `confirmed`: the candidate case violates the invariant while the control behaves correctly.
- `rejected`: the production path preserves the invariant, and the test meaningfully exercised the candidate assumptions.
- `inconclusive`: setup, environment, assumptions, or test isolation prevented a sound verdict.

A compile failure, broken fixture, unrelated test failure, or mocked-away production path cannot confirm a defect.

### 5. Clean and account

Compare final working-tree state with preflight.
Remove only failed, intermediate, or unused artifacts created by this task.
Retain a minimal useful regression test when it captures the invariant clearly.
Report retained paths and whether the focused or surrounding suite is expectedly red.
Never touch pre-existing or unexpectedly appearing changes.

## Report

Return one concise Markdown report:

```markdown
# Security regression test: <candidate>

**Result:** confirmed | rejected | inconclusive
**Revision:** <repository> · <ref> · <commit>
**Working tree:** <initial state; final state>

## Invariant

<Required security property.>

## Test and control

- Candidate case: `<test path or test name>` · <expected and observed>
- Control: `<test path or test name>` · <expected and observed>
- Commands: `<focused command>`; `<surrounding command>`

## Verdict

<Why the evidence confirms, rejects, or cannot resolve the candidate.>

## Retained work

<none | exact retained test paths and current pass/fail state>

## Limitations

<Only unresolved assumptions or unavailable surfaces.>
```

Keep the report repository-specific, test-centric, and suitable for remediation.
A confirmed result is suitable input for private disclosure or a fix.
