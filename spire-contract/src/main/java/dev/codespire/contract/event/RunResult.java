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
        @JsonSubTypes.Type(value = RunResult.RunWorkReady.class, name = "RunWorkReady"),
        @JsonSubTypes.Type(value = RunWorkArtifact.class, name = "RunWorkArtifact"),
        @JsonSubTypes.Type(value = RunReviewArtifact.class, name = "RunReviewArtifact"),
        @JsonSubTypes.Type(value = RunResult.RunFinished.class, name = "RunFinished"),
        @JsonSubTypes.Type(value = RunResult.RunFailed.class, name = "RunFailed")
})
public sealed interface RunResult permits RunResult.RunStarted, RunResult.RunWorkReady, RunWorkArtifact, RunReviewArtifact,
        RunResult.RunFinished, RunResult.RunFailed {

    String runId();

    record RunStarted(String runId, String providerRunId) implements RunResult {

        public RunStarted {
            Objects.requireNonNull(runId, "runId");
        }
    }

    /**
     * A completed build whose checkpoint remains unpublished. This is not a terminal publication
     * result. The same measured token map rides the eventual terminal result under the same charge
     * key; active wall time excludes the human hold between these two observations.
     *
     * <p>{@code stepSummary} is what the agent said it decided (M4 slice 8, §6.8), already capped and scrubbed by the
     * worker. Null means the build handed over none, or none that was a JSON object: the orchestrator records that as
     * {@code summary_missing}, never as an empty summary. A ready written before the field existed reads as null.
     * The six-component constructor carries none; rebuild through {@link #withStepSummary}, which enumerates the
     * components once, next to the record.
     *
     * <p>{@code startFromHead} is the checkpoint head the build started from (M4 slice 3, A2), null for a build from the
     * base; the orchestrator refuses a plan step's ready whose start is not the one it chose. Set through
     * {@link #withStartFromHead}.
     *
     * <p>{@code empty} says the held publisher saw no commit at all (M4 slice 3, §6.7, A3): a plan step that changed
     * nothing, reported at the head it started from. Only a step started from the base can be empty this way; a
     * checkpoint-started idle step still bundles its start and is judged empty by the orchestrator. A ready written
     * before the field existed reads as false. Set through {@link #withEmpty}.
     */
    record RunWorkReady(String runId, dev.codespire.contract.work.WorkRunBinding work, String head,
                        List<String> changedPaths, Map<String, Long> tokenUsage,
                        long activeWallSeconds, dev.codespire.contract.work.StepSummary stepSummary,
                        String startFromHead, boolean empty, List<EgressAttempt> egress) implements RunResult {
        public RunWorkReady {
            egress = egressOrNone(egress);
            if (runId == null || runId.isBlank()) throw new IllegalArgumentException("A ready run needs its identity");
            Objects.requireNonNull(work, "A ready run needs its work binding");
            if (head == null || !head.matches("[0-9a-f]{40}"))
                throw new IllegalArgumentException("A ready run needs its full checkpoint head");
            changedPaths = List.copyOf(Objects.requireNonNull(changedPaths, "changedPaths"));
            if (tokenUsage != null) {
                if (tokenUsage.isEmpty()) throw new IllegalArgumentException("An empty usage map is not a measurement");
                tokenUsage = Map.copyOf(tokenUsage);
            }
            if (activeWallSeconds < 0) throw new IllegalArgumentException("Active wall time cannot be negative");
            if (startFromHead != null && !startFromHead.matches("[0-9a-f]{40}"))
                throw new IllegalArgumentException("A start head is a full checkpoint head");
            // A build that committed nothing stands where it started; from the base, the orchestrator checks the base.
            if (empty && startFromHead != null && !head.equals(startFromHead))
                throw new IllegalArgumentException("An empty ready reports its start " + startFromHead + ", not " + head);
        }

        public RunWorkReady(String runId, dev.codespire.contract.work.WorkRunBinding work, String head,
                            List<String> changedPaths, Map<String, Long> tokenUsage, long activeWallSeconds) {
            this(runId, work, head, changedPaths, tokenUsage, activeWallSeconds, null, null, false, List.of());
        }

        /** Rebuild carrying the agent's step summary, enumerating the components once. */
        public RunWorkReady withStepSummary(dev.codespire.contract.work.StepSummary summary) {
            return new RunWorkReady(runId, work, head, changedPaths, tokenUsage, activeWallSeconds, summary, startFromHead, empty, egress);
        }

        /** Rebuild carrying the head the build started from, enumerating the components once. */
        public RunWorkReady withStartFromHead(String start) {
            return new RunWorkReady(runId, work, head, changedPaths, tokenUsage, activeWallSeconds, stepSummary, start, empty, egress);
        }

        /** Rebuild saying whether the build committed nothing, enumerating the components once. */
        public RunWorkReady withEmpty(boolean nothingCommitted) {
            return new RunWorkReady(runId, work, head, changedPaths, tokenUsage, activeWallSeconds, stepSummary, startFromHead, nothingCommitted, egress);
        }

        /** Rebuild carrying what the agent asked its egress proxy for, enumerating the components once. */
        public RunWorkReady withEgress(List<EgressAttempt> attempts) {
            return new RunWorkReady(runId, work, head, changedPaths, tokenUsage, activeWallSeconds, stepSummary, startFromHead, empty, attempts);
        }
    }

    /**
     * One path the push gate refused, and what the run did to it.
     *
     * <p><b>The kind is the point.</b> "ci.yml was blocked" does not tell an operator whether the
     * factory edited that workflow or deleted it, and after M2 dispatches agents at real findings
     * that distinction is the difference between a bad fix and an attempt to remove what reviews
     * the fix. The publisher has always emitted it, three modules argued in their javadocs for
     * carrying it, and the worker parsed it out of the JSON and threw it away.
     *
     * <p><b>{@code kind} is a String, not an enum, and that is deliberate.</b> The enum is
     * {@code ChangeKind} in {@code spire-workspace}, and this module depends on nothing — it is the
     * base every service builds on. Mirroring the enum here would create two closed sets to keep in
     * step, which is the drift {@code RunFailureCause} exists to prevent rather than to repeat. The
     * value is a string on the wire either way, since the publisher writes JSON.
     *
     * <p>Null means the producer did not say. That is a real state: a row written before this
     * change carries a path and no kind, and inventing one would be worse than admitting it.
     */
    record BlockedChange(String path, String kind) {

        public BlockedChange {
            Objects.requireNonNull(path, "path");
        }
    }

    /**
     * One host the agent asked its egress proxy for, and whether the proxy let it through.
     *
     * <p>Hosts and ports only: the proxy's log is agent-controlled text, and a corporate proxy's credentials must
     * never ride a result. {@code allowed} is false only where the proxy refused, which happens in enforce mode for a
     * host the harness did not declare.
     *
     * <p>Every result carries the list, empty when the unit had no proxy or the agent asked for nothing. A result
     * written before the field existed reads as empty, not as a failure: an old worker had no proxy to report on.
     */
    record EgressAttempt(String host, int port, boolean allowed) {

        public EgressAttempt {
            if (host == null || host.isBlank()) throw new IllegalArgumentException("An egress attempt names its host");
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Not a port: " + port);
        }
    }

    private static List<EgressAttempt> egressOrNone(List<EgressAttempt> egress) {
        return egress == null ? List.of() : List.copyOf(egress);
    }

    /**
     * A finished run.
     *
     * <p>{@code pushedRef} is null when the gate refused, and {@code blocked} then names why —
     * each path with what the run did to it.
     *
     * <p><b>{@code changedPaths} deliberately carries no kind.</b> Nothing reads it but a count in
     * one log line: it is not stored, not projected and not shown. {@code blocked} is the operator
     * surface, so it is the one that had to grow. Giving both the richer type would be symmetry
     * for its own sake, and a wire field nothing reads is a field that rots.
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
     * <p><b>{@code blocked} was {@code List<String> blockedPaths}, and that rename is NOT</b>
     * <b>backward-compatible — which is fine here, for a reason worth stating.</b> The snapshot gate
     * fired on it, correctly: renaming or retyping a component normally means bumping
     * {@code eventVersion} and writing an upcaster (ADR-013), because persisted events replay
     * through these types. {@code RunResult} is not one of those. It is a bus type under ADR-014
     * short retention, never written to the event store and never replayed — the same argument
     * {@code agentUnobserved} was appended under, made explicit rather than inherited.
     *
     * <p>What it DOES cost is a rolling upgrade: a refused-run result produced by an old worker and
     * read by a new orchestrator has no {@code blocked} field, so it fails to deserialize and the
     * never-throwing deserializer routes it to {@code cs.dlq}, where it is visible and replayable
     * once both sides are new. That is the right failure. Defaulting the field to empty instead
     * would make {@link #refused()} answer false for a run the gate DID refuse, and the row would
     * be written as "delivered nothing" — a silent lie in place of a loud stop.
     *
     * <p>There is deliberately <b>no shorter constructor</b>. Adding a component to a wire record
     * silently drops it at every rebuild site while the convenience constructors stay valid — the
     * trap this repository paid for on {@code ReviewResult}. Rebuild through {@link
     * #withAgentUnobserved(boolean)}, which enumerates the components once, next to the record.
     */
    record RunFinished(String runId, String pushedRef, List<String> changedPaths,
                       List<BlockedChange> blocked, Map<String, Long> tokenUsage,
                       boolean agentUnobserved, List<EgressAttempt> egress) implements RunResult {

        public RunFinished {
            Objects.requireNonNull(runId, "runId");
            egress = egressOrNone(egress);
            changedPaths = List.copyOf(Objects.requireNonNull(changedPaths, "changedPaths"));
            blocked = List.copyOf(Objects.requireNonNull(blocked, "blocked"));
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
            if (pushedRef != null && !blocked.isEmpty()) {
                throw new IllegalArgumentException(
                        "a run cannot have both pushed and been refused: " + pushedRef + " / " + blocked);
            }
        }

        /** Whether the harness reported usage at all. Never infer this from a count. */
        public boolean usageIsKnown() {
            return tokenUsage != null;
        }

        /** Whether the push gate refused this run. */
        public boolean refused() {
            return pushedRef == null && !blocked.isEmpty();
        }

        /** Whether this run both delivered work and left the agent's own outcome unobserved. */
        public boolean deliveredUnfinished() {
            return agentUnobserved && pushedRef != null;
        }

        /** Rebuild carrying a different observation flag, enumerating the components once. */
        public RunFinished withAgentUnobserved(boolean unobserved) {
            return new RunFinished(runId, pushedRef, changedPaths, blocked, tokenUsage, unobserved, egress);
        }

        /** Rebuild carrying what the agent asked its egress proxy for, enumerating the components once. */
        public RunFinished withEgress(List<EgressAttempt> attempts) {
            return new RunFinished(runId, pushedRef, changedPaths, blocked, tokenUsage, agentUnobserved, attempts);
        }
    }

    /**
     * A run that did not deliver.
     *
     * <p><b>{@code tokenUsage} is nullable, and null IS unknown</b> — the same contract as
     * {@link RunFinished}, and it is here for a reason that is easy to miss: a failure is not a
     * free outcome. An agent can work for an hour and then have its push rejected, so the tokens
     * were bought whatever the run's verdict. Without this the deployment's rolling spend window
     * was blind to exactly the runs most likely to be run again — under-counted precisely where
     * it is about to be charged a second time.
     *
     * <p>It is genuinely null for a failure raised before anything ran (a command refused at
     * validation), which is why the field cannot be made required: "spent nothing" and "spent an
     * amount nobody measured" are different facts and the ledger prices them differently. The
     * ledger tells them apart by the CAUSE rather than by this field, since a post-agent failure
     * with unmeasured usage must still be charged — see {@code RunFailureCause.agentMayHaveSpent}.
     *
     * <p>Rebuild through {@link #withUsage(java.util.Map)} rather than a shorter constructor —
     * adding a component to a wire record silently drops it at every rebuild site while a
     * convenience constructor stays valid.
     */
    record RunFailed(String runId, String cause, String detail, boolean retryable,
                     Map<String, Long> tokenUsage, List<EgressAttempt> egress) implements RunResult {

        public RunFailed {
            Objects.requireNonNull(runId, "runId");
            egress = egressOrNone(egress);
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
            return new RunFailed(runId, cause, detail, retryable, usage, egress);
        }

        /** Rebuild carrying what the agent asked its egress proxy for, enumerating the components once. */
        public RunFailed withEgress(List<EgressAttempt> attempts) {
            return new RunFailed(runId, cause, detail, retryable, tokenUsage, attempts);
        }
    }
}
