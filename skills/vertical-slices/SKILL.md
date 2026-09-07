---
name: vertical-slices
description: "Use when planning or implementing vertical slices, walking skeletons, or thin end-to-end increments."
---

# Vertical Slices

Deliver working behavior, not layer inventory.

## Slice loop

1. Select the smallest complete behavior required by the task that is observable through a user-facing or public interface.
2. Identify only the architectural layers and components that behavior requires.
3. Implement and integrate those parts as one unit.
4. Test the observable behavior, adding narrower tests only for nontrivial rules.
5. Run fresh verification and read its full result.
6. Leave the repository working before starting the next slice.

When no end-to-end path exists, make the first slice the thinnest working path through the critical components.
Include only the minimum prerequisite work that slice needs.

## Boundaries

A slice is complete only when its behavior is integrated, observable, tested, and freshly verified.
Do not batch work horizontally by UI, API, storage, architectural layer, or component.
Do not build speculative shared foundations ahead of the first behavior that needs them.
Not every slice touches every layer; it crosses every layer required for its behavior.
