package dev.codespire.agentimage;

import java.util.List;

/**
 * The clause ids, in one place, because two things must agree about them.
 *
 * <p>{@code docs/factory/AGENT-IMAGE-CONTRACT.md} documents each one and {@link AgentImageVerifier}
 * checks it. A clause documented and not checked is a promise nothing keeps; a clause checked and
 * not documented is a failure an operator cannot look up. {@code ContractAndCheckerAgreeTest} holds
 * both directions against this list.
 *
 * <p>Ids are stable and greppable, so a conformance failure in a log can be found in the contract
 * without reading either.
 */
public final class Clauses {

    /** The image declares an ENTRYPOINT, or the harness runs with no handoff protocol around it. */
    public static final String ENTRYPOINT = "entrypoint";

    /** USER is not root: this container runs untrusted model output at full shell access. */
    public static final String NON_ROOT = "non-root";

    /** /workspace and /handoff exist and belong to the run user, or a fresh volume is root's. */
    public static final String MOUNT_POINTS = "mount-points";

    /** The handoff is git bundles, so a missing git binary produces nothing and reports success. */
    public static final String GIT = "git";

    /** Without a trust store every TLS call fails, and at least one harness retries silently. */
    public static final String CA_CERTIFICATES = "ca-certificates";

    /** The work item reaches the harness on stdin, never on argv where inspect and ps print it. */
    public static final String PROMPT_ON_STDIN = "prompt-on-stdin";

    /** Commits leave as bundles: this container holds no credential, so there is no other way. */
    public static final String HANDOFF_BUNDLES = "handoff-bundles";

    /** DONE means "everything I produced is here", so writing it early truncates the run. */
    public static final String HANDOFF_DONE_LAST = "handoff-done-last";

    /** What the image can build. Unverifiable without the repository it would build. */
    public static final String TOOLCHAIN = "toolchain";

    /** Which harness the image provides. Unverifiable without a model credential and a paid call. */
    public static final String HARNESS = "harness";

    /** The label a {@link #TOOLCHAIN} declaration is read from. */
    public static final String TOOLCHAIN_LABEL = "dev.codespire.agent.toolchain";

    /** The label a {@link #HARNESS} declaration is read from. */
    public static final String HARNESS_LABEL = "dev.codespire.agent.harness";

    /** Every clause this checker proves, in report order. */
    public static final List<String> VERIFIED = List.of(
            ENTRYPOINT, NON_ROOT, MOUNT_POINTS, GIT, CA_CERTIFICATES,
            PROMPT_ON_STDIN, HANDOFF_BUNDLES, HANDOFF_DONE_LAST);

    /**
     * Every clause the image may declare and this checker will not prove.
     *
     * <p>A separate list rather than a flag, so "move a clause from declared to verified" is an
     * edit to both this file and the contract — which is exactly the change that should not happen
     * quietly.
     */
    public static final List<String> DECLARED = List.of(TOOLCHAIN, HARNESS);

    private Clauses() {
    }
}
