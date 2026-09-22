# Private disclosure

Privately report an already proven security defect to authorized maintainers, or maintain refreshed evidence on its existing private ticket, and share the regression branch.
Ticket, SVG, and detail-report content follow [jira ticket](jira-ticket.md).

Use the available Jira and Git tooling.
Determine the active route first, then verify every mutation by rereading the resulting object.

## Required input

Resolve before publication:

- `proven` verdict, or `rejected` refresh verdict for a matching existing ticket;
- codename;
- repository identity, ref, and exact assessed commit;
- security invariant and affected product surface;
- source paths and symbols;
- candidate and control test evidence;
- assumptions and remaining limitations;
- regression branch `ai-sec-scan/<codename>` and exact evidence commit at its tip.

A new ticket or reopened ticket requires `proven`; `unknown` or missing evidence always stops without creating external state.
An explicitly authorized evidence update to a matching existing ticket may use a `rejected` refresh verdict.
That route may push the evidence branch and add one revision-specific comment, but never create, reopen, transition, or resolve a ticket, and never replace the original attachments.
The user may supply equivalent evidence without running an earlier phase.

## Laws

- Create no external state without explicit disclosure authorization.
- Report only through private destinations authorized for the affected product.
- Never publish a public advisory, public issue, or pull request; the regression branch is the only code artifact.
- Never include newly observed credentials, tokens, external user data, or production data.
- Set only the fields verified for the resolved Jira target; never invent a field.
- Never add `false-positive` and never dismiss a finding on a human's behalf.
- Verify every external mutation by rereading the resulting object.

## Publication gate

Private disclosure requires:

- explicit authorization in the current context;
- a `proven` verdict with a meaningful control, or a `rejected` refresh verdict for the bounded existing-ticket update route;
- exact repository identity, ref, assessed commit, and evidence commit;
- one grouped root cause and violated invariant;
- successful deduplication;
- every intended ticket mutation prepared before external state exists: full ticket, SVG, and detail-report templates for new or materially changed defects; exact comment text for evidence-only updates.

For a new ticket, leave only key-derived values unresolved: the Jira key, attachment filenames, the detail report's Jira field, and final attachment references in the description.
Resolve them from the created ticket's exact key before uploading attachments and finalizing the description.

## Workflow

### 1. Prepare

For a new or materially changed defect, prepare the ticket text, SVG, and detail report as complete templates before creating external state.
Use explicit `<issue-key>` and key-derived filename placeholders; leave no other content unresolved.
Write every file into an OS temp directory outside every repository, never into the working tree, and leave the files there afterwards.
Check the audience split: triage language in the ticket, technical depth in the attachment.
For an evidence-only update, prepare the exact revision-specific comment instead; create no replacement attachment.

### 2. Deduplicate

Query every Bug labelled `ai-sec-scan` in the resolved destination project, regardless of status, resolution, or component.
The destination defaults to `RD`; when the user explicitly selected another authorized private project, deduplicate in that project instead.
Use summaries as a lightweight index; fetch descriptions, attachments, and comments only for semantically related candidates.
Reuse an existing matching ticket instead of creating a duplicate.

For a matching open ticket:

- report `already tracked: <issue-key>`;
- add evidence only when affected scope, regression evidence, or understanding materially changed.

When an existing ticket's regression test now passes, never close, resolve, reopen, or transition it.
When explicitly authorized, add a concise revision-specific comment with the assessed commit, evidence commit, test, previous result, current result, and remaining uncertainty.

Reopen a resolved non-false-positive ticket only when fresh regression evidence confirms the same root cause and invariant.
The `false-positive` label is authoritative; only humans may add it or dismiss a ticket.

### 3. Share the branch

Confirm the target remote is private and authorized for the affected product before pushing; a push to a public remote is public disclosure.
Push `ai-sec-scan/<codename>`, verify the remote branch points to the exact evidence commit, and resolve its private remote URL.
Never force-push, never rewrite it, never delete it, and never touch the user's current branch.
If push or verification fails, create or update no ticket; report status `partial` and stop.

### 4. Disclose

Create or update no ticket until the private remote branch is verified.
Create or update only the authorized private ticket, and reference the private remote branch URL rather than a local branch name alone.
For an existing ticket, use its verified key to resolve every placeholder before mutation.
For a new ticket, create it from the prepared content with key-dependent attachment references omitted, then read its issue key back.
Resolve every placeholder from that exact key, rename both artifacts, upload them, and update the description with the final detail-report filename and embedded SVG; never guess or reuse a neighbouring key.
Status is not settable at creation: create the ticket, then apply the resolved destination's verified triage transition (`Klärungsbedarf`, transition `5`, for `RD`).
If a mutation partially succeeds, preserve the ticket, mark publication `partial`, report the exact failed operation, and stop.

### 5. Verify and report

Reread the ticket: project and issue type, summary and description, labels and fields, verified private remote branch URL, no forbidden field mutation, deduplication action as intended.
For a new or reopened ticket, also verify both attachments are present and prefixed with this ticket's own key, the SVG is embedded, and status matches the resolved destination's verified triage status (`Klärungsbedarf` for `RD`).
For an evidence-only update, verify the comment and preserve the ticket's prior status and attachments.

The disclosure phase uses the session-report status schema: `complete`, `partial`, or `blocked`.
Its body reports ticket key and URL, pushed branch, applicable attachment filenames or evidence comment, and deduplication outcome.

## Mutation failure

If ticket creation succeeds but later work fails:

1. never delete the ticket;
2. mark it incomplete when possible without changing forbidden fields;
3. report the exact key and the failed operation;
4. set disclosure status to `partial`;
5. stop further publication until repaired.

## Fallback: no Jira

Unavailable or unauthorized Jira is not an error.
Create no external state and keep the phase body to one line:

```markdown
No disclosure to ticket system: <Jira unavailable | Jira authorization missing>.
```

Use session-report status `complete`.
