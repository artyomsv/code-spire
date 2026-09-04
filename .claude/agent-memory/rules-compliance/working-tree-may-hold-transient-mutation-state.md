---
name: working-tree-may-hold-transient-mutation-state
description: A guard or CHECK constraint missing from a working-tree file may be a concurrent qa mutation run, not a defect — verify against the ref under review before flagging
metadata:
  type: feedback
---

When a file on disk is missing a guard, a CHECK constraint, or a whole line — and especially when
a `.orig` sibling sits next to it — check `git show HEAD:<path>` before reporting anything. The qa
agent's mutation verification removes a guard, runs the suite, and restores it, so the working tree
holds a torn version for seconds at a time.

**Why:** in the m2-t5b round I read
`spire-orchestrator/src/main/resources/db/migration/V54__factory_run_fix_target.sql` from disk and
found `-- constraint removed by mutation` where the closed-set CHECK belongs, alongside an untracked
`V54__factory_run_fix_target.sql.orig`. `git show HEAD:` on the same path had
`ALTER TABLE factory_run ADD CONSTRAINT factory_run_kind_closed CHECK (kind IN (...))` fully intact,
and `git diff --stat HEAD` on that directory was empty one call later — qa had already restored it.
Reporting "the migration lost its constraint" would have been a fabricated HIGH against a file that
was never broken, the same class of error as [[no-project-rules-dir-and-no-lint-gate]].

**How to apply:** this is what the read-only contract's "quote from the ref under review, not from
disk" clause is actually protecting against — it is not pedantry. Treat a stray `.orig`, `.rej` or
`.bak` as a live-tooling signal, not as repo litter to flag under git-workflow's "what NOT to
commit". Mention it to the lead as housekeeping (it is untracked and no `.gitignore` rule covers
`*.orig`), never as a violation of the commits under review. Related:
[[factory-review-debt-deletions-need-location-check]], which is the same "read the authoritative
ref" discipline pointed at techdebt entries.
