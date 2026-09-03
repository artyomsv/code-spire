# The agent image contract (FR-F13)

A run unit's agent container is **your** image. Code Spire ships a reference one
(`deploy/agent/codex/Dockerfile`) and it is a reference, never a requirement: any image that
satisfies the clauses below can run a factory job.

This page is the contract. `spire agent-image verify <image>` checks it, and
`ContractAndCheckerAgreeTest` fails the build if this page and that command ever disagree — every
clause here has a check, and no check is undocumented.

## Why the report has two halves

The command reports **verified** clauses and **declared** clauses separately, and never mixes them.

A verified clause is one the checker actually proved, by inspecting the image or by running it. A
declared clause is one the image *claims* through a label and the checker **cannot** prove — because
proving it would need something the checker does not have, most often your repository.

A conformance report that blends the two is a report you cannot act on. "Toolchain: OK" reads as
proof; if it only means "the label was present", then an image that declares a toolchain it does not
have passes, and the first thing that notices is a run that has already been paid for.

---

## Verified clauses

Each has a stable id. `spire agent-image verify` prints the id, so a failure is greppable.

### `entrypoint` — the image has an entrypoint

The image must declare an `ENTRYPOINT`. Code Spire passes the harness argv as the container's
command, so an image with no entrypoint runs the harness directly and none of the handoff protocol
below happens — the run produces nothing and reports success.

*Verified by:* reading the image config.

### `non-root` — the image does not run as root

`USER` must be set to something other than `root` or `0`. The agent container runs untrusted model
output at full shell access; root in the container is a materially larger blast radius against the
kernel, and the reference image uses uid 1001.

*Verified by:* reading the image config.

### `mount-points` — `/workspace` and `/handoff` exist and belong to the run user

Both directories must exist in the image and be owned by the uid the image runs as. A fresh named
volume mounted onto a directory inherits that directory's ownership — so if they are root's, the
agent cannot write its own workspace and cannot produce a bundle. The symptom is a run that clones
correctly and then does nothing.

*Verified by:* running the image and reading the ownership.

### `git` — a git binary is on `PATH`

The handoff is git bundles. Without `git` the entrypoint's `checkpoint` silently produces nothing,
which reads as "the agent made no changes".

*Verified by:* running the image.

### `ca-certificates` — the image has a system trust store

Without it every TLS call fails with `invalid peer certificate: UnknownIssuer`, and at least one
harness retries silently rather than saying why (RUN-TOPOLOGY §9.4). A corporate deployment replaces
this store at run time (FR-F14), but an image with none at all fails before that can help.

*Verified by:* running the image.

### `prompt-on-stdin` — the harness receives the prompt on stdin

The work item reaches the harness on standard input, never on argv. A prompt on argv is visible in
`docker inspect` and in the host's process list; it is also attacker-influenced text in a position
where quoting mistakes become command injection.

*Verified by:* running the image with a stub harness that echoes what it read.

### `handoff-bundles` — commits leave as bundles on `/handoff`

When the harness exits, the entrypoint must write at least one `*.bundle` to `/handoff` for any
commit the harness made. The agent container holds no git credential, so a bundle is the only way
work leaves it.

*Verified by:* running the image with a stub harness that makes one commit.

### `handoff-done-last` — `DONE` is written after the last bundle

The publisher treats `DONE` as "the agent has finished and everything it produced is here". Written
early, the publisher drains and exits while the final bundle is still being written, and the run
reports the previous checkpoint as its result.

*Verified by:* running the image and comparing modification order.

---

## Declared clauses

These are labels. The checker reports whether the label is present and **never** reports it as
verified.

### `toolchain` — `dev.codespire.agent.toolchain`

What the image can build. The reference Codex image declares `node`; an image for a Java repository
would declare something else.

*Why it cannot be verified:* "carries the repository's toolchain" is a claim about a pairing of an
image and a repository. The checker has the image and no repository, so any generic check it could
run — is there a compiler? — would pass for images that cannot build your code and fail for images
that can.

### `harness` — `dev.codespire.agent.harness`

Which harness the image provides, matching a `HarnessAdapter` name (`codex`).

*Why it cannot be verified:* the checker can see that *a* command exists; it cannot tell whether it
behaves as that harness without a model credential and a paid call. Running one to find out would
make a conformance check cost money.

---

## What conformance does not promise

Passing every clause means the image can participate in the protocol. It does not mean a run will
succeed: the toolchain may be wrong for the repository, the harness may be misconfigured, and the
model may refuse. Those are run outcomes, and the factory reports them as such.
