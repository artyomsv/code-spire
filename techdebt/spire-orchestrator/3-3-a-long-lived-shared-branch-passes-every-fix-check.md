# A long-lived shared branch passes every check `/fix` makes

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Medium |
| Location | `spire-orchestrator/.../factory/FixTargets.java` (`whyNotPushable`); `spire-publisher/.../PublisherConfig.java` (`NEVER_PUSHED`, `looksLikeATrunk`) |
| Found during | M2 task 4+5 review (the ADR-040 branch mode), security lens |
| Date | 2026-09-04 |

## Issue

ADR-040 §2 says the destination branch is the truth: the publisher refuses the two literal trunk
names and refuses `SPIRE_PROTECTED_BRANCH`, which the orchestrator reads from the pull request's own
`dest_branch`. That covers a branch named `develop` **as a destination**. It covers it not at all as
a **source**.

A release pull request `develop → main` is a completely truthful row:

| Column | Value | Which check sees it |
|---|---|---|
| `pr_state` | `OPEN` | passes |
| `from_fork` | `false` | passes |
| `source_branch` | `develop` | **nothing tests this** |
| `dest_branch` | `main` | becomes `SPIRE_PROTECTED_BRANCH` — protects `main`, not `develop` |

So `/fix` on a finding in that pull request dispatches a run whose machine account commits directly
to `develop` — a branch several people share, that other pull requests are opened from, and that
nobody involved thinks of as "a pull request's branch". The publisher cannot catch it: `develop` is
not a literal trunk name, and its own protected branch says `main`.

The same shape reaches any team using a long-lived integration branch, a `release/*` branch, or a
stacked-pull-request workflow where the source of one is the destination of another.

**Not a defect in what was built.** Every check named in ADR-040 is implemented and tested. The gap
is that the ADR's model of "an existing branch" has exactly one shape in it — a short-lived feature
branch owned by one author — and the forge does not enforce that shape.

## Risks

Medium. It needs a workflow this repository does not use to be reachable, and the result is a
machine-authored commit on a shared branch rather than a lost one: it is visible, attributed, and
revertible. But it lands with no review of its own, on a branch whose whole purpose is that changes
reach it only through review — which is the property the fix path is supposed to preserve.

## Suggested Solutions

1. **A `spire.factory.fix.never-push` glob list** (empty by default, so no behaviour changes on
   upgrade), checked in `FixTargets.whyNotPushable` as its own `Unpushable` cause so the author is
   told which rule refused them. An operator naming `develop` and `release/*` closes it for their
   deployment, and the publisher keeps its literal-trunk floor underneath. This is the cheapest
   version and the one that fits the existing shape.
2. **Refuse any source branch that is some OTHER open pull request's destination**, read from the
   forge at dispatch time. Needs no configuration and is correct without an operator getting it
   right, but it is an API call per `/fix` and a second read of forge state — which is the same
   re-read the stale-`pr_state` gap wants, so the two are worth designing together rather than
   separately.
3. Leave it, and say so in the operator documentation for `/fix` before it is documented at all.
   Defensible only while `/fix` is undocumented and off by default, which is true today and stops
   being true the moment M2 ships a Runs screen that offers it.
