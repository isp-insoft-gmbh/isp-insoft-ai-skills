# Actionable Start-Day Report Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use /skill:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Bürobelegung and Jira/PR output complete, actionable, deterministic, and read-only.

**Architecture:** Keep the single Node companion script. Export small pure report functions for Node tests, inject the report date, and retain `acli`/`gh`/`git` only at the existing I/O boundary.

**Tech Stack:** Node.js ESM, `node:test`, `node:assert`, Atlassian CLI, GitHub CLI

**Roadmap:** None

**Phase:** Single-plan implementation

---

### Task 1: Deterministic script execution

**Files:**

- Modify: `skills/isp-start-day/scripts/isp-start-day.mjs`
- Create: `skills/isp-start-day/scripts/isp-start-day.test.mjs`
- Modify: `skills/isp-start-day/mise.toml`

- [ ] **Step 1: Write the failing date-option test**

Import `parseDateArg` and assert `parseDateArg(['--date', '2026-07-17'])` yields local date components `2026`, `6`, and `17`; assert malformed dates throw.

- [ ] **Step 2: Run the test and verify RED**

Run: `node --test skills/isp-start-day/scripts/isp-start-day.test.mjs`

Expected: FAIL because `parseDateArg` is not exported.

- [ ] **Step 3: Add the minimal date seam**

Export `parseDateArg(args)`, parse only `--date YYYY-MM-DD`, reject invalid calendar dates, pass the resulting `Date` into `main(today)` and `parseOffice(body, accountId, today)`, and guard `main()` with direct-execution detection using `pathToFileURL`.

- [ ] **Step 4: Run the test and verify GREEN**

Run: `node --test skills/isp-start-day/scripts/isp-start-day.test.mjs`

Expected: PASS.

### Task 2: Complete Bürobelegung proposals

**Files:**

- Modify: `skills/isp-start-day/scripts/isp-start-day.mjs`
- Modify: `skills/isp-start-day/scripts/isp-start-day.test.mjs`

- [ ] **Step 1: Write the failing month-end regression test**

Build synthetic Confluence storage HTML for July 2026 with `A` on July 23–24 and blanks on July 30–31. Call exported `parseOffice(body, accountId, new Date(2026, 6, 15, 12))` and assert output includes:

- `30:_/no-color` and `31:_/no-color`
- `Future workdays missing planning: 30, 31`
- `Proposed update: 30=A (from 23), 31=A (from 24)`
- a confirmation/correction request

- [ ] **Step 2: Run the test and verify RED**

Run: `node --test skills/isp-start-day/scripts/isp-start-day.test.mjs`

Expected: FAIL because the 14-day loop omits July 30–31 and no proposal exists.

- [ ] **Step 3: Implement the complete scan and proposal**

Iterate from tomorrow through the month’s final day, skipping weekends. For each blank weekday, read the cell seven days earlier; propose its valid code or `?`. Emit one proposal line and one confirmation line. Do not call any Confluence mutation command.

- [ ] **Step 4: Run the test and verify GREEN**

Run: `node --test skills/isp-start-day/scripts/isp-start-day.test.mjs`

Expected: PASS.

### Task 3: Actionable Jira/PR review findings

**Files:**

- Modify: `skills/isp-start-day/scripts/isp-start-day.mjs`
- Modify: `skills/isp-start-day/scripts/isp-start-day.test.mjs`

- [ ] **Step 1: Write failing review-finding tests**

Export `jiraReviewFindings(tickets, prs, today)`. Test four `Ready to Sync` cases matched by RD key:

- no PR → action
- `CHANGES_REQUESTED` → action
- PR last updated before the previous workday → stale action
- PR updated during the previous workday → waiting, no action

Also assert non-`Ready to Sync` tickets do not receive review actions.

- [ ] **Step 2: Run the tests and verify RED**

Run: `node --test skills/isp-start-day/scripts/isp-start-day.test.mjs`

Expected: FAIL because `jiraReviewFindings` does not exist.

- [ ] **Step 3: Implement minimal review analysis**

Match `RD-####` from PR branch/title. Treat the previous weekday at local midnight as the activity cutoff. Return explicit action and waiting lines. Request `updatedAt` from `gh pr list`. Remove Jira status counts; print ticket lines grouped as `Action`, `Waiting for review`, `In progress`, and `To do`.

- [ ] **Step 4: Run tests and verify GREEN**

Run: `node --test skills/isp-start-day/scripts/isp-start-day.test.mjs`

Expected: PASS.

### Task 4: Contract, verification, and read-only demonstration

**Files:**

- Modify: `skills/isp-start-day/SKILL.md`
- Modify: `skills/isp-start-day/mise.toml`

- [ ] **Step 1: Update the skill contract and verify task**

Document `--date YYYY-MM-DD`, full month-end scanning, same-weekday inference plus confirmation, and `Ready to Sync` review semantics. Make `mise run //skills/isp-start-day:verify` run the Node regression test before frontmatter validation.

- [ ] **Step 2: Run focused and repository verification**

Run:

- `node --test skills/isp-start-day/scripts/isp-start-day.test.mjs`
- `mise run //skills/isp-start-day:verify`
- `biome check skills/isp-start-day/scripts`
- `dprint check skills/isp-start-day/SKILL.md docs/super/specs/2026-07-15-start-day-actionable-report-design.md docs/super/plans/2026-07-15-actionable-start-day-report.md`

Expected: all commands pass.

- [ ] **Step 3: Run the requested read-only demonstration**

Run: `node skills/isp-start-day/scripts/isp-start-day.mjs --date 2026-07-17`

Expected: report date is July 17, live Confluence is only read, missing month-end days receive proposals, and Jira review findings use associated PR state/activity.

- [ ] **Step 4: Build and install the updated pi skill**

Run:

- `mise run //skills/isp-start-day:build`
- `mise run install -- isp-start-day --harness pi`

Expected: the pi harness reports `isp-start-day` installed/current.

- [ ] **Step 5: Commit the implementation**

Stage only the plan, skill source, test, script, and mise task. Commit with subject `fix: make start-day report actionable`.
