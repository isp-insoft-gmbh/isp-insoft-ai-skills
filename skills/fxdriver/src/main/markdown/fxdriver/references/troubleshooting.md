# troubleshooting fxdriver

## Attach fails

Check PID:

```sh
jcmd -l
```

Common causes:

- process exited;
- wrong PID;
- target is not a compatible JVM;
- dynamic attach restricted;
- target runtime image lacks attach support;
- port already in use.

For the current prototype, launching the app with debug flags has worked:

```sh
-XX:+EnableDynamicAgentLoading
--add-modules=java.instrument
```

Do not assume final distribution will require these; integration remains open.

## RPC doesn't respond

Check attach output for endpoint/port.

Try:

```sh
java -jar target/fxdriver.jar rpc <port> ping '{}'
```

If port is closed, reattach or inspect app stderr.

## Selector matches too much

Text queries may match both a control and skin/internal text. Prefer:

```json
{ "nodeId": "save" }
{ "textExact": "Save" }
{ "selector": "role=BUTTON" }
```

Use highlight:

```sh
java -jar target/fxdriver.jar rpc <port> highlight '{"text":"Save"}'
```

## Click doesn't do expected thing

Prefer semantic/direct actions when available:

- inspect `semantic.kind`/state before acting;
- `fire` for `ButtonBase`;
- `setText` for `TextInputControl`;
- `selectListItem` for `ListView`.

Robot click can be backend/session sensitive, especially Wayland.

## Text/password fields

`PasswordField` snapshot text/value is redacted by default (`masked: true`). Use app-specific test hooks if tests must verify actual secrets; do not expect password values in snapshots.

## Virtualized controls

`ListView`, `TreeView`, `TableView`, and `TreeTableView` only realize visible cells. Snapshot shows visible nodes, not all model rows. Use `semantic` metadata for counts, selected indices/items, focus, root value, and table columns.

Use model primitives where available:

```sh
java -jar target/fxdriver.jar rpc <port> listItems '{"nodeId":"my-list"}'
java -jar target/fxdriver.jar rpc <port> selectListItem '{"nodeId":"my-list","itemText":"needle"}'
```

## Visual bugs

JSON assertions can pass while layout is visually wrong. Capture screenshots:

```sh
java -jar target/fxdriver.jar screenshot <port> target/failure.png
```

Read the screenshot. Use `image-diff` to localize changes, not to decide quality alone.

## Native dialogs

Native OS file/color dialogs are outside JavaFX scene graph. They are not MVP. Prefer app test mode or avoid native dialogs in fxdriver flows for now.

## WebView

MVP treats `WebView` as a JavaFX node with bounds. DOM-level automation is later.
