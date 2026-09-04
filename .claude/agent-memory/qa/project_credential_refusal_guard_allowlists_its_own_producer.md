---
name: credential-refusal-guard-allowlists-its-own-producer
description: CredentialRefusalHasNoProducerTest allowlists RunFailureCause.java wholesale, so adding an ALIASES entry onto CREDENTIAL_REJECTED — the likeliest producer path — passes the whole fast tier
metadata:
  type: project
---

`spire-arch`'s `CredentialRefusalHasNoProducerTest` exists to fail the build **when** a producer for
`CREDENTIAL_REJECTED` appears, so that three documents describing the credential pool's dead-key gap
get corrected. `docs/UNVERIFIED.md` §A1 cites it as the guard making that gap safe.

It cannot see the likeliest producer. Its `DEFINITION_AND_CONSUMERS` allowlist exempts
`spire-contract/.../event/RunFailureCause.java` **wholesale**, and that file also holds
`ALIASES` — the map whose own javadoc says translation from the harness's and publisher's
vocabularies "belongs here". So `Map.entry("AUTH_FAILED", CREDENTIAL_REJECTED)` is a live producer
that the guard files under "the module that DEFINES it".

**Why:** mutation-verified on PR #96 (2026-09-03) on a git-archive copy. Added that alias, ran the
whole `testFast` tier: `BUILD SUCCESSFUL in 55s`, zero failures. `RunFailureCauseTest`'s own
producer scan does not catch it either.

**How to apply:** when a guard's pass condition is *"the scan found only the allowlisted files"*,
check what else lives inside each allowlisted file. A whole-file exemption on the file that defines
a vocabulary also exempts every mapping onto that vocabulary. The fix is to exempt the enum
*constant declaration* and scan the rest of the file, or to assert `ALIASES` maps nothing onto the
cause. Same family as [[contract-snapshot-does-not-recurse]] — a check whose granularity is coarser
than the thing it guards. See also [[code-spire-test-gap-pattern]].
