---
name: java-security-manager-disabled
description: "Use when migrating old Security Manager flags, policy files, permission checks, or `System.setSecurityManager` code on Java 24+."
license: CC-BY-4.0-summary
compatibility: "JDK 24+"
metadata:
  sources:
    - "https://openjdk.org/jeps/486"
    - "https://openjdk.org/jeps/411"
---

# Permanently Disable the Security Manager

Use for **Permanently Disable the Security Manager** work from [JEP 486](https://openjdk.org/jeps/486) when targeting JDK 24+.

## When active

- Remove `-Djava.security.manager` startup flags.
- Replace Security Manager sandboxing with process/container/OS controls or explicit app authorization.
- Use `jdeprscan` on older JDKs to find deprecated APIs.

## Pitfalls

- Policy files no longer enforce permissions.
- `System.setSecurityManager(...)` throws `UnsupportedOperationException`.
- `System.getSecurityManager()` always returns `null`.

## Examples

Read `examples/jep-486.md` for JEP-derived snippets.

## References

- https://openjdk.org/jeps/486
- https://openjdk.org/jeps/411
