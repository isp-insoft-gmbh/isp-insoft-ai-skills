---
name: uuid-generator
description: "Generate UUIDs/GUIDs in various formats and versions. Use when: generating database keys, API IDs, session tokens, bulk UUIDs, namespace-based deterministic UUIDs (UUID5), format conversion (hyphenated/compact/URN/uppercase), or validating UUID strings."
---

# UUID Generator

Generate universally unique identifiers (UUIDs) in various formats for
distributed systems, databases, and APIs.

## CLI Usage

```bash
# Generate single UUID4 (random, default)
node scripts/uuid_generator.mjs

# Generate specific version
node scripts/uuid_generator.mjs --version 1  # time-based
node scripts/uuid_generator.mjs --version 4  # random (default)
node scripts/uuid_generator.mjs --version 5  # namespace-based

# Bulk generation
node scripts/uuid_generator.mjs --count 100
node scripts/uuid_generator.mjs --count 1000 --output uuids.txt

# Format options
node scripts/uuid_generator.mjs --format compact      # no hyphens
node scripts/uuid_generator.mjs --format urn        # urn:uuid:...
node scripts/uuid_generator.mjs --format uppercase   # uppercase hex

# Namespace UUID (UUID5 - deterministic)
node scripts/uuid_generator.mjs --namespace dns --name example.com
node scripts/uuid_generator.mjs --namespace url --name "https://foo.com/bar"

# Validate a UUID
node scripts/uuid_generator.mjs --validate "a1b2c3d4-e5f6-4789-abcd-ef0123456789"

# Export formats
node scripts/uuid_generator.mjs --count 10 --export json   # JSON array
node scripts/uuid_generator.mjs --count 10 --export csv    # CSV with header
```

## Quick Reference

| Version | Use Case                             | Deterministic |
| ------- | ------------------------------------ | ------------- |
| UUID1   | Time-ordered, MAC address disclosure | No            |
| UUID4   | Random, privacy-preserving           | No            |
| UUID5   | Namespace-based, reproducible        | Yes           |

| Namespace | Typical Use               |
| --------- | ------------------------- |
| dns       | Domain names              |
| url       | URLs                      |
| oid       | ISO OID                   |
| x500      | X.500 Distinguished Names |
