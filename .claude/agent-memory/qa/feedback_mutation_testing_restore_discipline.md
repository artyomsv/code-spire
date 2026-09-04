---
name: feedback-mutation-testing-restore-discipline
description: Mutation-test on a git archive copy in the scratchpad, never in the worktree — git checkout as a restore step is forbidden and has destroyed uncommitted work twice
metadata:
  type: feedback
---

Mutate a **copy**, never the worktree. Build the copy from the ref under review:

```
git archive HEAD | tar -x -C "<scratchpad>/probe-tree"     # ~13 MB for code-spire, no .git, no build/
cd "<scratchpad>/probe-tree" && ./gradlew --console=plain --no-parallel --max-workers=1 \
    :<module>:test --rerun --tests '<FQCN>'
```

Baseline the copy green before the first mutation, then loop: `sed -i` the exact line, `grep` to
prove the mutation landed, run the targeted test class, revert with the inverse `sed`, `grep` again.
The copy has its own `build/` and cannot race the real tree, so a peer agent building beside you is
harmless.

**Why:** the earlier version of this memory advised an `edit / test / git checkout --` loop in the
worktree. That is now explicitly forbidden by the review contract, because on two real runs it
reverted the user's uncommitted work and left the tree in a torn master/HEAD mix while peers were
building against it. The copy removes the failure mode rather than managing it. The copy approach
was used end to end on the Task-1 review (2026-09-02) for eight mutations across four modules with
the worktree untouched throughout.

**How to apply:** name the copy's location in the report and say the probe ran on a copy. Prefer
`--tests '<FQCN>'` to skip a module's slow Docker suites, and say in the report that the run was
narrowed. One mutation at a time — never batch across files, or a failure gets attributed to the
wrong change. Run `git status --porcelain` on the real worktree before and after and state the
result. For a class with no test file at all (see [[project-code-spire-test-gap-pattern]]) write a
throwaway probe test **in the copy**; nothing needs deleting afterwards.

A mutation that fails NOTHING is usually the more valuable finding than one that fails the intended
test — it is how a vacuous test is proved vacuous. See [[gradle-rerun-required]].
