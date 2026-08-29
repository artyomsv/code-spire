# Where tech debt lives

Two places, on purpose, split by what the item actually is.

## Files in this directory — code-level debt

`techdebt/<module>/{criticality}-{complexity}-{description}.md`, where `<module>` is
the repository folder that owns the root cause, or `global/` when the fix genuinely
spans two or more independently-deployed services.

Criticality `1`–`4` (critical → low), complexity `1`–`4` (trivial → large). The
counts in `CLAUDE.md` are derived from these files, so a debt tracked only in the
issue tracker will not appear there.

Use a file when the debt is a property of the code: missing coverage, a swallowed
exception, an unguarded call, a hardcoded value, a duplicated pattern. It is
resolved by an edit, and the file is deleted in the same commit as the fix.

## GitHub issues labelled `tech-debt` — deferred verification

Use an issue when the debt is not a defect in the code but a **claim the code makes
that nobody has tested yet**. Those need a corpus, a live deployment, a spend
budget, or elapsed time — none of which a file in a repository can carry, and all of
which want a thread where results accumulate.

The first of these is [#89](https://github.com/artyomsv/code-spire/issues/89):
rung 2 of the repository knowledge base shipped on an operator override rather than
the ADR-026 §9 evidence gate, so what it does is proven and what it is *worth* is
not.

## Before adding either

Search both. A duplicate entry in the other mechanism is the specific failure this
file exists to prevent.
