# Publication contract

Load this reference only when `publish` is explicit or the user asks to publish findings from the current session.

Use available Jira and GitHub tools in the abstract.
Do not assume a CLI, MCP server, skill, authentication method, or API shape.
Before mutating, determine how the active harness performs the required operation.
After every mutation, reread the resulting object and verify its required state.

## Publication gate

Publication requires:

- `publish` authorization in context;
- a clean working tree at scan start;
- exact repository identity, ref, and commit;
- successful Jira deduplication;
- controlled reproduction, a meaningful control, and repetition;
- a grouped root cause and violated invariant;
- a prepared short Jira description and complete detailed attachment.

A `partial` scan may publish independently verified findings.
A finding may be selected by report number or codename.
Without selection, publish every eligible verified finding.

Prepare all tickets, attachments, and optional PR plans before creating external state.
Publish after investigation ends, never during candidate discovery.

## Jira target

Hardcoded target:

| Field         | Value                           |
| ------------- | ------------------------------- |
| Project       | `RD`                            |
| Issue type    | `Bug`                           |
| Labels        | `ai`, `ai-sec-scan`, `security` |
| R&D Kategorie | `Wartung und Fehlerbehebung`    |
| Rolle         | `Entwickler*in`                 |

Do not set:

- assignee;
- priority or severity;
- sprint or epic;
- initial status or resolution;
- security level.

Jira defaults and automation own assignment and initial status.
Set an existing Component only when product/component mapping is unambiguous from current Jira and repository context.
Never invent a Component or create repository labels in Jira.

## Jira fields

Publication-critical:

- project;
- issue type;
- summary;
- description;
- all mandatory labels;
- R&D Kategorie;
- Rolle;
- detailed Markdown attachment.

Best effort, with the attachment as fallback:

| Field                                  | Content                                                             |
| -------------------------------------- | ------------------------------------------------------------------- |
| Component                              | Existing unambiguous product/sub-product component                  |
| Environment                            | Canonical repository URL, ref, exact commit, synthetic setup        |
| Sicherheitsanforderungen               | Violated security invariant                                         |
| Schritte zum Reproduzieren des Fehlers | Concise controlled scenario and attachment section reference        |
| Erwartetes Verhalten                   | Concise invariant-preserving behavior                               |
| Testanweisung                          | Concise post-fix rerun instruction and attachment section reference |

Failure to set a best-effort field does not invalidate publication when the detailed attachment contains the information.

## Jira summary and description

Summary:

```text
<Dark workplace-safe codename>: <plain affected capability and unauthorized outcome>
```

Rules:

- maximum 250 characters, including codename;
- codename maximum three words;
- no severity, CVSS, file paths, root-cause mechanics, or fix language;
- clarity outranks humor.

Description:

- maximum 250 words;
- short scenario;
- demonstrated human impact;
- direct or conditional verification result;
- conditional assumptions when present;
- no severity, implementation tour, code dump, or fix proposal;
- text-first because Jira does not render Mermaid here.

Every ticket receives the detailed attachment defined in `reports.md`.
The attachment contains exact fresh-context reproduction and technical analysis.

## Ticket granularity

Create one ticket per root cause and violated security invariant.
Group multiple paths, endpoints, or manifestations when the fix and owning product boundary are the same.
Split only when fixes, components, or responsible teams materially differ.

Every verified novel finding is ticket-eligible, including findings locally ordered as `Low`.
Human triage owns priority and dismissal.

A trivial fix never bypasses the ticket or attachment.

## Existing-ticket lookup

Query every `RD` Bug labelled `ai-sec-scan`, regardless of:

- `ai` label presence;
- status;
- resolution;
- Component.

Do not create repository labels for lookup.
Use summaries and available Component metadata as a lightweight index.
Fetch description, attachments, and comments only when a candidate appears semantically related.

### Matching open finding

- Do not create another ticket.
- Report `already tracked: RD-…` locally.
- Do not add another comment or attachment for an unchanged reproduction.
- Add evidence only when affected scope, reproduction, or understanding materially changed.

### Open finding no longer reproduces

Never close or resolve it.
In `publish`, add a concise comment:

```markdown
Revalidated against: <repository/ref/commit>
Experiment: <short setup and controls>
Previously expected violation: <result>
Observed now: <result>
Conclusion: not reproduced
Remaining uncertainty: <limits>
```

Make no status, label, priority, or resolution change.

### Resolved non-false-positive regression

Reopen the original only after fresh experimental confirmation that the same vulnerability regressed.
Attach a new SHA-specific detailed report and explain the regression evidence.

### Human false positive

The `false-positive` label is authoritative.
A materially matching candidate is suppressed by default.
Do not proactively reverify every false-positive ticket.

