package dev.codespire.runtime;

/**
 * What a runtime can do, declared rather than inferred from {@link RuntimeType}.
 *
 * <p><b>Read the "who reads this" note on each component before adding a seventh.</b> This javadoc
 * used to open "the domain reads these", and for the whole of M1 the domain read none of them:
 * {@code capabilities()} had no production caller at all, and neither did {@code type()}. A record
 * that looks like a decision point and is not is worse than no record — the next author wires
 * something to a value nothing maintains, or leaves a capability unimplemented believing a caller
 * checks it first. So each component now says what actually consults it today.
 *
 * <p>{@code steering} is the one with a real consumer, and it is a TEST consumer by design:
 * {@code RunRuntimeContract} asserts that {@code steer} throws exactly when this is false. The
 * production gate deliberately sits one layer up, on what the HARNESS declares — a one-shot agent
 * has no session to steer whatever the container could carry — and the runtime's throw is the
 * backstop. Binding the declaration to the behaviour in the contract is what stops the two
 * drifting apart while both look present.
 *
 * <p>The rest are declarations waiting for a second arm (M5). They are kept rather than deleted
 * because each names a real difference a Kubernetes arm will have, and rediscovering them is more
 * expensive than carrying them — but "waiting for a reader" is now written down instead of
 * implied by a javadoc that claimed one.
 *
 * <p><b>Read the note uniformly: "no PRODUCTION caller branches on it".</b> A first attempt at
 * this rewrite said "NOT READ ANYWHERE" of two components that an arm's own test does read, which
 * is the same drift in the opposite direction — a paragraph written to stop a false claim making
 * two more. Where an arm declares a value about itself in a test, that is named below.
 *
 * <p>Six positional booleans, so each is documented: a swapped pair compiles and reads plausibly,
 * and only a test asserting the whole record by value catches it.
 *
 * @param networkPolicy     the runtime can restrict a unit's egress. Absent, the agent reaches
 *                          whatever the host can, and the model-provider allowlist is advisory.
 *                          No production caller branches on it; the Docker arm asserts its own
 *                          {@code false} in {@code DockerRunRuntimeIT}. So nothing is advisory on
 *                          that basis today — the real exposure is tracked in
 *                          {@code techdebt/spire-runtime-docker/4-3-the-agent-container-on-the-}
 *                          {@code default-bridge-reaches-host-published-ports.md}.
 * @param resourceLimits    memory, CPU and disk ceilings are enforced rather than requested. Read
 *                          by the Docker arm's own test as a declaration about itself; no
 *                          production caller branches on it, because a unit spec that cannot be
 *                          limited is refused by {@code RunUnitSpec} before any runtime sees it.
 * @param steering          a running agent can be sent further input. Read by
 *                          {@code RunRuntimeContract}, which holds the declaration to the
 *                          behaviour. No shipped arm has it.
 * @param archival          a finished unit's filesystem can be preserved for inspection, which is
 *                          what makes a failed salvage recoverable rather than merely reported.
 *                          Read by nothing at all, production or test: the Docker arm preserves
 *                          unconditionally on a failed salvage.
 * @param garbageCollection the runtime reclaims abandoned units on its own. Absent, the orphan
 *                          watchdog is the only thing that does. Read by nothing at all: the
 *                          watchdog runs regardless, and an arm that also collects makes it a
 *                          no-op rather than a conflict.
 * @param nativeSidecar     Kubernetes >= 1.29 terminates a sidecar when the main container exits.
 *                          Absent, the publisher must learn the agent finished from a sentinel
 *                          file instead, because nothing will stop it otherwise (RUN-TOPOLOGY §3).
 *                          No production caller branches on it — the Docker arm and
 *                          {@code RuntimeSpiTest} assert its value as a declaration — and the
 *                          sentinel path is UNCONDITIONAL, so an arm answering true would change
 *                          nothing until that path is made to branch on this.
 */
public record RuntimeCapabilities(boolean networkPolicy, boolean resourceLimits, boolean steering,
                                  boolean archival, boolean garbageCollection, boolean nativeSidecar) {
}
