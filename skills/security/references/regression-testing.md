# Regression testing

Turn a source-backed security candidate into `proven`, `rejected`, or `unknown` local test evidence.
Accept candidates from session context or an equivalent user-supplied description.

## Required input

Resolve before editing, per candidate:

- repository, ref, and candidate scope;
- codename;
- security invariant;
- affected production path or configuration;
- expected and apparent behavior;
- assumptions needing runtime confirmation;
- suggested test seam, when available.

If no concrete production path or invariant exists, stop with `unknown`.

## Boundaries

- Use only authorized local source, synthetic fixtures, and project-owned test facilities.
- Exercise the real production path at the recorded commit.
- Do not remove safeguards, replace the behavior under test with a mock, or alter production code to manufacture failure.
- Do not access production systems, external accounts, or unrelated services.
- Do not push, mutate Jira, or mutate GitHub; this phase stays local.
- Do not hide a failing regression test with disablement, exclusion, or weakened assertions.

## Isolation

Every candidate gets its own worktree and its own branch, never a shared branch or working directory.

Resolve `ai-sec-scan/<codename>` before creating the worktree:

1. Fetch the exact candidate branch from an authorized private remote when available; fetching is read-only.
2. Reuse the local branch, create it from the remote-tracking branch, or create it from the recorded commit, in that order.
3. Compare local and remote-tracking tips when both exist:
   - equal or local ahead: continue;
   - local behind: fast-forward it to the remote-tracking tip before editing;
   - diverged: stop with `unknown`; never choose or rewrite history implicitly.

Attach an existing branch with `git worktree add <temp-dir>/<codename> ai-sec-scan/<codename>`.
Create a new branch with `git worktree add <temp-dir>/<codename> -b ai-sec-scan/<codename> <commit>`.

For refreshed evidence, merge the recorded assessed commit into the existing candidate branch before changing tests.
Preserve previous evidence and add new commits only: never reset, rebase, recreate, or force-update the branch.
If the merge conflicts, abort it and report `unknown` with the conflict paths.
Before testing, compare the worktree with the assessed commit.
Only reviewed regression-test and test-fixture paths may differ; any production or configuration difference means this worktree does not represent the assessed revision → stop with `unknown`.

Never check the candidate branch out in the user's working directory, and never move the user's `HEAD`, branch, index, or stash.

Work candidates sequentially by default, one worktree at a time.
Run them in parallel only when subagent capability is available; each subagent then receives one candidate card, the repository context, the test seam, and its own worktree path.
Collect the verdicts, then report them together.
Leave every branch in place; disclosure references it.
Remove only the temporary worktree directories, never the branches.

Record the user's git log style and language once, and write every commit message in that style and language.

## Test loop

### 1. Preflight

Inspect the affected production flow, its callers, and existing tests.
Record the normal focused-test command and its pre-existing result when practical.

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
A timeout or unavailable environment is `unknown`, never `rejected`.

### 4. Classify

- `proven`: the candidate case violates the invariant while the control behaves correctly;
- `rejected`: the production path preserves the invariant, and the test meaningfully exercised the candidate assumptions;
- `unknown`: setup, environment, assumptions, or test isolation prevented a sound verdict.

A compile failure, broken fixture, unrelated test failure, or mocked-away production path cannot prove a defect.

### 5. Commit

Commit the regression test and its control to the candidate branch, inside its worktree.
Commit `rejected` and `unknown` work too, so the evidence stays reviewable.
Leave the branch unpushed and undeleted, and leave the user's original checkout untouched.

## Report

The regression phase body is one verdict block per candidate, in the session-report format, each preceded by a `---` rule.
`Revision` pins the assessed base commit; the created branch belongs on the `Branch` line only.
Spell the outcome of each test out; never abbreviate it to a bare pass/fail marker.

```markdown
---

## <codename>

**Result:** proven | rejected | unknown
**Branch:** `ai-sec-scan/<codename>` · `<evidence-full-sha>` · local, unpushed · <test paths>

<Required security property.>

- Candidate `<test name>`: expected <required behavior>, observed <actual behavior> → invariant violated | invariant held
- Control `<test name>`: expected <allowed behavior>, observed <actual behavior> → behaves correctly | broken
- Commands: `<focused command>`; `<surrounding command>`

<Why the evidence proves, rejects, or cannot resolve the candidate.>
```
