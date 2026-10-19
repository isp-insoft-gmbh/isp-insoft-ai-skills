---
name: coding-guidelines
description: "Use before writing code"
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

- no unrequested abstractions: no one-impl interfaces, factories, wrappers, or config nobody sets.
- deletion over addition. boring over clever. fewest files possible.
- be lazy about the solution, never about reading: inspect the touched flow before choosing a rung.
- two stdlib/native options, same size? pick the edge-case-correct one.
- challenge unnecessary scope once, then ship the smallest safe version.
- enforce the ladder before custom code.
- half-tested is not tested. test all features before claiming tested.
- do not disable checks instead of fixing or testing.
- do not add unwanted compatibility cruft or unrequested scope.
- performance and allocation behavior matter; assess costs before replacing views with copies or adding intermediate collections. dependencies are vetted, few, and suspect by default.
- STOP SMOKING! `smoke` is a bad name -> use semantic names!
