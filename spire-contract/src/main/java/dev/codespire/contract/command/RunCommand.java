package dev.codespire.contract.command;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import dev.codespire.contract.scm.RepoRef;

import java.util.List;
import java.util.Objects;

/**
 * Run dispatch. A SEPARATE hierarchy from {@code ActionCommand}, which declares {@code reviewId()}
 * as mandatory — a run has a {@code runId}, and a run id behind a method named {@code reviewId()}
 * is a name that lies. Rides {@code cs.run-commands}, keyed by {@code runId}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = RunCommand.ExecuteRun.class, name = "ExecuteRun"),
        @JsonSubTypes.Type(value = RunCommand.CancelRun.class, name = "CancelRun"),
        @JsonSubTypes.Type(value = RunCommand.SteerRun.class, name = "SteerRun")
})
public sealed interface RunCommand {

    String runId();

    /**
     * Opaque, KEK-encrypted machine-account SCM credential (ADR-038) — never the review bot's.
     * Base64 Tink ciphertext, packed by the orchestrator. Never logged.
     */
    default String scmCredential() {
        return null;
    }

    /** Opaque, KEK-encrypted harness credential (ADR-031). Never logged. */
    default String harnessCredential() {
        return null;
    }

    /**
     * The AAD both credentials are bound to: the run they were packed for, and which of the two
     * they are. Bound to the run rather than the workspace (the review path's choice) because a run
     * is the unit of dispatch here — a ciphertext lifted from one command cannot be replayed on
     * another run's command even within the same workspace. The two slots differ so an SCM token
     * cannot be presented where a harness key is expected.
     */
    static String scmCredentialAad(String runId) {
        return "run-scm:" + Objects.requireNonNull(runId, "runId");
    }

    static String harnessCredentialAad(String runId) {
        return "run-harness:" + Objects.requireNonNull(runId, "runId");
    }

    /**
     * One agent run.
     *
     * <p><b>Its {@code toString} redacts both credentials.</b> A record prints every component, and
     * {@code log.info("dispatching {}", command)} is the obvious line to write — the same leak
     * already closed on {@code HarnessInvocation} and {@code ContainerSpec}. These two are Tink
     * ciphertext rather than plaintext, so a leak is not immediately usable, but a ciphertext in a
     * log is still a credential in a log: it survives key rotation, it is attacker-collectable, and
     * the whole point of the KEK boundary is that the ciphertext never leaves the paths that need it.
     */
    record ExecuteRun(String runId, RepoRef repo, String remoteUri,
                      String baseBranch, String baseCommit, String branch,
                      String prompt, String harness, String model, String agentImage,
                      List<String> protectedPaths, long maxWallClockSeconds,
                      String scmCredential, String harnessCredential) implements RunCommand {

        public ExecuteRun {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(repo, "repo");
            // The clone URL cannot be derived from RepoRef: that is (workspace, slug) with no host,
            // and a self-hosted GitLab or Bitbucket DC has its own base URL. The orchestrator knows
            // which provider instance this repository belongs to; this call site does not.
            Objects.requireNonNull(remoteUri, "remoteUri");
            // Two different branches, and conflating them is a real hazard: baseBranch is what the
            // publisher CLONES (the target the work is based on) and branch is what it PUSHES to.
            // One name for both would make the factory push onto the branch it forked from.
            Objects.requireNonNull(baseBranch, "baseBranch");
            // Was the one component with no check: a null surfaced later as an NPE out of Map.of in
            // the unit builder, reported as BAD_COMMAND with a message naming nothing.
            Objects.requireNonNull(baseCommit, "baseCommit");
            Objects.requireNonNull(branch, "branch");
            Objects.requireNonNull(prompt, "prompt");
            protectedPaths = List.copyOf(Objects.requireNonNull(protectedPaths, "protectedPaths"));
            if (maxWallClockSeconds <= 0) {
                throw new IllegalArgumentException(
                        "a run needs a wall clock; unlimited is not a limit: " + maxWallClockSeconds);
            }
        }

        @Override
        public String toString() {
            return "ExecuteRun[runId=" + runId
                    + ", repo=" + repo.full()
                    + ", remoteUri=" + remoteUri
                    + ", baseCommit=" + baseCommit
                    + ", baseBranch=" + baseBranch
                    + ", branch=" + branch
                    + ", harness=" + harness
                    + ", model=" + model
                    + ", agentImage=" + agentImage
                    + ", protectedPaths=" + protectedPaths
                    + ", maxWallClockSeconds=" + maxWallClockSeconds
                    + ", promptChars=" + prompt.length()
                    + ", scmCredential=" + (scmCredential == null ? "absent" : "***")
                    + ", harnessCredential=" + (harnessCredential == null ? "absent" : "***") + "]";
        }
    }

    record CancelRun(String runId, String reason) implements RunCommand {

        public CancelRun {
            Objects.requireNonNull(runId, "runId");
        }
    }

    /**
     * A new instruction for a run that is already going.
     *
     * <p>Capability-gated at the worker: a harness that does not declare {@code steer} must REFUSE
     * this visibly rather than drop it. A silent drop is the shape this project has already been
     * burned by — an operator who steers a run and sees nothing cannot tell "not supported" from
     * "the message was lost", and the second sends them looking for a broker fault that is not there.
     *
     * <p>The instruction is bounded for the same reason the prompt is: it rides every copy of the
     * command, the dead-letter row, and the transcript.
     */
    record SteerRun(String runId, String instruction) implements RunCommand {

        /** The same ceiling the dispatch prompt has, and for the same reasons. */
        public static final int MAX_INSTRUCTION_CHARS = 64 * 1024;

        public SteerRun {
            Objects.requireNonNull(runId, "runId");
            if (instruction == null || instruction.isBlank()) {
                throw new IllegalArgumentException("a steer must carry an instruction; an empty one"
                    + " would reach the agent as a turn that says nothing and cost a model call");
            }
            if (instruction.length() > MAX_INSTRUCTION_CHARS) {
                throw new IllegalArgumentException("a steer instruction of " + instruction.length()
                    + " characters is over the " + MAX_INSTRUCTION_CHARS + " limit; it rides every copy"
                    + " of the command, the dead-letter row and the transcript");
            }
        }
    }
}
