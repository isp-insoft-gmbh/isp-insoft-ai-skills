# Actionable start-day report

## Problem

The checker contradicts its skill contract:

- Bürobelegung checks only the next 14 calendar days instead of every remaining workday in the month.
- Missing planning days are listed without proposed values or a confirmation request.
- Jira status counts obscure ticket-level actions.
- `Ready to Sync` is incorrectly presented as actionable although it means waiting for code review.

## Design

### Bürobelegung

Check every weekday after today through the final day of the current month.

For each blank day, propose the value from the same weekday one week earlier. If that day is unavailable or blank, show `?`. Present all proposals and ask the user to confirm or correct each value. The checker remains read-only and never changes Confluence.

Example for 2026-07-17, when July 23 and 24 are `A` and July 30 and 31 are blank:

- `30=A` inferred from `23=A`
- `31=A` inferred from `24=A`
- Ask for confirmation or corrected `B`, `BP`, `H`, `A`, or `AP` values.

A date CLI option permits deterministic read-only runs such as `--date 2026-07-17`.

### Jira and pull requests

Match sprint tickets and open KKG pull requests using `RD-####` in the PR branch or title.

Treat `Ready to Sync` as waiting for code review. Do not recommend user action unless:

- no associated open PR exists,
- the PR has requested changes, or
- the PR has no activity for more than one workday.

Show ticket-level findings and concrete next actions. Remove the status-count summary. Preserve the complete ticket list only when it adds context after findings.

One workday means the previous weekday; weekends do not consume the threshold.

## Data flow

1. Read the Confluence page and current sprint through `acli`.
2. Read open PR metadata through `gh` without mutation.
3. Parse office entries for the requested date.
4. Derive month-end gaps and same-weekday proposals.
5. Associate Jira tickets and PRs by RD key.
6. Render findings and next actions.

## Failure behavior

Missing Confluence, Jira, GitHub, account, or repository data remains explicit. Never claim completeness when the checked range or source is unavailable. Never mutate remote or local state.

## Tests

Add Node regression tests proving:

- a July 15 run includes July 30 and 31,
- missing days receive previous-same-weekday proposals,
- `Ready to Sync` without a PR is flagged,
- requested changes are flagged,
- one-workday stale review is flagged,
- actively reviewed `Ready to Sync` work is not actionable,
- `--date 2026-07-17` controls report date without writes.
