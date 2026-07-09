# Missing types and docs

Missing types and missing docs are language- and project-dependent slop signals.

Follow project rules first.

## Missing or weak types

Missing types are mainly a problem in languages with some form of gradual typing, such as:

- TypeScript / JavaScript
- typed Python
- Teal / Lua

In those languages, well-typed code should be preferred.

In languages with stricter type systems, types cannot really be missing, but they can still be too general or too specific.

## Type specificity

In compiled languages, the general consensus is:

- use general types for parameters and declarations
- use specific types for values

## Missing docs or comments

For documentation, follow project rules in general.

Complex, surprising, or side-effect-heavy code almost always deserves a comment or doccomment.

## Good comments

Good comments explain:

- context
- reason
- motivation

They explain why the code exists or why it is shaped this way.

## Bad comments

Never simply translate what the code does into human language.

A comment that only restates the code is noise.

## Pseudo-code examples

### Bad: weak typing in gradually typed code

```text
function resize(value):
    return value * 2
```

In a gradually typed language, the input and output expectations are unclear.

### Better: well-typed gradually typed code

```text
function resize(value: Pixels) -> Pixels:
    return value * 2
```

Well-typed code should be preferred in languages that support gradual typing.

### Bad: comment only repeats the code

```text
# Add one to retry_count.
retry_count = retry_count + 1
```

This only translates the code into human language.

### Better: comment explains reason or context

```text
# The first retry happens immediately because the remote cache often becomes
# visible one tick after the write succeeds.
retry_count = retry_count + 1
```

This explains the reason and context.

### Worth documenting: surprising side effect

```text
function calculate_total(order):
    # Also marks expired discounts as used because the billing system expects
    # discount state to be finalized during total calculation.
    expire_old_discounts(order)
    return sum order lines
```

Complex, surprising, or side-effect-heavy code almost always deserves a comment or doccomment.
