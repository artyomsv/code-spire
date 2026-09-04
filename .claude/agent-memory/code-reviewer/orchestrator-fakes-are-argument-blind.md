---
name: orchestrator-fakes-are-argument-blind
description: Orchestrator unit tests fake @ApplicationScoped collaborators with anonymous subclasses that discard every argument, so argument-order and constant-value mutations survive green — check this first in any factory/pipeline review
metadata:
  type: feedback
---

In `spire-orchestrator` unit tests, a collaborator is faked as an anonymous subclass of the real
`@ApplicationScoped` class, overriding one or two methods to return a mutable test field. Those
overrides ignore their parameters. So every mutation that changes only *which argument goes where*
survives the whole suite green.

Check these three shapes on every such test class before reading anything else:

1. **Swapped arguments of the same type** at the call site — `decide(reviewId, threadRef, ...)`,
   `nextAttempt(reviewId, findingRef)`. In `FixDispatch` this made both FR-F32 caps count on
   transposed keys, i.e. fail open, with 8/8 tests green.
2. **Swapped `int` constants** passed as caps or limits, and the constants' *values*, which the
   fake never sees. `MAX_PER_FINDING = 2` → `200` was invisible.
3. **Substring assertions on a derived id.** `assertTrue(id.contains(THREAD))` plus
   `assertTrue(id.endsWith(":3"))` passes for a run id whose workspace and slug are transposed.
   Insist on `assertEquals` against the whole id — run ids ARE the address
   (`FactoryRunProjection.queued` parses one straight back into provider/workspace/slug).

**Why:** the lead has acted on this exact class in three consecutive rounds (`nextAttempt` reading
the wrong axis; the destination floor's unpinned property; `isBlank`→`isEmpty` surviving), and asks
for "a mutation that leaves all N tests green" as the single most valuable review output. The fakes
are the reason such a mutation almost always exists.

**How to apply:** the fix to recommend is to make the fake a *witness* — record each parameter into a
field and add one test asserting what was asked for, not only what was returned. Also override every
remaining public method to `throw new AssertionError("not expected")`: CLAUDE.md records seven
instances where an un-overridden fake method opened a real database from a plain unit test, one of
them silently under a `catch (RuntimeException)`.

Related: [[review-both-encodings-of-one-rule]], [[test-fixture-cleanup-keys]].
