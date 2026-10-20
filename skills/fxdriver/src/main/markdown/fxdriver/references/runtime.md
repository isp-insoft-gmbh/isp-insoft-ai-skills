# Runtime and artifact preflight

## Use the installed artifact

Resolve `fxdriver.jar` beside the active skill's `SKILL.md`; retain its absolute path as `<driver-jar>`.
Do not resolve the JAR against the app cwd or substitute a workspace checkout.
If the bundled JAR is missing, stop and report an incomplete installation.
Building or editing driver source is a separate, explicitly authorized task.
Record the actual JAR path and SHA-256; matching snapshot version strings do not prove identical binaries.
After launch/attach, compare them with `version.agentJar` and `version.agentSha256` captured by the loaded agent.
The CLI refuses readiness when the running agent cannot attest the selected JAR, including stale loaded agents and classes-directory shadowing.
Do not add a checkout's `target/classes` to the app classpath as a driver fallback.
Replacing a file does not upgrade an agent already loaded in a user JVM; request restart permission rather than killing it.

## Resolve Java before isolation

Require JDK 26+ for driver and app, plus JavaFX 26+ in the app.
Reuse the app's configured launcher, module/class paths and JVM options.
Resolve real installed Java executables before changing cwd or environment; retain absolute paths as `<driver-java>` and `<app-java>`.
They may be the same executable, but verify both when they differ.
Run each resolved executable with `-version` before launch and record its path and version.

A PATH lookup may return a tool-manager shim rather than the JDK binary.
With mise, `mise which java` in the original project environment resolves the configured installation; a generic `which java` may only locate the shim.
Otherwise inspect the project's configured JDK or `JAVA_HOME` and verify its actual `bin/java` or `bin/java.exe`.
Setting `JAVA_HOME` alone does not change what a bare `java` command invokes.
Do not silently install a toolchain or modify global configuration when resolution fails.

## Isolate app data, not the toolchain

Prefer the app's own test-data/profile options.
Preserve the harness and tool-manager environment where possible.
If app isolation requires overriding `HOME`, `USERPROFILE`, `LOCALAPPDATA`, `APPDATA` or XDG directories, freeze executable paths first and preserve unrelated tool/cache configuration.
On Windows, redirecting `LOCALAPPDATA` can make a mise shim look for tools in a new location and bootstrap installations instead of starting the app.
Never invoke Java through that shim in the isolated environment.

Use the resolved executables and JAR for launch, attach and every CLI fallback, even after cwd changes.
Preserve argument boundaries for paths containing spaces; serialize JSON-valued paths rather than assembling JSON by hand.
Keep endpoint/token, owned-process identity, provenance and temporary evidence in private OS-temp session state.
Do not log credentials or publish the endpoint token.

## Command notation

CLI snippets are argv templates, not literal shell scripts.
Replace `<driver-java>`, `<app-java>` and `<driver-jar>` with the verified absolute paths and apply the caller's shell quoting.
Replace `<temp-dir>` in command and JSON examples with the absolute path to the private OS-temp session directory.
Replace `<endpoint-json>` with the absolute path to saved launch/attach JSON in that directory.
RPC accepts this JSON file directly, avoiding manual port/token extraction.
Port-only forms require the token through the documented argument or `FXDRIVER_TOKEN`.

```text
<driver-java> -jar <driver-jar> launch --quiet -- <app-java> [existing-JVM-options] <main-or-jar> [app-args]
<driver-java> -jar <driver-jar> rpc <endpoint-json> capabilities '{}'
```

Run the supervised launch through a persistent process facility and capture its output in OS temp.
Endpoint readiness and application readiness are separate checks.
Record `version.runtime` executable/JVM/JavaFX/backend metadata, then wait for the actual application-ready predicate.
A layout pulse or successful RPC dispatch does not establish backend rendering health; inspect startup/runtime logs and actual screenshots.

## Diagnose startup correctly

If no endpoint appears, inspect process exit and captured stderr before classifying a GUI/driver timeout.
Tool-manager installation output indicates executable/environment resolution trouble, not a JavaFX action failure.
Do not retry through another checkout, bootstrap tools into the isolated profile or kill an attached user-owned app.
