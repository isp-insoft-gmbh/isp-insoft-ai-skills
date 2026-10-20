# Basic flow

Complete [runtime preflight](../references/runtime.md); use the installed JAR and resolved real Java executables.

Attach to an identified JavaFX process; record printed endpoint and token.
Call `capabilities`, then use a persistent HTTP caller for these JSON-RPC requests.
Check each reply before constructing a dependent plan.

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "query",
  "params": { "selector": "role=TEXT_FIELD", "mode": "compact", "limit": 10 }
}
```

After resolving the unique input and expected completion state:

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "run",
  "params": {
    "steps": [
      { "op": "setText", "nodeId": "search", "value": "Ada" },
      { "op": "wait", "regexText": "^1 result$", "timeoutMs": 3000 },
      { "op": "assert", "nodeId": "open-result", "enabled": true }
    ],
    "returnState": "compact"
  }
}
```

IDs and completion text are app-specific examples, not universal selectors.
If IDs are absent, discover safe text/role/handle alternatives; improve real source semantics when authorized.
Read nested step outcomes and query exact semantic values when compact state omits them.
Close only test-owned instances and verify exit.
