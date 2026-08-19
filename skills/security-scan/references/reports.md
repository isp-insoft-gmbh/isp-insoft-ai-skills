# Report schemas

## Language and visual hierarchy

Interactive session reports follow the user language.
CI reports and Jira attachments default to German.
GitHub PRs follow repository convention and fall back to German.

Prefer short scenarios, demonstrations, diagrams, pseudocode, and plain explanations over technical tours.
Prefer Mermaid unless project or active renderer context suggests another format.
Jira descriptions remain text-first because the current Jira view does not render Mermaid.

A long findings list needs two visual layers:

1. a compact index;
2. strongly separated, consistently structured vulnerability cards.

Never use color as the only grouping cue.

## Session report

Return exactly one Markdown report in the session.
Do not create a local report file.

Length limits:

- scan context, status, and coverage together: at most 300 words;
- each vulnerability card: at most 200 words, excluding diagrams;
- no code dumps, raw command transcripts, long path lists, or implementation tours.

Use this order.

### 1. Header

```markdown
# Security scan: <target>

**Scan:** complete | partial | blocked
**Publication:** not requested | complete | partial | failed
**Revision:** <repository> · <ref> · <commit>
**Working tree:** clean at start | dirty at start, publication blocked | clean at start, verification artifacts retained
```

Add one direct warning when source state, execution integrity, or publication is unreliable.

### 2. Deduplication result

One line only:

```markdown
Loaded <n> known `ai-sec-scan` false positives; <n> candidate matches examined.
```

Do not list all historical false positives.
Do not imply that loading them caused proactive revalidation.

If Jira lookup failed:

```markdown
Known Jira findings unavailable; scan is partial and publication is blocked.
```

### 3. Coverage and limitations

```markdown
## Coverage and limitations

- Examined: <product surfaces and trust boundaries>
- Partial: <surface and reason>
- Skipped: <surface and reason>
```

Name behavior surfaces and trust boundaries, not file counts.
Never use coverage percentages or claim complete security assurance.

Classify blockers without guessing:

- execution-environment gap: documented setup exists but required service/capability was unavailable;
- context gap: required deployment or security-boundary knowledge is missing;
- testability/architecture gap: the production boundary cannot be isolated or exercised safely.

Report architecture, documentation, or testability issues only when they block or condition vulnerability verification.
Do not turn them into security findings.

### 4. Vulnerability index

Omit this section when no vulnerability was verified.

```markdown
## Verified vulnerabilities

| # | Severity | Finding                                                            | Product surface   | Verification | Jira disposition |
| - | -------- | ------------------------------------------------------------------ | ----------------- | ------------ | ---------------- |
| 1 | High     | **Night Clerk:** project templates cross an authorization boundary | Project templates | direct       | new              |
```

Severity is local only:

- `Critical`
- `High`
- `Medium`
- `Low`

Judge it from demonstrated impact and realistic deployment preconditions.
Do not use CVSS or numeric scores.
Do not copy severity into Jira descriptions, fields, comments, attachments, or PRs.

Verification is:

- `direct`
- `conditional`

Jira disposition is:

- `new`
- `already tracked: RD-1234`
- `reopened: RD-1234`
- `local only`

Order findings by proposed severity.
Rediscovered open findings remain visible but do not count as new.

### 5. Vulnerability cards

Separate every card with a horizontal rule and a numbered level-two heading.
Use the same subheadings for every finding so ten or more findings remain scannable.

```markdown
---

## 1. Night Clerk: project templates cross an authorization boundary

**Severity:** High\
**Verification:** direct\
**Jira:** new

### Scenario

<Two to four plain sentences, or a compact Mermaid diagram, showing actor, boundary, and consequence.>

### Security invariant

<One sentence describing what must always remain true.>

### Demonstration

<Short expected-versus-observed account of the controlled experiment and why it proves the security consequence.>

### Assumptions and preconditions

<Only conditions that affect exploitability, deployment likelihood, or conditional status.>

### Finding limitations

<What this experiment did not establish.>

### Context gap

<Conditional findings only: missing deployment fact and likely existing documentation location needing clarification. Do not draft docs.>

### Jira action

<new ticket | already tracked with key | reopen with changed assumption | local only and why>

### Retained verification work

<none | concise statement that useful test/prototype changes remain; no path list or diff>
```

A codename is dark, funny, workplace-safe, and at most three words.
Format the heading as `<codename>: <plain vulnerability statement>`.
Understandability always outranks humor.

Do not include unverified suspicions in the default report.
Expose leads only during explicitly human-guided investigation.

### No verified findings

Use direct language:

```markdown
## Result

No vulnerability was verified within the assessed scope.

This does not establish that the repository is secure.
```

Retain coverage, limitations, Jira lookup state, and dirty-state warnings.

## Detailed Jira attachment

Create one Markdown attachment per published vulnerability.
A fresh coding agent must be able to reproduce the finding from this report without prior session context.

Do not include severity, CVSS, model identity, harness identity, generalized offensive instructions, real targets, credentials, or external user data.
Tracked repository data may be quoted when needed.

Filename:

```text
RD-1234-night-clerk-verification-a1b2c3d.md
```

Use Jira key, ASCII lowercase codename slug, `verification`, and short commit SHA.
A later regression or materially expanded finding gets another attachment with its own SHA.

Use this schema.

```markdown
# <Codename>: <plain finding>

## Source

- Jira: <key>
- Repository: <canonical URL>
- Ref: <branch or tag>
- Commit: <full SHA>
- Scanned: <timestamp>

## Security invariant

<One precise invariant.>

## Deployment context

<Typical deployment and security-boundary facts.>

### Evidence for deployment assumptions

- `<repository path>` · <heading or symbol> · <what it establishes>

### Preconditions and assumptions

- <required actor, access, configuration, state, or conditional assumption>

## Impact scenario

<Short human explanation, Mermaid diagram, or pseudocode.>

## Controlled reproduction

### Prerequisites

<Exact build/runtime prerequisites and synthetic services/data/auth.>

### Setup

<Exact steps needed from a fresh checkout at the pinned commit.>

### Run

<Exact bounded commands and retained test/prototype invocation.>

### Expected

<Invariant-preserving result.>

### Observed

<Actual result and bounded evidence.>

### Repetition

<Run counts and outcomes.>

## Controls and alternate explanations

- <negative/control case and result>
- <alternate explanation ruled out and how>

## Technical cause

### Affected source

- `<path>` · `<symbol>` · <role in the vulnerable flow>

### Flow

<Concise data/control-flow explanation plus Mermaid or pseudocode when useful.>

Do not propose a fix.

## Related Jira findings

<Duplicates, existing findings, or matching false-positive ticket and why this finding is same, different, or reopened.>

## Context gaps

<Missing fact and likely existing repository documentation location. Omit when none.>

## Post-fix verification

<Observable criteria and commands a future fixer must make pass, without prescribing implementation.>

## Artifacts and cleanup

- Initial working tree: clean
- Retained verification artifacts: <exact paths and purpose>
- Removed ephemeral artifacts: <exact bounded summary>
- Final working tree: <state>
```

A reproduction may contain exact synthetic local steps needed by a fresh agent.
Do not turn those steps into a generalized exploit guide.

After verified Jira upload, delete the temporary attachment file.
If upload fails, retain and report its exact path until repaired, then delete it.
