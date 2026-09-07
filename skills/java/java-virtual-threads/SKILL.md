---
name: java-virtual-threads
description: "Use when Java 21+ code handles many blocking I/O tasks, executor choice, ThreadLocal use, or virtual-thread pinning."
license: CC-BY-4.0-summary
compatibility: "JDK 21+"
metadata:
  sources:
    - "https://openjdk.org/jeps/444"
    - "https://openjdk.org/jeps/491"
---

# Virtual Threads

Use for **Virtual Threads** work from [JEP 444](https://openjdk.org/jeps/444) when targeting JDK 21+.

## When active

- Prefer one virtual thread per task/request for blocking I/O.
- Use `Executors.newVirtualThreadPerTaskExecutor()` for task submission.
- Limit scarce external resources with pools/semaphores, not virtual-thread pools.

## Pitfalls

- Virtual threads do not speed CPU-bound work.
- Do not pool virtual threads.
- For JDK 21-23, long blocking inside `synchronized` can pin carriers; JDK 24 improves this via [JEP 491](https://openjdk.org/jeps/491).

## Examples

Read `examples/jep-444.md` for JEP-derived snippets.

## References

- https://openjdk.org/jeps/444
- https://openjdk.org/jeps/491
