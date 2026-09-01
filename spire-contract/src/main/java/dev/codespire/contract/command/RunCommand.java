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
        @JsonSubTypes.Type(value = RunCommand.CancelRun.class, name = "CancelRun")
})
public sealed interface RunCommand {

    String runId();

    /**
     * Opaque, KEK-encrypted machine-account SCM credential (ADR-037) — never the review bot's.
     * Base64 Tink ciphertext, packed by the orchestrator. Never logged.
     */
    default String scmCredential() {
        return null;
    }

    /** Opaque, KEK-encrypted harness credential (ADR-030). Never logged. */
    default String harnessCredential() {
        return null;
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
}
