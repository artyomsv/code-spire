---
name: secret-scrub-spelling-traps
description: Two measured traps in the SecretScrub/proxy-credential tests — a %40 fixture cannot discriminate a decode-only regression, and deleting a startup refusal moved a URLDecoder throw onto the run-launch path
metadata:
  type: project
---

Both measured on `fix/credential-scrub-forms` (PR #107) by mutating a git-archive copy.

**A `%40` fixture cannot prove the raw spelling is carried.** `URLEncoder.encode("p@ss")` returns
`p%40ss` — identical to the operator's raw spelling, because `40` contains no hex *letter* and so no
case difference. So `bothSpellingsOfOneProxyPasswordAreScrubbed`, built on `p%40ss-TEST`, passes even
when `credentialIn` collects only the decoded form: `SecretScrub`'s own `URLEncoder` re-encoding
happens to reproduce the raw text. Use an escape with a hex letter (`%2f` → encodes back as `%2F`) and
the same test kills both the drop-`bothSpellings` and the decode-only mutations. Verified: unmutated
green, both mutations red.

**Why:** the whole point of collecting both spellings is that `URLEncoder` emits UPPERCASE hex and `+`
for a space, so a re-encoded form matches neither the environment variable nor the header. A fixture
where that difference vanishes tests the opposite of the claim.

**How to apply:** any test asserting "the raw URL spelling is covered" needs a fixture whose
percent-escape contains a letter, or a space. Check the fixture before trusting the assertion.

**Deleting a startup refusal can move a throw onto a live path.** `requireScrubbableProxyPasswords()`
was the ONLY caller of `proxyCredentials()` at startup. Removing it left `RunFailures.scrubFor` as the
first caller — on the run-launch path and inside `RunDispatcher`'s own catch — where `URLDecoder`
throwing on a bare `%` (`100%secure`) escapes the handler and skips `registry.forget()`.

**Why:** a refusal is often also the only *exercise* of the code path behind it. Removing it removes
the fail-fast, not just the rule.

**How to apply:** when a review removes a startup check, ask what else called the method it validated,
and where the first remaining caller sits. See [[fast-orchestrator-mutation-loop]] for the probe loop.
