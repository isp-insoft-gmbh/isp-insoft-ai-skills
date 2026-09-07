---
name: coding-guidelines
description: "Use before designing, implementing, debugging, testing, or reviewing code."
---

# Coding guidelines

Ladder:

1. Do not build what was not asked.
2. Reuse existing code in this repo.
3. Prefer stdlib.
4. Prefer native platform APIs.
5. Prefer installed deps over new deps.
6. Prefer one-line/data-only changes.
7. Write minimum custom code.

Root-cause bug-fix rule: inspect callers and fix the shared route when possible.

Rules:

- No unrequested abstractions: no one-impl interfaces, factories, wrappers, or config nobody sets.
- Deletion over addition. Boring over clever. Fewest files possible.
- Be lazy about the solution, never about reading: inspect the touched flow before choosing a rung.
- Two stdlib/native options, same size? Pick the edge-case-correct one.
- Challenge unnecessary scope once, then ship the smallest safe version.
- Enforce the ladder before custom code.
- half-tested is not tested. Test all features before claiming tested.
- do not disable checks instead of fixing or testing.
- do not add unwanted compatibility cruft or unrequested scope.
- Performance matters; dependencies are vetted, few, and suspect by default.
- STOP SMOKING! `smoke` is a bad name -> use semantic names!

Heuristics (decide like this):

- Internalizability beats incumbency and even acknowledged theoretical superiority; gaps get owned via self-built mitigation.
- Complexity is fine when examined: internalized / being internalized / behind a legitimate boundary / mess. Only mess is rejected.
- Minimal self-controlled tools beat integrated products.
- Spec before implementation. The spec is the verbalization step; clarity precedes words.
- Strictness where the machine writes, flexibility where humans extend.
- Arrive with the fix designed; leave authority with the owner.

Safety floor: never remove trust-boundary validation, data-loss handling, security, accessibility, hardware calibration,
or explicitly requested behavior.

Test floor: non-trivial logic leaves one small runnable check; trivial one-liners need no test.
