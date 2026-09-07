# Mise Examples

Minimal shape; choose real tools/versions from local help/docs.
Inspect config effects and trust before loading it; ask before trusting real config.

```toml
[env]
EXAMPLE_ENV = "value"
_.path = "relative/bin"

[tasks.check]
description = "Run checks"
run = "echo check-ok"
```

Structure checks after config inspection:

```sh
mise tasks ls
mise tasks info check
mise tasks validate
```

Execution preview: `mise run --dry-run check`.
Review config evaluation and possible sensitive output first; dry-run is not a sandbox.
`--skip-tools --no-deps` alone still executes the task.
Inspect only needed env variables, redacting values before output; never dump `mise env --json`.

Local-only values belong in local config:

```toml
[env]
PRIVATE_OR_MACHINE_LOCAL_VALUE = "local value"
```
