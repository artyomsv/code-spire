---
name: run-unit-env-vars-are-not-env-example-keys
description: SPIRE_* variables the run worker computes for the run unit's containers are NOT .env.example keys — do not flag a new publisher/agent env var as an .env.example contract violation
metadata:
  type: project
---

The factory's run-unit container variables (`SPIRE_REMOTE_URI`, `SPIRE_BRANCH`, `SPIRE_BRANCH_BASE`,
`SPIRE_BASE_COMMIT`, `SPIRE_PROTECTED_PATHS`, `SPIRE_BUNDLE_MAX_BYTES`, `SPIRE_GIT_USERNAME`,
`SPIRE_GIT_SECRET`, and now `SPIRE_BRANCH_MODE` / `SPIRE_PROTECTED_BRANCH`) appear in **no**
`.env.example`, by design. They are computed per run by
`spire-run-worker/.../RunUnitBuilder.java` and injected into the run unit's containers.

**Why:** `~/.claude/rules/secrets-and-env-handling.md` §"The `.env.example` contract" binds keys
that "exist in any `.env`" — deployment configuration an operator sets. A per-run container
variable is never in an operator's `.env`, so the rule does not reach it. Verified: not one of the
eight pre-existing publisher variables is in the root `.env.example` or `deploy/.env.example`, and
that has survived M0 + M1 review. Flagging a new one would be a fabricated gate, the same class of
error as [[no-project-rules-dir-and-no-lint-gate]].

**Re-confirmed in the m2-t5b round, with a producer present.** The obvious objection — "the ruling
only held because nothing SET these variables" — does not survive the code. `RunUnitBuilder`
hardcodes the literal `"existing"` and reads the destination from `command.protectedBranch()`, i.e.
off the Kafka wire command, never from the run worker's own process environment. No operator sets
either one in any environment, so a producer existing changes nothing about the rule's reach.

**How to apply:** for a new `SPIRE_*` variable, first ask which side it lives on. Operator-set
service config → `.env.example` is mandatory. Run-unit container env → the homes that actually
matter are `docs/factory/AGENT-IMAGE-CONTRACT.md` (agent side only), the `PUBLISHER_MISCONFIGURED`
row in `docs/SMOKE-TEST.md` (which enumerates the publisher's refusal causes and goes stale), the
ADR that introduced it, and `RunUnitBuilder` itself. Check those, not `.env.example`. Related:
[[whole-pr-doc-drift-lives-in-the-design-docs]].
