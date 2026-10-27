package dev.codespire.agentimage;

import java.util.List;

/**
 * The {@code handoff-files} clause (M4 slice 3): {@code $SPIRE_ARTIFACT} and {@code $SPIRE_STEP_SUMMARY} reach the
 * handoff as regular files, and neither is newer than {@code DONE}.
 *
 * <p>It rides on the same agent run as the other handoff clauses rather than starting a container of its own: the
 * stub harness also writes both files, and {@link DockerImageProbe} reports which of them sit on the handoff as
 * regular files no newer than DONE. Presence alone is not the clause, because the publisher reads the files when it
 * sees DONE: a file copied after DONE is one it may never read.
 */
final class HandoffFilesProbe {

    static final String ARTIFACT = "artifact.md";

    static final String STEP_SUMMARY = "step-summary.json";

    /** The handoff names the entrypoint copies the two files to, in report order. */
    static final List<String> FILES = List.of(ARTIFACT, STEP_SUMMARY);

    private HandoffFilesProbe() {
    }

    /**
     * Harness commands that write both files where the entrypoint says. Guarded, so an image that does not export
     * the variables gets no shell error and simply fails the clause.
     */
    static String harnessCommands() {
        return "[ -n \"${SPIRE_ARTIFACT:-}\" ] && printf conformance > \"$SPIRE_ARTIFACT\"; "
                + "[ -n \"${SPIRE_STEP_SUMMARY:-}\" ] && printf '{}' > \"$SPIRE_STEP_SUMMARY\"";
    }

    static ConformanceReport.Verification clause(ImageProbe.Result result) {
        List<String> missing = FILES.stream().filter(name -> !result.handoff().contains(name)).toList();
        if (!missing.isEmpty()) {
            return ConformanceReport.Verification.failed(Clauses.HANDOFF_FILES, String.join(" and ", missing)
                    + " never reached " + AgentImageVerifier.HANDOFF + ": the entrypoint must export $SPIRE_ARTIFACT "
                    + "and $SPIRE_STEP_SUMMARY and copy what the harness wrote there before DONE, or a specification "
                    + "run returns nothing and a plan step's decisions are lost");
        }
        List<String> late = FILES.stream().filter(name -> !result.filesBeforeDone().contains(name)).toList();
        if (!late.isEmpty()) {
            return ConformanceReport.Verification.failed(Clauses.HANDOFF_FILES, String.join(" and ", late)
                    + " on " + AgentImageVerifier.HANDOFF + " is newer than DONE, not a regular file, or there is no "
                    + "DONE; the publisher reads the files when DONE appears, so a later or linked file is never read");
        }
        return ConformanceReport.Verification.passed(Clauses.HANDOFF_FILES,
                "both handoff files were regular files on " + AgentImageVerifier.HANDOFF + " before DONE");
    }
}
