# Private disclosure

Privately report an already confirmed security defect to authorized maintainers.
External mutation must be explicit in the current context.

Use available Jira and GitHub tools abstractly.
Do not assume a CLI, MCP server, authentication method, or API shape.
Determine the active harness route before mutating, then verify each mutation by rereading it.

## Required input

Resolve before publication:

- confirmed regression-test verdict;
- repository identity, ref, and exact commit;
- security invariant and affected product surface;
- source paths and symbols;
- candidate and control test evidence;
- assumptions and remaining limitations;
- retained regression-test paths, when any.

If confirmation evidence is missing or inconclusive, stop without creating external state.
The user may supply equivalent evidence without running another phase.

## Laws

- Create no Jira or GitHub state without explicit disclosure authorization.
- Report only through private destinations authorized for the affected product.
- Never publish a public advisory, public issue, standalone demonstration, or demonstration-only PR.
- Never include newly observed credentials, tokens, external user data, or production data.
- Do not set Jira severity, priority, assignee, sprint, status, resolution, or security level.
- Never add `false-positive` or dismiss a finding on a human's behalf.
- Verify every external mutation by rereading the resulting object.
- An optional PR contains a fix and regression test, never only evidence of the defect.

## Publication gate

Private disclosure requires:

- explicit authorization in current context;
- confirmed regression-test evidence with a meaningful control;
- exact repository identity, ref, and commit;
- grouped root cause and violated invariant;
- reliable working-tree accounting;
- successful Jira deduplication;
- prepared ticket description and private attachment.

Prepare every ticket, attachment, and optional fix plan before creating external state.

## Workflow

### 1. Preflight

1. Record the current working-tree state.
2. Separate known retained regression-test changes from unrelated changes.
3. Resolve the authorized private Jira project and repository destination.
4. Block external mutation when unrelated or unexpected changes make the evidence unreliable.

### 2. Prepare

Prepare the complete private report before creating external state.
Keep it fix-oriented and repository-specific:

- violated invariant;
- affected behavior and source;
- human impact;
- regression-test and control results;
- assumptions and limitations;
- observable remediation criteria.

Do not include a standalone operational procedure or generalized offensive guidance.

### 3. Deduplicate

Query every `RD` Bug labelled `ai-sec-scan`, regardless of status, resolution, Component, or presence of the `ai` label.
Use summaries and available Component metadata as a lightweight index.
Fetch descriptions, attachments, and comments only for semantically related candidates.
Group manifestations sharing one root cause and invariant.
Reuse an existing matching ticket instead of creating a duplicate.

For a matching open ticket:

- report `already tracked: RD-…`;
- add evidence only when affected scope, regression evidence, or understanding materially changed.

When an open ticket's regression test now passes, never close or resolve it.
When explicitly authorized, add a concise revision-specific comment with the test, previous result, current result, and remaining uncertainty.

Reopen a resolved non-false-positive ticket only when fresh regression evidence confirms the same root cause and invariant.
The `false-positive` label is authoritative; only humans may add it or dismiss a ticket.

### 4. Disclose privately

Create or update only the authorized private ticket and required attachment.
If a mutation partially succeeds, preserve the ticket, mark publication `partial`, report the exact failed operation, and stop further publication.

### 5. Optional draft fix PR

Create a PR only when the user separately authorized a fix and the completed change satisfies all gates:

- minimal understood production change;
- regression test fails on the affected base and passes with the fix;
- focused and normal relevant suites pass;
- no unrelated changes;
- private Jira ticket already exists;
- PR starts as draft.

Reuse an appropriate open draft instead of creating a duplicate.
Do not alter the developer's current branch; use established branch/worktree conventions.

### 6. Verify and report

Reread Jira and GitHub state.
Report two statuses:

- disclosure: `complete`, `partial`, or `failed`;
- fix PR: `not requested`, `complete`, `partial`, or `failed`.

Return ticket keys, private URLs, optional draft PR URL, and any retained local paths.

## Jira target

Default company target:

| Field         | Value                           |
| ------------- | ------------------------------- |
| Project       | `RD`                            |
| Issue type    | `Bug`                           |
| Labels        | `ai`, `ai-sec-scan`, `security` |
| R&D Kategorie | `Wartung und Fehlerbehebung`    |
| Rolle         | `Entwickler*in`                 |