Read the ticket body, attachment, and optional closing comments only when an independently discovered candidate collides.
Treat recorded preconditions and assumptions as human context, not deterministic matching keys.

Reopen only when:

- fresh experimental evidence satisfies the normal verification gate; and
- a material precondition, assumption, deployment fact, or implementation changed.

When reopening:

- reuse the original ticket;
- attach the new SHA-specific report;
- explain the changed assumption or precondition;
- remove `false-positive`;
- transition the ticket back to an active state through available Jira tooling.

Only humans may add `false-positive` or close a finding as false positive.

## Jira mutation failure

Prepare the detailed report before ticket creation.

If ticket creation succeeds but later field or attachment work fails:

1. never delete the ticket;
2. mark it incomplete when possible;
3. report the exact key and failed operation;
4. set publication status to `partial`;
5. stop further publication until the ticket is repaired.

If attachment upload fails, preserve its exact temporary path.
After successful repair and verified upload, delete the temporary file.

## GitHub draft PR decision

Assume company repositories use GitHub.
A PR is optional and never required for complete Jira publication.

Create one only when retained verification test or prototype code materially improves reproduction or future fixing.
If existing commands reproduce the vulnerability, ticket plus attachment is enough.

Use at most one PR per Jira ticket.
Reuse an appropriate open draft instead of creating a duplicate.
Closed and merged PRs are historical evidence and are never reopened automatically.

Every security-scan PR starts as a draft, including a trivial fix.
Human triage decides whether to keep, convert, mark ready, merge, or close it.

## Git branch and worktree

Create the Jira ticket first because the key is required in branch and PR names.

Follow established repository branch and worktree conventions.
When none exist, branch fallback is:

```text
security/RD-1234-night-clerk
```

Never alter a developer's current branch merely to publish.

- Human harness: use a separate worktree.
- Disposable CI checkout: branch directly from the scanned commit.
- Existing repo/harness worktree location: use it when unambiguous.
- No convention: use a unique OS-temporary worktree.

Record the exact temporary path and remove only that worktree after successful push/publication.
Never hardcode a developer-specific worktree directory.

A detached CI checkout may create a PR only when one source branch is unambiguous from the attached branch, user instruction, CI event/ref metadata, or harness-supplied PR metadata.
Do not infer a source branch merely because multiple remote refs contain the commit.

If a tag is encountered, do not create a PR and do not revalidate on trunk.
Jira publication may still proceed against the recorded tagged revision.

## GitHub labels

Every PR requires:

| Label         | Color     | Description                                         |
| ------------- | --------- | --------------------------------------------------- |
| `ai`          | `#8250DF` | Created or materially authored by an agent          |
| `ai-sec-scan` | `#0969DA` | Produced by the unknown-vulnerability security scan |
| `security`    | `#CF222E` | Security-relevant finding or change                 |

Create a missing label with that color and description.
Never recolor or rewrite an existing label.

## PR title and language

Follow repository language convention, falling back to German.

Demo title:

```text
RD-1234 Night Clerk: demonstrate project-template authorization bypass
```

If converted into a fix, replace `demonstrate` with `fix`.
The Jira key in the title is the Jira/GitHub integration contract.
Do not wait or poll for Jira Development synchronization.

## Demo PR description

```markdown
## <Jira key> · <Codename>: <plain finding>

**Demo only, no fix.**

### Prerequisites

<Short setup using synthetic data/auth.>

### Run

`<one primary command>`

### Expected demonstration output

<Bounded output and why its appearance proves the invariant violation.>

### Cleanup

<Exact cleanup for demo-created state.>
```

Keep deeper analysis in the Jira attachment.

A demo PR must not intentionally fail the normal CI suite.
Provide an explicit opt-in reproduction command whose successful result means the vulnerability was reproduced.
A later fixer converts the demonstration into a normal regression test.

## Trivial fix

A fix is trivial only when all are true:

- one localized change;
- no API, architecture, schema, or data migration;
- full behavioral effect is understood;
- a regression test proves the fix;
- the normal suite remains green.

The Jira attachment reproduces the vulnerable base.
The PR includes a test that fails on that base and passes with the fix.
The PR remains draft.

## Publication verification

Before reporting success, reread and verify:

### Jira

- correct project and issue type;
- summary and description;
- mandatory labels;
- R&D category and role;
- detailed attachment present;
- no forbidden priority/assignee/status mutation;
- existing-ticket action matched deduplication decision.

### GitHub, when a PR exists

- draft state;
- Jira key in title;
- correct source and target branches;
- required labels present;
- concise runnable description;
- no unintended normal-CI failure;
- current developer branch/worktree preserved.

Report final Jira keys, PR URLs, scan status, and publication status in the session report.
