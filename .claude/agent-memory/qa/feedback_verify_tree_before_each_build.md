---
name: verify-tree-before-each-build
description: Re-check git status immediately before every Gradle run in this shared worktree — another session may be editing it, and a clean check minutes ago proves nothing
metadata:
  type: feedback
---

Run `git status --short` and `git rev-parse HEAD` immediately before **each** build, not once at
the start of a review. Refuse to build when tracked files are modified, and say so rather than
producing numbers for a tree that is not the ref under review.

**Why:** on the round-3 QA pass over `feat/software-factory` the lead paused Gradle for a merge,
then messaged "resume, the worktree is clean at e1dfb01". It was clean when I checked, and eight
production files began changing seconds later — another session landing fixes for my own findings.
Building then would have produced results attributed to `e1dfb01` for a tree that was not
`e1dfb01`, and would likely have gone red on a test the in-flight change contradicted, looking like
a regression I had caused. A teammate's "the tree is stable" is their honest belief about a moment
that has already passed.

**How to apply:** before each module run, check status; if dirty, stop and report what changed with
mtimes rather than building. Reading the uncommitted diff is still useful and safe — it is how the
contradicted test was spotted (`FactoryRunProjection`'s re-arm SQL was reversed while
`FactoryRunProjectionTest` still asserted the old rule). When reporting anything learned that way,
label it as read from the diff, not measured. Pairs with
[[gradle-rerun-required]] and [[mutation-testing-restore-discipline]].

**Prove afterwards which tree you measured, don't discard the run.** The pattern recurred on the
2026-09-02 round-1 pass over `6036a11`: the tree was clean at the start and eight files began
changing at 21:38:59, again another session fixing the very findings being written up. The run was
salvaged rather than thrown away by comparing timestamps — every
`<module>/build/test-results/test/TEST-*.xml` was written at 21:37:28, before the first edit, so the
numbers provably describe the reviewed ref. `build/classes/**/*.class` mtimes tell the complementary
half: they had moved to 21:40+, showing someone recompiled the edited sources *after* the results
were written. Two rules fall out — check the XML mtimes against the source mtimes before reporting
any count, and treat a class file NEWER than the result XML as proof the numbers predate the current
tree rather than as a reason to doubt them.

**Interference has a signature — but a precise failure list is NOT proof of innocence.** Probes run
in the scratchpad copy while another session was compiling still produced exactly the predicted
per-mutation failures (2, then 1, then 6, each the intended test), so an attributable failure set is
usually trustworthy. **Corrected on the 2026-09-02 round-1 pass over `e57b76b`:** the lead began M1
Task 7 (`RunRegistry`, `RunControlListener`, `RunDispatcher`) mid-build, and `testServices` came back
with a tidy, specific list — ten named `RunDispatcherTest` methods, all
`java.lang.NoSuchFieldError`. That reads exactly like a real regression. It was a torn classpath:
test classes compiled against one version of `RunDispatcher`, main classes another.

So refine the signature. **`NoSuchFieldError` / `NoSuchMethodError` / `NoClassDefFoundError` is
linkage, not logic** — a compiled-against-X-ran-against-Y symptom that concurrent editing produces
and that a genuine test failure almost never does. Treat any of them as a torn tree until proven
otherwise, however precise the failure list looks, and check whether the failing test class names a
source file that is currently modified in `git status`.

**The clean escape is a pristine `git archive HEAD` copy, and it also fixes the lifecycle-task
problem.** `--rerun` cannot force `testFast`/`testServices` (see [[gradle-rerun-required]]), and in
this worktree a plain `./gradlew testFast` returned `BUILD SUCCESSFUL in 2s, 64 up-to-date` — a
cached pass, zero tests run. Extracting the ref into the scratchpad
(`git archive HEAD | tar -x -C <scratch>/qaprobe`) gives a tree with no `build/`, so every task
executes by construction, the numbers describe the reviewed commit and nothing else, and no amount
of concurrent editing in the worktree can reach them. Add `--no-build-cache` so the shared cache
cannot hand back a result from the dirty tree. This is now the default way to measure a tier here,
not the fallback.
