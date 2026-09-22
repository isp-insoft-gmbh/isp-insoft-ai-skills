# Jira ticket and detail report

Two artifacts, two audiences, one codename.

| Artifact      | Audience                        | Depth                                                        |
| ------------- | ------------------------------- | ------------------------------------------------------------ |
| Ticket        | humans doing triage             | what breaks, who can abuse it, why it matters — nothing else |
| SVG diagram   | humans doing triage             | one picture of the broken flow                               |
| Detail report | agents and the fixing developer | full technical evidence, as deep as needed                   |

Never write triage text into the detail report, and never leak detail-report depth into the ticket.

## Jira target

Verified against project `RD` (`isp-insoft.atlassian.net`, 2026-09-18):

| Field         | Value                                             | Field ID            |
| ------------- | ------------------------------------------------- | ------------------- |
| Project       | `RD`                                              | —                   |
| Issue type    | `Bug`                                             | `10018`             |
| Labels        | `ai`, `ai-sec-scan`, `security`                   | —                   |
| Priority      | `High`, `Medium`, or `Low`                        | —                   |
| R&D Kategorie | `Wartung und Fehlerbehebung`                      | `customfield_10052` |
| Rolle         | `Entwickler*in`                                   | `customfield_10053` |
| Status        | `Klärungsbedarf` (transition `5`, after creation) | —                   |

Set these labels; leave any other label the user or triage added in place, and never remove labels.
Priority carries triage urgency only: `RD` has no severity field, so never state a severity, score, or CVSS value in any text.
Set no other field: no assignee, sprint, resolution, or security level.
Set an existing component only when product mapping is unambiguous; never invent one.
Use another destination only when the user explicitly identifies an authorized private project and its issue type, fields, values, and transition have been verified.
Treat that project as the resolved destination for deduplication and disclosure.

## Ticket language and register

German, matching the existing `ai-sec-scan` tickets.
Leave identifiers, paths, symbols, commands, branch names, and commit hashes unchanged.

Write for a colleague who triages, not for an engineer who fixes:

- plain description of the broken behavior and the attack path, in two or three sentences;
- KKG domain terms are expected and welcome: Bescheid, Grundstück, Mandant, Benutzer, Projekt;
- no class names, no method names, no stack traces, no framework jargon, no caching or interceptor mechanics;
- no fix proposal, no remediation design, no code.

Say who can do what to whom, and what a municipality would notice.
When no domain term fits, name the capability, never the symbol:

| ✗ Symbol language                                         | ✓ Capability language                                           |
| --------------------------------------------------------- | --------------------------------------------------------------- |
| `keepAlive authentifiziert gelöschten Benutzer weiter`    | `Gelöschte Benutzer: Anmeldung bleibt gültig`                   |
| `AuthentificationInterceptor liest Session aus dem Cache` | `Zugriff auf geschützte Funktionen nach dem Löschen des Kontos` |
| `BescheidService prüft Mandant-ID nicht`                  | `Bescheide fremder Mandanten sind einsehbar`                    |

Symbols, classes, and methods belong in the detail report only.

## Summary

```text
<codename>: <betroffene Fähigkeit und verletzte Grenze>
```

`<codename>` is the codename verbatim, unchanged and untranslated; the rest of the summary is German.
Maximum 250 characters.
No severity, score, file path, root-cause mechanics, or fix language.
Clarity outranks humor.

## Description

Maximum 250 words, these headings, in this order:

```text
## Problem
## Sicherheitsinvariante
## Angriffsweg
## Nachweis
## Einschränkung
```

- `Problem`: the current broken behavior in plain German.
- `Sicherheitsinvariante`: the one rule that must hold.
- `Angriffsweg`: embed the SVG; one or two sentences on how the defect is exploited — who, from where, gains what.
  Never add a `Soll-Zustand` section: the fix is the fixing developer's decision, not the report's.
- `Nachweis`: that the defect was assessed at `<assessed-short-sha>` and is proven by a regression test on the linked private remote branch `ai-sec-scan/<codename>` at `<evidence-short-sha>`, plus the attached detail report by name. Never reference a local-only branch.
- `Einschränkung`: what remains unverified.

## SVG diagram

One diagram per ticket, attached and embedded in `Angriffsweg`.

Requirements, not tooling:

- filename `<issue-key>-<codename>-flow.svg`, using the key of this ticket as read back after creation;
- German `<title>` and `<desc>` for accessibility;
- shows the current broken flow, with the violated step visually marked;
- small and self-contained: no external fonts, scripts, or network references;
- readable without color alone.

Produce it however the environment allows.

## Detail report

One Markdown attachment per new or materially changed defect, consumed mostly by agents.
Technical depth is wanted here; audience-friendliness is not.

Filename:

```text
<issue-key>-<codename>-regression-<evidence-short-sha>.md
```

Schema:

```markdown
# <codename>: <plain finding>

## Source

- Jira: <key>
- Repository: <canonical URL>
- Ref: <branch or tag>
- Assessed commit: <full SHA>
- Evidence commit: <full SHA>
- Regression branch: <private remote branch URL> (`ai-sec-scan/<codename>` at the evidence commit)
- Assessed: <timestamp>

## Security invariant

<One precise invariant.>

## Affected behavior

<Expected versus observed, through the real production path, with human impact.>

## Source evidence

- `<path>` · `<symbol>` · <role in the affected flow>

## Regression evidence

- Candidate test: `<path or test name>` · <expected and observed>
- Control: `<path or test name>` · <expected and observed>
- Pre-change command: `<command>` · <result>
- Focused command: `<command>` · <result>
- Surrounding suite: `<command>` · <result>

## Assumptions and limitations

<Only facts affecting validity or deployment relevance.>

## Remediation acceptance

<Observable behavior and test results required after a fix.>
```

Exclude credentials, tokens, production data, real targets, generalized offensive procedures, and unrelated repository details.
Write both attachments into an OS temp directory outside every repository and leave them there; report their exact paths, whether upload succeeded or failed.
