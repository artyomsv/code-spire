package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.work.WorkPublicationPermit;
import dev.codespire.runtime.ContainerSpec;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What each publisher command is handed, kept apart from {@link RunUnitBuilder} so the unit's shape and the
 * publisher's authority read separately. The write credential appears in exactly one place below, and the
 * read-only publisher is the one that never reaches it.
 */
final class PublisherEnvironments {

    /** The executable a specification or plan run's publisher starts (spire-publisher's ReadOnlyPublisherMain). */
    static final String READ_ONLY_COMMAND = "spire-publish-none";

    private PublisherEnvironments() {
    }

    /**
     * A specification or plan run's publisher (M4 slice 3): the same image and the same read-only handoff mount,
     * {@code spire-publish-none}, and the handoff path alone. No remote, branch or write credential: it cannot push
     * even if its code were wrong. Its size caps are the publisher's own constants, so nothing else is passed.
     */
    static ContainerSpec readOnly(ContainerSpec publisher) {
        return new ContainerSpec(publisher.image(), List.of(READ_ONLY_COMMAND),
                Map.of("SPIRE_HANDOFF_DIR", "/handoff"), publisher.mounts());
    }

    /**
     * What the publisher is told, including where ADR-040 lets it push.
     *
     * <p>A {@code HashMap} rather than {@code Map.of}, because the two branch-mode variables are
     * CONDITIONAL and {@code Map.of} cannot express that. Writing them unconditionally would be
     * worse than verbose: {@code SPIRE_PROTECTED_BRANCH} set to an empty string reads to the
     * publisher as "present but blank", which its own required-in-existing-mode check treats
     * exactly as absent — so the distinction would survive here and be lost there.
     *
     * <p>The mode is written only when it is {@code existing}. An absent variable is the M0 rule,
     * which is the safe direction and the one every run before ADR-040 used.
     */
    static Map<String, String> publisherEnvironment(RunCommand.ExecuteRun command, Credentials.Scm scm) {
        Map<String, String> env = new HashMap<>();
        env.put("SPIRE_REMOTE_URI", command.remoteUri());
        env.put("SPIRE_BRANCH", command.branch());
        env.put("SPIRE_BRANCH_BASE", command.baseBranch());
        env.put("SPIRE_BASE_COMMIT", command.baseCommit());
        env.put("SPIRE_PROTECTED_PATHS", String.join(",", command.protectedPaths()));
        env.put("SPIRE_BUNDLE_MAX_BYTES", Long.toString(RunUnitBuilder.BUNDLE_MAX_BYTES));
        env.put("SPIRE_GIT_USERNAME", scm.writeUsername());
        env.put("SPIRE_GIT_SECRET", scm.writeSecret());
        if (command.pushesToAnExistingBranch()) {
            env.put("SPIRE_BRANCH_MODE", "existing");
        }
        // Written whenever it is known, NOT only in existing mode. The publisher honours it in
        // every mode, so gating it on the mode meant a pull request targeting a factory branch
        // (spire/x as a DESTINATION) went unprotected in the default mode — a floor dropped by the
        // caller rather than by the floor.
        if (!command.protectedBranch().isBlank()) {
            env.put("SPIRE_PROTECTED_BRANCH", command.protectedBranch());
        }
        return Map.copyOf(env);
    }

    /**
     * What the held publisher is told beside the ordinary environment. A land fix's merged base tip: the held gate sees the
     * base merge before any permit exists, so it excuses the same orchestrator-issued tip the permit will; without it a base
     * that edited a workflow is refused before any checkpoint (M4 slice 7a). A build that starts from a checkpoint names its
     * head (M4 slice 3, A2), and the publisher refuses a checkpoint that does not descend from it. A plan step may finish
     * with no commit (decision 14), and only its publisher is told so: every other held build keeps today's missing
     * checkpoint, which fails the run. The agent receives none of them.
     */
    static ContainerSpec held(ContainerSpec publisher, RunCommand.ExecuteWorkRun command) {
        String start = command.execution().startFromHead();
        if (command.mergedBaseTip() == null && start == null && !command.emptyAllowed()) return publisher;
        Map<String, String> env = new HashMap<>(publisher.environment());
        if (command.mergedBaseTip() != null) env.put("SPIRE_MERGED_BASE_TIP", command.mergedBaseTip());
        if (start != null) env.put("SPIRE_START_HEAD", start);
        if (command.emptyAllowed()) env.put("SPIRE_EMPTY_ALLOWED", "1");
        return new ContainerSpec(publisher.image(), publisher.argv(), Map.copyOf(env), publisher.mounts());
    }

    /**
     * The land-fix half of a permit (M4 slice 7a). An existing-branch run publishes only with an expected remote
     * head, and only the head it was built from: the run cloned that commit, so any other expectation would
     * approve a push over work the run never saw. A new-branch run has no remote head to expect.
     */
    static Map<String, String> existingBranchEnvironment(RunCommand.ExecuteWorkRun original,
                                                         WorkPublicationPermit permit) {
        RunCommand.ExecuteRun execution = original.execution();
        if (execution.pushesToAnExistingBranch() != permit.pushesToExistingBranch()) {
            throw new IllegalArgumentException("A push to an existing branch needs an expected remote head, and only "
                    + "an existing-branch run may carry one");
        }
        if (!permit.pushesToExistingBranch()) return Map.of();
        if (!execution.baseCommit().equals(permit.expectedRemoteHead())) {
            throw new IllegalArgumentException("The permit expects " + permit.expectedRemoteHead()
                    + " but the run was built from " + execution.baseCommit());
        }
        // The checkpoint was gated with the held build's tip; another tip would widen or narrow the exclusion
        // after the fact.
        if (!Objects.equals(original.mergedBaseTip(), permit.mergedBaseTip())) {
            throw new IllegalArgumentException("The permit excuses the base tip " + permit.mergedBaseTip()
                    + " but the held build was gated with " + original.mergedBaseTip());
        }
        Map<String, String> env = new HashMap<>();
        env.put("SPIRE_EXPECTED_REMOTE_HEAD", permit.expectedRemoteHead());
        if (permit.mergedBaseTip() != null) env.put("SPIRE_MERGED_BASE_TIP", permit.mergedBaseTip());
        return env;
    }
}
