---
name: fxdriver-instructions
description: "Clarify JavaFX workflow acceptance, data safety, source-edit scope and evidence requirements before a non-trivial fxdriver run."
license: MIT
compatibility: "Pair with fxdriver on JDK 26+ and JavaFX 26+; workflow requirements only, not protocol syntax."
---

# Clarify the workflow

Inspect project docs/config first; require JDK 26+ and JavaFX 26+.
Infer safe defaults; ask only for decisions affecting correctness, authorization, data safety or evidence.
Older runtimes are outside scope; do not design compatibility workarounds.
Verify the installed artifact and real Java executables through [runtime preflight](../fxdriver/references/runtime.md) before data-isolation overrides.
Discover paths from project/toolchain configuration; do not ask the user for facts that can be verified.

## Resolve consequential gaps

- Entrypoint, app cwd, required environment and readiness signal.
- Fresh/test-owned data vs existing data; permission before destructive reset.
- Credentials and first-login behavior; never expose secrets in logs/artifacts.
- Fixture generation vs manual input, exact counts and persistence checks.
- Required screens, final app state, screenshots vs mandatory video, artifact location.
- Authorization for app-source edits, debug fixtures, instrumentation or restarts.
- Cleanup ownership: close only launched test instances; preserve user work.

Default test-owned runs to headless unless the workflow requires visible desktop interaction.
Use the fxdriver skill for runtime flags, capabilities and the observe/act/verify loop.
Missing IDs/accessibility are normal, not a prerequisite for driving.
Within authorized source scope, improve actual controls and label associations; rebuild and runtime-verify.
Otherwise use safe discovery and ask before editing.
Accessibility metadata supports driving; general accessibility audits and screen-reader testing are separate work.
Use standard shell/HTTP facilities, not a custom harness extension.

Prefer one grouped question when a human decision is unavoidable.
State safe assumptions briefly, then execute.
Keep temporary session state and experimental artifacts in OS temp.
Check capabilities before promising video or modal-safe dispatch.
Agree an alternative when required evidence is unsupported; never silently claim completion.
