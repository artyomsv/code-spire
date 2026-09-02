package dev.codespire.contract.event;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Why a run failed, from a closed set, recorded as data (FR-F9).
 *
 * <p>"Read the logs" is not a failure cause, and neither is a free string. A cause used to arrive
 * from three vocabularies that agreed with each other only partly — three literals in the launcher,
 * the harness's own {@code FailureCause}, and whatever the publisher wrote into its outcome JSON —
 * and landed in an unconstrained {@code VARCHAR(32)}. A typo in any writer became a category no
 * query matched and no operator saw grouped.
 *
 * <p><b>Each value earns its place by being actionable by a different person, or actionable in a
 * different way.</b> That is the test for adding one, and it is why the harness's twelve values do
 * not map one-to-one. {@code PROVIDER_ERROR} and {@code NO_MODEL_RESPONSE} become
 * {@link #MODEL_UNAVAILABLE} while {@code HARNESS_EXIT_NONZERO} becomes {@link #AGENT_FAILED}, and
 * the split is load-bearing: an outage clears and deserves a retry, an agent that ran and failed the
 * task will fail it again. An earlier draft collapsed all three and a test caught it, because the
 * collapse quietly took the retry away from the one of them that had earned it.
 *
 * <p><b>Parsing is lenient although the set is closed, and the two are not in tension.</b> An
 * unrecognised value becomes {@link #UNCLASSIFIED} rather than throwing. The alternative has already
 * cost this project a paid run: a value the pipeline could not accept threw inside a result handler
 * and dead-lettered a review that had already been charged for. A failure to classify must never
 * become a second failure — least of all after the money is spent.
 */
public enum RunFailureCause {

    // --- The request was wrong. The person who asked fixes it. ---

    /** Malformed or unacceptable dispatch input. The same command fails the same way. */
    BAD_COMMAND(false, false),

    // --- The deployment is misconfigured. An operator fixes configuration. ---

    /** The agent or publisher image could not be pulled. A registry blip is transient. */
    IMAGE_UNAVAILABLE(true, false),

    /** The publisher started without something it needs. Configuration, not weather. */
    PUBLISHER_MISCONFIGURED(false, true),

    /** No runtime could place the unit — the daemon is down or unreachable. */
    RUNTIME_UNAVAILABLE(true, false),

    // --- Credentials. An operator fixes the registry. ---

    /** The forge or the model provider refused the credential. An answer, not a blip. */
    CREDENTIAL_REJECTED(false, true),

    /** Every member of the harness credential pool is exhausted or rejected (FR-F12). */
    ALL_CREDENTIALS_EXHAUSTED(false, true),

    // --- The agent ran. Read the run. ---

    /**
     * The agent ran to completion and did not deliver. Not retryable: the same prompt against the
     * same commit produces the same result, and the model has already been paid for.
     */
    AGENT_FAILED(false, true),

    /**
     * The model provider errored or returned nothing at all, so the agent never got its answer.
     *
     * <p>Separate from {@link #AGENT_FAILED} because the two send different people to different
     * places and deserve opposite retry answers. An outage clears; an agent that ran and failed the
     * task will fail it again. Collapsing them cost the retry that a provider blip should get.
     */
    MODEL_UNAVAILABLE(true, true),

    /** The agent outlived its wall clock and was stopped. */
    AGENT_TIMEOUT(false, true),

    /** The agent's egress was blocked, so it could not reach the model or a dependency. */
    BLOCKED_EGRESS(true, true),

    // --- The sandbox died under the run. Infrastructure. ---

    /** Evicted, unreachable, out of memory, or otherwise gone before it finished. */
    SANDBOX_LOST(true, true),

    // --- Delivery. The push half. ---

    /** The init container could not clone the repository. */
    CLONE_FAILED(true, false),

    /** The push gate refused the change. It refuses the same tree the same way (ADR-037). */
    GATE_REFUSED(false, true),

    /** The forge rejected the push for a reason that is not a stale parent. It will reject it again. */
    PUSH_REJECTED(false, true),

    /**
     * The push never reached the forge — a network, DNS or TLS fault on the way out.
     *
     * <p>Distinct from {@link #PUSH_REJECTED} because the forge never answered, so the same push
     * may well succeed. Collapsing the two answered "never retry" for a transient fault, which is
     * the opposite of what {@link #CLONE_FAILED} already answers for the identical condition on the
     * way in.
     */
    PUSH_TRANSPORT_FAILED(true, true),

    /** The branch moved under the run; a retry pushes the same stale parent again. */
    NON_FAST_FORWARD(false, true),

    /** A bundle reached the handoff and could not be read as one. */
    BUNDLE_UNREADABLE(false, true),

    /** A commit the agent made never reached a bundle. */
    DROPPED_COMMIT(false, true),

    // --- The control plane itself. Our bug, or our infrastructure. ---

    /** Finalization failed, so the workspace is preserved rather than destroyed (FR-F7). */
    SALVAGE_FAILED(false, true),

    /** The broker never acknowledged the dispatch. */
    DISPATCH_FAILED(true, false),

    /** Dispatch may or may not have landed, and an operator must resolve which (FR-F10). */
    DISPATCH_UNCERTAIN(false, true),

    /** An operator or a policy cancelled the run (FR-F6). */
    CANCELLED(false, true),

    /** The worker failed for a reason of its own that is none of the above. */
    WORKER_FAILED(true, true),

    /**
     * The broker refused the run's result, in full and again compacted.
     *
     * <p>Not retryable, and not an alias of {@link #WORKER_FAILED}, which answers the opposite: a
     * re-run produces a result refused again for the same reason. An operator raises the record
     * limit; nobody re-runs anything. Different person, different action, so its own value.
     */
    RESULT_UNPUBLISHABLE(false, true),

    /**
     * A wire value this version does not recognise.
     *
     * <p>Not choosable by a writer: it exists so an unknown string has somewhere to land instead of
     * throwing, and a writer that selected it would be recording "we did not look", which is
     * exactly what FR-F9 forbids.
     */
    UNCLASSIFIED(false, true);

    /**
     * Every alias that maps onto a value, from the vocabularies that reach the wire.
     *
     * <p>The harness names a failure in its own terms and the publisher in its own; both are older
     * than this set and neither should be forced to adopt it, because each is meaningful inside its
     * own module. The translation belongs here, where the wire vocabulary is defined.
     */
    private static final Map<String, RunFailureCause> ALIASES = Map.ofEntries(
            // spire-harness FailureCause: the agent's own words for "ran, did not deliver".
            Map.entry("PROVIDER_ERROR", MODEL_UNAVAILABLE),
            Map.entry("NO_MODEL_RESPONSE", MODEL_UNAVAILABLE),
            Map.entry("HARNESS_EXIT_NONZERO", AGENT_FAILED),
            Map.entry("TIMED_OUT", AGENT_TIMEOUT),
            Map.entry("OUT_OF_MEMORY", SANDBOX_LOST),
            Map.entry("EVICTED", SANDBOX_LOST),
            Map.entry("SANDBOX_UNREACHABLE", SANDBOX_LOST),
            Map.entry("PUSH_GATE_REFUSED", GATE_REFUSED),
            // spire-publisher outcome JSON.
            Map.entry("PUSH_FAILED", PUSH_TRANSPORT_FAILED),
            Map.entry("PUBLISHER_FAILED", WORKER_FAILED));

    /**
     * Canonical names and aliases in one lookup.
     *
     * <p>{@code toUnmodifiableMap} throws on a duplicate key, so an alias that shadows a canonical
     * name fails at class initialisation rather than quietly winning at runtime.
     */
    private static final Map<String, RunFailureCause> BY_WIRE_VALUE = Stream.concat(
                    Stream.of(values()).map(cause -> Map.entry(cause.name(), cause)),
                    ALIASES.entrySet().stream())
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

    private final boolean retryable;

    private final boolean agentMayHaveSpent;

    RunFailureCause(boolean retryable, boolean agentMayHaveSpent) {
        this.retryable = retryable;
        this.agentMayHaveSpent = agentMayHaveSpent;
    }

    /**
     * Whether retrying this run could plausibly reach a different outcome.
     *
     * <p>A property of the cause, never of the caller. Every publisher failure used to be reported
     * retryable, so a run refused for a reason that will refuse it identically next time was retried
     * at the full price of another agent run.
     */
    public boolean isRetryable() {
        return retryable;
    }

    /**
     * Whether the agent could have bought tokens before this failure.
     *
     * <p>The charge ledger needs it. A failure is normally NOT a free outcome — an agent can
     * work for an hour and then have its push rejected — so a run that failed is charged like
     * one that succeeded. But five causes are raised before the agent's first token, and a
     * zero-token row for those is not a harmless extra: the deployment-wide cap counts
     * {@code COUNT(DISTINCT call_ref)} as well as summing money, so a daemon outage failing
     * every dispatch in seconds would spend the whole call budget on runs that bought nothing —
     * and that budget gates the review pipeline too. A control firing for the wrong reason,
     * which is the same defect as one that never fires.
     *
     * <p><b>The default is true, deliberately.</b> Losing a real charge is the failure this axis
     * exists beside, so anything ambiguous charges: a rejected credential may be the forge's
     * rather than the model's, an exhausted pool means earlier members were paid, and an
     * uncertain dispatch may well have run. Only a cause that provably precedes the agent
     * answers false.
     */
    public boolean agentMayHaveSpent() {
        return agentMayHaveSpent;
    }

    /** Whether a writer may select this cause. False only for {@link #UNCLASSIFIED}. */
    public boolean isChoosable() {
        return this != UNCLASSIFIED;
    }

    /**
     * The cause a wire value names, or {@link #UNCLASSIFIED} if this version does not know it.
     *
     * <p>Never throws. See the class note: a value we cannot interpret is answered at the boundary
     * that receives it, because throwing here would fail a run that has already been paid for.
     */
    public static RunFailureCause of(String wireValue) {
        if (wireValue == null || wireValue.isBlank()) {
            return UNCLASSIFIED;
        }
        return BY_WIRE_VALUE.getOrDefault(wireValue.strip().toUpperCase(Locale.ROOT), UNCLASSIFIED);
    }

    /** The names a writer may legitimately produce, for a check that wants to enumerate them. */
    public static Set<String> choosableNames() {
        return Stream.of(values())
                .filter(RunFailureCause::isChoosable)
                .map(Enum::name)
                .collect(Collectors.toUnmodifiableSet());
    }
}
