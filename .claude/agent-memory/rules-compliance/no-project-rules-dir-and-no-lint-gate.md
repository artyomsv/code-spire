---
name: no-project-rules-dir-and-no-lint-gate
description: code-spire has no .claude/rules/ and no modernizer/checkstyle/spotless in its Gradle build — report both facts instead of implying a gate exists
metadata:
  type: project
---

`code-spire` has **no project-level `.claude/rules/`** (its `.claude/` holds only `agent-memory/`
and `reviews/`), so every rule I check comes from `~/.claude/rules/`. And its Gradle build declares
**no `modernizer`, `checkstyle`, `spotless` or `errorprone` plugin** — verified by grepping the root
`build.gradle.kts`, every module build file, and `gradle/libs.versions.toml`.

**Why:** my agent instructions describe a modernizer step that assumes a Maven pipeline which can go
red. Reporting a "HIGH, the PR pipeline will fail" here would be a fabricated gate — the same class
of error this project records repeatedly (a claim in one document about behaviour that does not
exist). Two agents already burned a round on a diagnosis nobody had read the code for.

**How to apply:** still read Java for the modernizer patterns and report real hits, but label them
as convention findings against `~/.claude/rules/clean-code-java.md`, and say plainly that no build
gate enforces them. In particular `Optional.get()` guarded by an `isEmpty()` check is the
established house shape — 13 instances in `spire-orchestrator/src/main` alone — so it is LOW
consistency, never HIGH. Related: [[commit-style-is-narrative-not-conventional]].

**Two more house shapes that are NOT violations here.** (a) Methods with 4+ parameters — 20+ in
`spire-orchestrator/src/main` alone, several with five (`ContextKeyValidator.ping`,
`ProviderClients.identitySource`), so `clean-code-java.md`'s "Max 3 parameters" is LOW consistency
at most. (b) Narrative commit subjects with no `type(scope):` prefix, per
[[commit-style-is-narrative-not-conventional]]. Verify the house shape by grepping the module before
raising either.
