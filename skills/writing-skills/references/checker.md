# Checker scope

Run from the writing-skills directory:

```sh
node scripts/check.mjs /path/to/target-skill
```

The checker accepts one skill directory containing `SKILL.md`,
not a discovery root or loose Markdown skill.
It works from any cwd when the script and target paths resolve correctly.

## Checks

- Required YAML fences and a valid mapping, including duplicate-key rejection.
- String name and description, length limits, portable name syntax,
  and directory/name agreement.
- Boolean `disable-model-invocation` when supplied; unknown fields are allowed.
- Inline, image, and reference-style local links
  in regular `.md` files throughout the target.
  Resolve relative to each source document;
  ignore code examples, URLs, absolute links, and anchors.
  Check file existence, not heading anchors, raw HTML links,
  or remote availability.
- Scripted skills require `package.json`, a direct `node --test` test command,
  and at least one `.test.mjs` file.
  Recognize `.mjs`, `.cjs`, `.js`, `.ts`, `.sh`, `.bash`, `.nu`, `.py`, `.ps1`,
  `.cmd`, and `.bat` helpers anywhere in the target.
  Do not infer executable behavior for extensionless files.
- Skip `node_modules`, `.git`, and directory/file symlinks
  during recursive enumeration.
  Read the explicitly selected `SKILL.md`;
  linked targets are checked for existence, not recursively audited.

Directory/name matching and script packaging are authoring policy,
not claims about what Pi accepts.
The checker does not evaluate helper code, shell commands, coverage,
trigger quality, policy consistency, or correctness of prose.
A test declaration plus test file is not proof
that the command selects or passes those tests.
Review and run the target's actual tests separately.

## Output

JSON contains `skill`, `ok`, and `issues`
with source `path`, stable `code`, and `message`.
Exit codes: `0` means checks passed; `1` means findings;
`2` means usage or filesystem failure.
A failure is not a clean audit.
Do not copy raw findings into public artifacts
without checking for sensitive paths or URLs.
