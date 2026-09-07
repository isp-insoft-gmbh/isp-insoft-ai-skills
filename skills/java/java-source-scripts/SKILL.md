---
name: java-source-scripts
description: "Write or review Java source-file scripts run with `java path/to/script.java`. This is Java, not JavaScript; do not use for JS/TS/browser/node scripting."
metadata:
  sources:
    - "https://openjdk.org/jeps/330"
    - "https://openjdk.org/jeps/458"
    - "https://openjdk.org/jeps/512"
---

# Java Source Scripts

Use this for **Java source-file scripts**: small operational `.java` files invoked directly with `java script.java`.
Do **not** use this for JavaScript.

## Default stance

- Script invocation is `java path/to/script.java [args...]`.
- Prefer a `.java` extension and explicit `java ...` invocation for cross-platform use.
- Shebang is allowed for Unix convenience, but never required; Windows does not support it.
- Prefer standard library only. No Maven/Gradle/JBang/Picocli unless the task explicitly needs them.
- Prefer one pragmatic file over a package/module/build setup.
- Use modern Java features and APIs. Terse is good. Boilerplate is not.
- For repo-managed scripts, ensure the script is committed before testing from a fresh clone/container.

## Source-file launcher facts

- JDK 11+ runs a single `.java` file directly (`java Tool.java`).
- Arguments after the source file go to the program.
- The first top-level class, or an instance `void main(String[] args)` / `void main()` on modern Java, is the entry point.
- JDK 25+ supports compact source files for small programs without an explicit class declaration.
- JDK 22+ can resolve referenced sibling `.java` files automatically for multi-file source programs.
- Shebang launch only applies to single-file scripts; for portability still document `java script.java`.
- Source-file mode triggers only when the source file exists; otherwise `java` may treat the argument as a class name.
- If a container image only has a JRE, `java script.java` may work while `javac` does not. Install a JDK when compiling ahead of time.

## Shape

Prefer this structure:

```java
import java.nio.file.*;
import java.time.*;
import java.util.*;

/// Does one operational thing.
///
/// Usage:
/// `java scripts/example.java [--flag] <arg>`
void main( String[] args ) throws Exception
{
  final var opts = parseArgs( args );
  if ( opts.help() ) { help(); return; }

  // linear flow
}

Opts parseArgs( String[] args )
{
  var help = false;
  for ( var arg : args )
  {
    switch ( arg )
    {
      case "-h", "--help" -> help = true;
      default -> throw new IllegalArgumentException( "unknown arg: " + arg );
    }
  }
  return new Opts( help );
}

void help()
{
  IO.println( "Usage: java scripts/example.java [--help]" );
}

record Opts( boolean help ) {}
```

## Style

- Prefer `void main(...)` over `public class Main { public static void main... }` when the target Java supports it.
- Prefer `final var` for obvious local values.
- Prefer `record` for small immutable option/result/config carriers.
- Prefer early returns over nested control flow.
- Prefer `Path`, `Files`, `ProcessBuilder`, `HttpClient`, `URI`, `Instant`, `Duration`, `Pattern`, `System.getenv` over shelling out.
- Prefer `Path.of(...)` for new code; use `Paths.get(...)` only when matching older local style.
- Prefer command arrays (`new ProcessBuilder(cmd)`) over shell strings.
- Prefer loops when clearer; do not force streams.
- Keep helpers small and named by behavior.
- Keep constants in one place when repeated.

## CLI parsing

- For a few flags, write a simple `switch` over `args`.
- For 1–2 values, do a linear scan.
- Do not create generic parser frameworks.
- `--help` must be short and direct.

## Processes

Centralize external commands. Do not scatter `new ProcessBuilder(...)`.

Use helper names that state failure/output behavior:

- `run(...)` — fail-fast, inherited IO.
- `runIgnoreFail(...)` — non-zero exit is acceptable.
- `runCapture(...)` — capture stdout/stderr and fail on non-zero.
- `runCaptureIgnoreFail(...)` — capture result and exit code.

Keep OS special cases local to the call site, e.g. `mvnw.cmd` on Windows.

Process helper baseline:

```java
CommandResult runCaptureIgnoreFail( String... cmd ) throws Exception
{
  final var p = new ProcessBuilder( cmd ).redirectErrorStream( true ).start();
  final var output = new String( p.getInputStream().readAllBytes() );
  return new CommandResult( p.waitFor(), output );
}

record CommandResult( int exitCode, String output ) {}
```

Use `inheritIO()` for interactive commands; use capture helpers only when the script needs output.

## Output and errors

- Console output is the UI. `IO.println`/`IO.print` are fine.
- Use semantic ANSI constants if coloring: `STEP`, `SUCCESS`, `HINT`, `ERROR`, `RESET_STYLE`.
- Fail fast on required errors with direct messages.
- If ignoring errors, make it explicit in helper names or comments.
- Validate required env vars early. Defaults must be explicit.
- Never print secret env values; print only whether they are set or which variable is missing.

## Documentation

Put a short header comment in the source:

- purpose
- usage
- options
- environment variables
- important side effects

Keep docs matched to implementation.

## Avoid

- JavaScript/Node assumptions.
- Bash/POSIX-only logic when Java stdlib works.
- Build tools for simple scripts.
- Depending on Bash/POSIX tools for bootstrapping/setup/maintenance when Java stdlib can do it.
- `public static void main` boilerplate unless compatibility demands it.
- Mutable tiny config classes; use records.
- Broad abstractions for cleanliness alone.
- Hidden global side effects.

## References

- [JEP 330](https://openjdk.org/jeps/330): single-file source-code programs.
- [JEP 458](https://openjdk.org/jeps/458): multi-file source-code programs.
- [JEP 512](https://openjdk.org/jeps/512): compact source files and instance main methods.
- `examples/jep-512.md`: compact-source examples.
- Inside Java “Scripting with Java”.
