# Two hand-rolled credential scrubbers, with rules that differ and a javadoc claiming they do not

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Small |
| Location | `spire-run-worker/.../SecretScrub.java`; `spire-publisher/.../OutcomeWriter.java` (`scrub`) |
| Found during | PR #96 whole-PR review (code-quality S3) |
| Date | 2026-09-03 |

## Issue

Both implement the same three forms a credential takes — literal, URL-encoded, and
`base64(user:secret)`. They differ in ways nothing documents as deliberate:

| | `SecretScrub` | `OutcomeWriter.scrub` |
|---|---|---|
| Minimum length | 8 characters | none |
| Ordering | longest form first, so one secret containing another is fully redacted | none |
| Credentials | many, each with its own username | one |

`SecretScrub`'s own javadoc asserts they are equivalent: *"The publisher already scrubs the git
secret … the same three forms apply here."* They do not.

This repository already refuses this shape elsewhere. `spire-arch`'s `RedirectHandlingHasOneHomeTest`
fails the build on a fourth hand-rolled redirect loop, after `PinnedJsonClient` was extracted because
four near-identical copies existed.

## Risks

A credential scrubber is a stronger case for one home than a redirect loop, and the weaker of the two
copies runs in the container holding the git **write** token. A fix applied to one will not reach the
other — which is the shape that produced three separate defects in this milestone already.

## Suggested Solutions

Extract one scrubber. Both modules are FSL, so a shared home is licence-clean; `spire-run-worker`'s
version is the more complete of the two and should be the basis. Then either add the sibling guard in
`spire-arch` or accept that the extraction itself is the guard.
