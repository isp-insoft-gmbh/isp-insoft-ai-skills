---
name: java-http3-client
description: "Use when Java 26+ `java.net.http.HttpClient` should prefer or require HTTP/3."
license: CC-BY-4.0-summary
compatibility: "JDK 26+"
metadata:
  sources:
    - "https://openjdk.org/jeps/517"
---

# HTTP/3 for HttpClient

Use for **HTTP/3 for HttpClient** work from [JEP 517](https://openjdk.org/jeps/517) when targeting JDK 26+.

## When active

- Select `HttpClient.Version.HTTP_3` on the client or request.
- Check `HttpResponse.version()` because fallback can occur.
- Use strict discovery only when server HTTP/3 support is required.

## Pitfalls

- HTTP/3 is opt-in; HTTP/2 remains default.
- The legacy `java.net.URL` HTTP handler is not HTTP/3.
- [JEP 517](https://openjdk.org/jeps/517) is client API support, not a public QUIC API or server.

## Examples

Read `examples/jep-517.md` for JEP-derived snippets.

## References

- https://openjdk.org/jeps/517
