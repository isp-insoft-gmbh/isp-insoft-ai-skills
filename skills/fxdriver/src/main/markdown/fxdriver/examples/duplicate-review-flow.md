# Duplicate review example

Complete [runtime preflight](../references/runtime.md); use the installed JAR and resolved real Java executables.

Use isolated data; reviewing duplicates is not permission to delete them.
Discover actual names, methods and IDs before using this app-specific plan.

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "run",
  "params": {
    "steps": [
      { "op": "click", "nodeId": "find-duplicates" },
      {
        "op": "wait",
        "nodeId": "filter-duplicates",
        "enabled": true,
        "timeoutMs": 10000
      },
      { "op": "setText", "nodeId": "filter-duplicates", "value": "portrait" }
    ],
    "returnState": "compact"
  }
}
```

If Find opens a modal and times out, inspect current windows instead of repeating it.
Inspect list items and selection semantics before choosing an item; selection can activate controls.
Capture relevant before/after nodes with absolute OS-temp paths and read the images.
Verify app completion/readback separately from successful dispatch.
Keep driver events only after redacting sensitive inputs.
