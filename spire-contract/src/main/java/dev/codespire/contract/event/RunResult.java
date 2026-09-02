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
     */
    record RunFinished(String runId, String pushedRef, List<String> changedPaths,
                       List<String> blockedPaths, Map<String, Long> tokenUsage) implements RunResult {

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
    }

    record RunFailed(String runId, String cause, String detail, boolean retryable) implements RunResult {

        public RunFailed {
            Objects.requireNonNull(runId, "runId");
            if (cause == null || cause.isBlank()) {
                // "read the logs" is not a failure cause (FR-F9). A failure with no named cause
                // reaches an operator as a row that says only that something went wrong.
                throw new IllegalArgumentException("a failed run must name its cause");
            }
        }
    }
}
