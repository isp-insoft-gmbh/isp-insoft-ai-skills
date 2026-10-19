# Authoring edge cases

Read only when the task touches the edge case.

- **Portability:** Pi accepts some forms the Agent Skills standard rejects.
  Keep the portable contract: lowercase `name`, directory/name agreement, non-empty `description`, valid YAML frontmatter.
- **Invocation:** `disable-model-invocation` is a boolean.
  Do not add a dispatcher prompt when current Pi invocation already supplies the behavior.
- **Disclosure:** link conditional references directly from `SKILL.md`; avoid chains of references.
  Give long reference files a contents section so partial reads expose their scope.
  Short `SKILL.md` files need no contents section; partial-read risks are not a Pi 100-line limit.
- **Links:** resolve local Markdown links from their source file.
  Ignore URLs, anchors, absolute paths, and examples inside code blocks.
  Check target existence; do not promise remote availability or heading validity.
- **Scripts:** any recognized helper extension anywhere in the skill makes the package scripted under the local checker policy.
  Extensionless files do not.
  A declaration of `node --test` is packaging evidence, not proof that tests run.
- **Helpers:** distinguish “run this script” from “read this script”; declare prerequisites, failure handling, and expected output.
- **Examples:** include a concrete input/output example when prose leaves an observed ambiguity.
- **Runtime boundary:** accepted frontmatter does not prove implemented behavior.
  Do not assume Claude Code's `context: fork`, model/effort overrides, hooks, tool permission grants, argument substitution, or shell interpolation work in Pi.
  Require installed Pi documentation or implementation evidence for runtime claims.
- **Fixtures:** use OS temp directories; do not execute fixture helpers.
  Spawn subprocesses with `process.execPath` and argument arrays, never shell strings.
- **Scope:** checker tests prove structural behavior only.
  Trigger quality, prose effectiveness, source authority, and semantic correctness need review or a controlled scenario.
- **Research:** keep downloaded material and generated evidence outside the repository unless explicitly requested.
