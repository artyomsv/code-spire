# A settings-read fault silently falls back to the seed review mode, which may be `active`

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Small |
| Location | `spire-orchestrator/.../settings/AppSettingRepository.java` (`get`); `spire-orchestrator/.../policy/ReviewPolicy.java` (`currentMode`) |
| Found during | M2 task 1 review (the observe-mode gate), security lens |
| Date | 2026-09-04 |

## Issue

`AppSettingRepository.get` answers `Optional.empty()` on a `SQLException`. `ReviewPolicy.currentMode`
then falls back to the `spire.review.mode` seed default. So **"nobody has set this"** and **"the
database could not be read"** arrive as the same value, and the second resolves to a posture the
operator did not choose.

On a deployment seeded `SPIRE_REVIEW_MODE=active` whose operator later flipped the slider to
`observe`, a single failed `SELECT` makes that one event fail **open**: the review runs, the model is
paid, and comments are posted, under a mode the operator believes is passive.

This is pre-existing and shared by every `observeOnly()` caller — the pull-request path has had it
since observe mode shipped. It is recorded now because M2 task 1 added two more callers (the
`/command` gate and the reply gate) and made the archived notice a third, so the same fail-open now
covers three more paths than it did.

## Risks

Low, and bounded by how it is reached. A fully-down database cannot spend anything — the paths that
follow (`commitOf`, `clearWorkerIdempotency`, the credential read) fail first — so this needs a
*single* transient failed read on an otherwise healthy pool, and it affects exactly the one event
being handled. The next event reads the setting again and behaves correctly.

What makes it worth recording rather than ignoring is the direction. A security posture that fails
*open* on a transient fault is the wrong default even when the window is narrow, and the operator has
no signal that it happened: the fallback is silent.

## Suggested Solutions

1. **Give `ReviewPolicy` a way to tell the two apart, and fail to `observe` when the value is
   unreadable.** `AppSettingRepository.get` currently collapses "unset" and "unreadable" into one
   empty; a second method (or a three-valued result) distinguishes them without changing the many
   existing callers for whom a missing setting genuinely means "use the default". Refusing to act on
   an unreadable posture is the same call ADR-023 made for an unknown price: unknown is not zero, and
   here unreadable is not active.
2. **Log the fault at WARN when it happens.** Today `get` swallows the `SQLException` entirely, so
   the fallback leaves no trace at all. Even without (1), an operator who can see it happened can
   investigate; today there is nothing to see.
3. Leave it. Defensible only while the window stays one event wide and no path treats
   `observeOnly()` as a security boundary rather than a posture. M2 moved it closer to the former,
   which is why this was filed.
