package dev.codespire.runtime;

/**
 * What a runtime can do. The domain reads these; it never branches on {@link RuntimeType}.
 *
 * <p>Six positional booleans, so each is documented: a swapped pair compiles and reads plausibly,
 * and only a test asserting the whole record by value catches it.
 *
 * @param networkPolicy     the runtime can restrict a unit's egress. Absent, the agent reaches
 *                          whatever the host can, and the model-provider allowlist is advisory.
 * @param resourceLimits    memory and CPU ceilings are enforced rather than requested.
 * @param steering          a running agent can be sent further input. No M0 arm has this.
 * @param archival          a finished unit's filesystem can be preserved for inspection, which is
 *                          what makes a failed salvage recoverable rather than merely reported.
 * @param garbageCollection the runtime reclaims abandoned units on its own. Absent, the orphan
 *                          watchdog is the only thing that does.
 * @param nativeSidecar     Kubernetes >= 1.29 terminates a sidecar when the main container exits.
 *                          Absent, the publisher must learn the agent finished from a sentinel
 *                          file instead, because nothing will stop it otherwise (RUN-TOPOLOGY §3).
 */
public record RuntimeCapabilities(boolean networkPolicy, boolean resourceLimits, boolean steering,
                                  boolean archival, boolean garbageCollection, boolean nativeSidecar) {
}