Use another destination only when the user explicitly identifies an authorized private project.
Set an existing Component only when product mapping is unambiguous.
Never invent a Component.

## Jira summary and description

Summary:

```text
<Short codename>: <plain affected capability and boundary failure>
```

Rules:

- maximum 250 characters;
- codename maximum three words;
- no severity, score, file path, root-cause mechanics, or fix language;
- clarity outranks humor.

Description, maximum 250 words:

- concise affected behavior;
- violated invariant;
- demonstrated human impact;
- confirmed regression-test result;
- assumptions and limitations;
- attachment reference;
- no severity, code dump, standalone operational procedure, or fix proposal.

## Private attachment

Create one Markdown attachment per new or materially changed defect.
Use only repository-specific engineering evidence needed for remediation.

Filename:

```text
RD-1234-<codename>-regression-<short-sha>.md
```

Schema:

```markdown
# <Codename>: <plain finding>

## Source

- Jira: <key>
- Repository: <canonical URL>
- Ref: <branch or tag>
- Commit: <full SHA>
- Assessed: <timestamp>

## Security invariant

<One precise invariant.>

## Affected behavior

<Concise expected-versus-observed account and human impact.>

## Source evidence

- `<path>` · `<symbol>` · <role in the affected flow>

## Regression evidence

- Candidate test: `<path or test name>` · <expected and observed>
- Control: `<path or test name>` · <expected and observed>
- Focused command: `<repository-local test command>`
- Surrounding suite: `<command and result>`

## Assumptions and limitations

<Only facts affecting validity or deployment relevance.>

## Remediation acceptance

<Observable behavior and test results required after a fix.>

## Artifacts and working tree

- Initial state: <state>
- Retained regression work: <exact paths or none>
- Final state: <state>
```

Do not include real targets, credentials, production data, generalized procedures, or unrelated repository details.
Delete the temporary attachment after verified upload.
If upload fails, retain and report its exact path until repaired, then delete it.

## Jira mutation failure

If ticket creation succeeds but later work fails:

1. never delete the ticket;
2. mark it incomplete when possible without changing forbidden fields;
3. report the exact key and failed operation;
4. set disclosure status to `partial`;
5. stop further publication until repaired.

## Draft fix PR

A PR requires separate explicit fix authorization.
Use at most one PR per Jira ticket and reuse an appropriate open draft.
Every PR starts as draft.

Create the Jira ticket first because its key is required in branch and PR names.
Follow repository branch and worktree conventions.
When none exist, use:

```text
security/RD-1234-<codename>
```

Never alter a developer's current branch merely to publish.
A tag may be disclosed to Jira but never used to create a fix PR or silently revalidated on trunk.

Required PR labels:

| Label         | Color     | Description                                        |
| ------------- | --------- | -------------------------------------------------- |
| `ai`          | `#8250DF` | Created or materially authored by an agent         |
| `ai-sec-scan` | `#0969DA` | Produced by the defensive security review workflow |
| `security`    | `#CF222E` | Security-relevant finding or change                |

Create a missing label with that definition.
Never modify an existing label.

PR title:

```text
RD-1234 <Codename>: fix <plain affected behavior>
```

PR description:

```markdown
## RD-1234 · <Codename>: <plain finding>

### Change

<Concise fix behavior.>

### Regression test

- Before: <test and observed failure>
- After: <test and observed success>
- Control: <result>

### Validation

- `<focused command>`
- `<normal relevant suite>`
```

The regression test must fail on the affected base and pass with the fix.
The normal relevant suite must pass.
No demonstration-only PR is allowed.

## Final verification

Before reporting success, reread and verify:

### Jira

- correct private project and issue type;
- summary and description;
- mandatory labels and fields;
- private attachment present;
- no forbidden field mutation;
- existing-ticket action matched deduplication.

### GitHub, when a fix PR exists

- draft state;
- Jira key in title;
- correct source and target branches;
- required labels present;
- regression and normal suites pass;
- no unrelated changes;
- developer branch/worktree preserved.
