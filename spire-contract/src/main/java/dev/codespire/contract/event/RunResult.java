package dev.codespire.contract.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** What the run worker reports back on {@code cs.run-results}, keyed by {@code runId}. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = RunResult.RunStarted.class, name = "RunStarted"),
        @JsonSubTypes.Type(value = RunResult.RunFinished.class, name = "RunFinished"),
        @JsonSubTypes.Type(value = RunResult.RunFailed.class, name = "RunFailed")
})
public sealed interface RunResult {

    String runId();

    record RunStarted(String runId, String providerRunId) implements RunResult {

        public RunStarted {
            Objects.requireNonNull(runId, "runId");
        }
    }

    /**
     * A finished run.
     *
     * <p>{@code pushedRef} is null when the gate refused, and {@code blockedPaths} then names why.
     *
     * <p><b>{@code tokenUsage} is nullable, and null IS unknown.</b> The shape this replaces was
     * {@code Long inputTokens, Long outputTokens, boolean usageUnknown} — two representations of one
     * fact, free to disagree: {@code (inputTokens=5, usageUnknown=true)} was constructible and
     * meaningless, and a consumer reading the numbers while ignoring the flag would price a run
     * nobody measured. It is the same defect already removed from the harness SPI, where an
     * {@code Optional} wrapper around a type that already had an unknown value gave callers an
     * {@code orElse(0L)} door.
     *
     * <p>The map is keyed by the neutral token-bucket NAME rather than the enum, because that enum
     * lives in {@code spire-harness} and the wire contract does not depend on the harness tier.
     * {@code TokenBucketMatchesLedgerDimensionsTest} keeps the two vocabularies from drifting.
     *
     * <p><b>{@code agentUnobserved} carries the two facts that used to fight.</b> A run can put its
     * work on the branch and still not have finished: the agent overran its wall clock, or the
     * runtime could not read its exit. Reporting that as a failure hid delivered work; reporting it
     * as an ordinary finish asserted a clean delivery for a run whose agent was killed mid-thought.
     * Both were wrong in opposite directions, and this project has already made the same call twice
     * against itself — {@code V47} exists because a run that delivered nothing was written with the
     * same status as one whose branch reached the remote, and a refused review once rendered as five
     * green segments under "done", which the record calls worse than silence. So the result carries
     * both: the work IS on the branch, and the run is NOT complete.
     *
     * <p>Appending it is wire-safe, and the gate that stopped this change is the reason to say why
     * rather than just re-baselining: old JSON omits the field, Jackson defaults a missing boolean
     * to {@code false}, and {@code false} means "observed" — exactly what every record written
     * before this change meant. {@code RunResult} is a bus type under ADR-014 short retention, not a
     * persisted domain event, so nothing replays through it from an event store.
     *
     * <p>There is deliberately <b>no shorter constructor</b>. Adding a component to a wire record
     * silently drops it at every rebuild site while the convenience constructors stay valid — the
     * trap this repository paid for on {@code ReviewResult}. Rebuild through {@link
     * #withAgentUnobserved(boolean)}, which enumerates the components once, next to the record.
     */
    record RunFinished(String runId, String pushedRef, List<String> changedPaths,
                       List<String> blockedPaths, Map<String, Long> tokenUsage,
                       boolean agentUnobserved) implements RunResult {

        public RunFinished {
            Objects.requireNonNull(runId, "runId");
            changedPaths = List.copyOf(Objects.requireNonNull(changedPaths, "changedPaths"));
            blockedPaths = List.copyOf(Objects.requireNonNull(blockedPaths, "blockedPaths"));
            if (tokenUsage != null) {
                if (tokenUsage.isEmpty()) {
                    // An empty map is not a measurement. Allowing it would make "measured nothing"
                    // and "measured, and it was all zero" the same value — the fabricated zero
                    // ADR-023 exists to prevent, arriving through the wire instead of the ledger.
                    throw new IllegalArgumentException(
                            "an empty usage map is not a measurement — use null for unknown");
                }
                tokenUsage = Map.copyOf(tokenUsage);
            }
            if (pushedRef != null && !blockedPaths.isEmpty()) {
                throw new IllegalArgumentException(
                        "a run cannot have both pushed and been refused: " + pushedRef + " / " + blockedPaths);
            }
        }

        /** Whether the harness reported usage at all. Never infer this from a count. */
        public boolean usageIsKnown() {
            return tokenUsage != null;
        }

        /** Whether the push gate refused this run. */
        public boolean refused() {
            return pushedRef == null && !blockedPaths.isEmpty();
        }

        /** Whether this run both delivered work and left the agent's own outcome unobserved. */
        public boolean deliveredUnfinished() {
            return agentUnobserved && pushedRef != null;
        }

        /** Rebuild carrying a different observation flag, enumerating the components once. */
        public RunFinished withAgentUnobserved(boolean unobserved) {
            return new RunFinished(runId, pushedRef, changedPaths, blockedPaths, tokenUsage, unobserved);
        }
    }

    /**
 * A run that did not deliver.
 *
 * <p><b>{@code tokenUsage} is nullable, and null IS unknown</b> — the same contract as
 * {@link RunFinished}, and it is here for a reason that is easy to miss: a failure is not a
 * free outcome. An agent can work for an hour and then have its push rejected, so the tokens
 * were bought whatever the run's verdict. Without this the deployment's rolling spend window
 * was blind to exactly the runs most likely to be run again — under-counted precisely where it
 * is about to be charged a second time.
 *
 * <p>It is genuinely null for a failure raised before anything ran (a command refused at
 * validation), which is why the field cannot be made required: "spent nothing" and "spent an
 * amount nobody measured" are different facts and the ledger prices them differently.
 *
 * <p>Rebuild through {@link #withUsage(java.util.Map)} rather than a shorter constructor —
 * adding a component to a wire record silently drops it at every rebuild site while a
 * convenience constructor stays valid.
 */
    record RunFailed(String runId, String cause, String detail, boolean retryable,
                     Map<String, Long> tokenUsage) implements RunResult {

        public RunFailed {
            Objects.requireNonNull(runId, "runId");
            if (tokenUsage != null) {
                if (tokenUsage.isEmpty()) {
                    // Same rule as RunFinished: an empty map is not a measurement, and allowing
                    // it would make "measured nothing" and "measured, and it was all zero" the
                    // same value.
                    throw new IllegalArgumentException(
                            "an empty usage map is not a measurement -- use null for unknown");
                }
                tokenUsage = Map.copyOf(tokenUsage);
            }
            if (cause == null || cause.isBlank()) {
                // "read the logs" is not a failure cause (FR-F9). A failure with no named cause
                // reaches an operator as a row that says only that something went wrong.
                throw new IllegalArgumentException("a failed run must name its cause");
            }
        }

        /** Whether the harness reported usage at all. Never infer this from a count. */
        public boolean usageIsKnown() {
            return tokenUsage != null;
        }

        /** Rebuild carrying measured usage, enumerating the components once. */
        public RunFailed withUsage(Map<String, Long> usage) {
            return new RunFailed(runId, cause, detail, retryable, usage);
        }
    }
}
