package dev.codespire.agentimage;

import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.model.ContainerConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The clause LOGIC, against a fake probe.
 *
 * <p>What a fake can prove is the mapping: that a "no git" answer becomes a failure naming git, and
 * that a root user becomes a failure naming the user. What it cannot prove is that the question was
 * asked correctly of a real image — {@code ReferenceImageIT} is that half, and neither substitutes
 * for the other. This file is the one that runs in the fast tier.
 */
class AgentImageVerifierTest {

    /**
     * A probe answering whatever a case needs.
     *
     * <p>Every method is answered, including ones a given case does not use. A double that answers
     * only today's call is the trap this repository has recorded six times; here an unanswered
     * {@code runAgent} would fail three clauses with an NPE reported as an image problem.
     */
    private record FakeProbe(ContainerConfig config, String runOutput, ImageProbe.Result agentResult)
            implements ImageProbe {

        @Override
        public InspectImageResponse inspect(String image) {
            return inspectionOf(config);
        }

        @Override
        public ImageProbe.Result run(String image, List<String> argv) {
            return ImageProbe.Result.of(runOutput);
        }

        @Override
        public ImageProbe.Result runAgent(String image, List<String> harnessArgv, String prompt) {
            return agentResult;
        }
    }

    /**
     * docker-java's inspect response has no public constructor for its fields.
     *
     * <p>Reflection rather than a wrapper interface, deliberately: wrapping it would put a type of
     * our own between the checker and the library, and the thing most likely to be wrong here is
     * our belief about what the library returns — which a wrapper would hide behind our own shape.
     */
    private static InspectImageResponse inspectionOf(ContainerConfig config) {
        try {
            InspectImageResponse response = new InspectImageResponse();
            Field field = InspectImageResponse.class.getDeclaredField("config");
            field.setAccessible(true);
            field.set(response, config);
            return response;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("docker-java's InspectImageResponse changed shape", e);
        }
    }

    private static ContainerConfig configWith(String user, String[] entrypoint, Map<String, String> labels) {
        try {
            ContainerConfig config = new ContainerConfig();
            set(config, "user", user);
            set(config, "entrypoint", entrypoint);
            set(config, "labels", labels);
            return config;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("docker-java's ContainerConfig changed shape", e);
        }
    }

    private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final String ALL_GOOD =
            "git=yes\nca=yes\nworkspace=yes\nhandoff=yes\n";

    private static ImageProbe.Result conformingAgentRun() {
        return new ImageProbe.Result("stdin=yes\n", true, List.of("1.bundle", "DONE"),
                ImageProbe.Done.WRITTEN_LAST);
    }

    private static ConformanceReport verify(ContainerConfig config, String runOutput,
                                            ImageProbe.Result agent) {
        return new AgentImageVerifier(new FakeProbe(config, runOutput, agent)).verify("acme/agent:1");
    }

    private static ConformanceReport.Verification clause(ConformanceReport report, String id) {
        return report.verified().stream()
                .filter(verification -> verification.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no clause " + id + " in the report"));
    }

    /** The reference image is its own first test: a conforming image passes every clause. */
    @Test
    void aConformingImagePassesEveryVerifiedClause() {
        ConformanceReport report = verify(
                configWith("1001:1001", new String[] {"/usr/local/bin/spire-agent-entrypoint"},
                        Map.of(Clauses.TOOLCHAIN_LABEL, "node", Clauses.HARNESS_LABEL, "codex")),
                ALL_GOOD, conformingAgentRun());

        assertTrue(report.conforms(), report.render());
        assertEquals(Clauses.VERIFIED,
                report.verified().stream().map(ConformanceReport.Verification::id).toList(),
                "every documented verified clause must appear, in the documented order");
    }

    /** Not "verification failed" — the clause, and what to change. */
    @Test
    void aMissingEntrypointFailsNamingTheClause() {
        ConformanceReport report = verify(configWith("1001", null, Map.of()),
                ALL_GOOD, conformingAgentRun());

        ConformanceReport.Verification entrypoint = clause(report, Clauses.ENTRYPOINT);
        assertFalse(entrypoint.passed());
        assertTrue(entrypoint.detail().contains("ENTRYPOINT"), entrypoint.detail());
        assertFalse(report.conforms());
    }

    /**
     * The uid rule M0 learned the hard way: a fresh volume inherits the mount point's ownership, so
     * root-owned directories mean the agent cannot write its own workspace — and the symptom is a
     * run that clones correctly and then does nothing.
     */
    @Test
    void wrongMountOwnershipFailsNamingTheClause() {
        ConformanceReport report = verify(
                configWith("1001", new String[] {"/entrypoint"}, Map.of()),
                "git=yes\nca=yes\nworkspace=no\nhandoff=yes\n", conformingAgentRun());

        ConformanceReport.Verification mounts = clause(report, Clauses.MOUNT_POINTS);
        assertFalse(mounts.passed());
        assertTrue(mounts.detail().contains("/workspace"), mounts.detail());
        assertFalse(mounts.detail().contains("/handoff"),
                "only the directory that is actually wrong, or the operator fixes the wrong one");
    }

    /** An absent USER is root, and that is the spelling an author does not notice. */
    @Test
    void anUnsetUserIsTreatedAsRoot() {
        ConformanceReport report = verify(
                configWith(null, new String[] {"/entrypoint"}, Map.of()), ALL_GOOD, conformingAgentRun());

        assertFalse(clause(report, Clauses.NON_ROOT).passed());
        assertTrue(clause(report, Clauses.NON_ROOT).detail().contains("<unset>"));
    }

    /** A prompt on argv is printed by docker inspect and by the host process list. */
    @Test
    void aPromptNotSeenOnStdinFailsTheClause() {
        ConformanceReport report = verify(
                configWith("1001", new String[] {"/entrypoint"}, Map.of()), ALL_GOOD,
                new ImageProbe.Result("stdin=no\n", true, List.of("1.bundle", "DONE"),
                        ImageProbe.Done.WRITTEN_LAST));

        assertFalse(clause(report, Clauses.PROMPT_ON_STDIN).passed());
        assertTrue(clause(report, Clauses.PROMPT_ON_STDIN).detail().contains("argv"));
    }

    /** No bundle means the work never left the container, whatever the harness did. */
    @Test
    void noBundleOnHandoffFailsTheClause() {
        ConformanceReport report = verify(
                configWith("1001", new String[] {"/entrypoint"}, Map.of()), ALL_GOOD,
                new ImageProbe.Result("stdin=yes\n", true, List.of("DONE"),
                        ImageProbe.Done.WRITTEN_LAST));

        assertFalse(clause(report, Clauses.HANDOFF_BUNDLES).passed());
    }

    /** DONE early means the publisher drains before the final bundle is written. */
    @Test
    void doneWrittenBeforeTheLastBundleFailsTheClause() {
        ConformanceReport report = verify(
                configWith("1001", new String[] {"/entrypoint"}, Map.of()), ALL_GOOD,
                new ImageProbe.Result("stdin=yes\n", true, List.of("DONE", "1.bundle"),
                        ImageProbe.Done.BUNDLE_AFTER_DONE));

        assertFalse(clause(report, Clauses.HANDOFF_DONE_LAST).passed());
        assertTrue(clause(report, Clauses.HANDOFF_DONE_LAST).detail().contains("publisher"));
    }

    /**
     * A declaration is read from the label and never verified, whatever the image claims.
     *
     * <p>Including the case that matters: an image declaring a toolchain it does not have is
     * reported as having SAID so, and the reason it was not checked travels with it.
     */
    @Test
    void aDeclarationReportsWhatTheImageSaidAndWhyItWasNotChecked() {
        ConformanceReport report = verify(
                configWith("1001", new String[] {"/entrypoint"},
                        Map.of(Clauses.TOOLCHAIN_LABEL, "a-toolchain-this-image-does-not-have")),
                ALL_GOOD, conformingAgentRun());

        ConformanceReport.Declaration toolchain = report.declared().stream()
                .filter(declaration -> declaration.id().equals(Clauses.TOOLCHAIN))
                .findFirst().orElseThrow();

        assertEquals("a-toolchain-this-image-does-not-have", toolchain.claimed());
        assertTrue(toolchain.whyNotVerifiable().contains("repository"), toolchain.whyNotVerifiable());
        assertTrue(report.conforms(), "a false claim in a label cannot fail conformance — nothing "
                + "checked it, and pretending otherwise is the blend the report shape prevents");
    }

    /**
     * An unreachable daemon is a CHECKER problem reported as such.
     *
     * <p>Silently omitting the clauses would make the report read as a shorter contract, and
     * reporting them as ordinary failures would send an operator to fix a good image.
     */
    @Test
    void anUnreachableProbeIsReportedAsNotCheckedRatherThanAsAFailingImage() {
        ImageProbe broken = new ImageProbe() {
            @Override
            public InspectImageResponse inspect(String image) {
                return inspectionOf(configWith("1001", new String[] {"/entrypoint"}, Map.of()));
            }

            @Override
            public ImageProbe.Result run(String image, List<String> argv) {
                throw new IllegalStateException("daemon unreachable");
            }

            @Override
            public ImageProbe.Result runAgent(String image, List<String> harnessArgv, String prompt) {
                throw new IllegalStateException("daemon unreachable");
            }
        };

        ConformanceReport report = new AgentImageVerifier(broken).verify("acme/agent:1");

        assertFalse(report.conforms());
        assertTrue(clause(report, Clauses.GIT).detail().contains("NOT CHECKED"));
        assertTrue(clause(report, Clauses.GIT).detail().contains("not necessarily an image one"));
        assertTrue(clause(report, Clauses.ENTRYPOINT).passed(),
                "the clauses that need no container are still answered");
    }
    /**
     * Root is uid 0 however the image spells it.
     *
     * <p>{@code USER root:root} is a documented Dockerfile form and an earlier version matched
     * none of its branches, so an image running as uid 0 was reported as CONFORMING — on the one
     * clause whose whole purpose is that this container runs untrusted model output at full shell
     * access. Enumerated rather than sampled, because the defect was a spelling nobody tried.
     */
    @Test
    void everySpellingOfRootIsCaught() {
        for (String user : java.util.Arrays.asList("root", "root:root", "root:0", "root:agent",
                "0", "0:0", "0:agent", "", "  ", null)) {
            ConformanceReport report = verify(
                    configWith(user, new String[] {"/entrypoint"}, Map.of()),
                    ALL_GOOD, conformingAgentRun());

            assertFalse(clause(report, Clauses.NON_ROOT).passed(),
                    "USER=" + user + " is uid 0: " + clause(report, Clauses.NON_ROOT).detail());
        }
    }

    /** And a real non-root user passes, in both spellings. */
    @Test
    void aNonRootUserPassesInEitherSpelling() {
        for (String user : java.util.List.of("1001", "1001:1001", "agent", "agent:agent")) {
            ConformanceReport report = verify(
                    configWith(user, new String[] {"/entrypoint"}, Map.of()),
                    ALL_GOOD, conformingAgentRun());

            assertTrue(clause(report, Clauses.NON_ROOT).passed(), user);
        }
    }

    /** An empty ENTRYPOINT array is no entrypoint, and Docker stores it as one. */
    @Test
    void anEmptyEntrypointArrayFailsTheClause() {
        ConformanceReport report = verify(configWith("1001", new String[0], Map.of()),
                ALL_GOOD, conformingAgentRun());

        assertFalse(clause(report, Clauses.ENTRYPOINT).passed());
    }

    /** The git clause had no failing case at all: it could have been nailed open. */
    @Test
    void aMissingGitBinaryFailsTheClause() {
        ConformanceReport report = verify(
                configWith("1001", new String[] {"/entrypoint"}, Map.of()),
                "git=no\nca=yes\nworkspace=yes\nhandoff=yes\n", conformingAgentRun());

        assertFalse(clause(report, Clauses.GIT).passed());
        assertTrue(clause(report, Clauses.GIT).detail().contains("bundles"));
    }

    /** A trust store missing on its own, which the isolated-break IT could not distinguish. */
    @Test
    void aMissingTrustStoreFailsOnlyThatClause() {
        ConformanceReport report = verify(
                configWith("1001", new String[] {"/entrypoint"}, Map.of()),
                "git=yes\nca=no\nworkspace=yes\nhandoff=yes\n", conformingAgentRun());

        assertEquals(java.util.List.of(Clauses.CA_CERTIFICATES),
                report.failures().stream().map(ConformanceReport.Verification::id).toList());
    }

    /** The handoff half of the mount-point clause, which only the workspace half was tested for. */
    @Test
    void aHandoffThatIsNotWritableFailsAndNamesOnlyIt() {
        ConformanceReport report = verify(
                configWith("1001", new String[] {"/entrypoint"}, Map.of()),
                "git=yes\nca=yes\nworkspace=yes\nhandoff=no\n", conformingAgentRun());

        assertFalse(clause(report, Clauses.MOUNT_POINTS).passed());
        assertTrue(clause(report, Clauses.MOUNT_POINTS).detail().contains("/handoff"));
        assertFalse(clause(report, Clauses.MOUNT_POINTS).detail().contains("/workspace"),
                "only the directory that is wrong, or the operator fixes the wrong one");
    }

    /** A probe that answered nothing is NOT CHECKED, not "missing or not writable". */
    @Test
    void aProbeThatAnsweredNothingReportsNotCheckedRatherThanAccusingTheImage() {
        ConformanceReport report = verify(
                configWith("1001", new String[] {"/entrypoint"}, Map.of()),
                "", conformingAgentRun());

        for (String id : java.util.List.of(Clauses.MOUNT_POINTS, Clauses.GIT, Clauses.CA_CERTIFICATES)) {
            assertTrue(clause(report, id).notChecked(), id + ": " + clause(report, id).detail());
        }
    }

    /**
     * The entrypoint never reached the harness: three clauses NOT CHECKED, not three accusations.
     *
     * <p>This is the case a review reproduced on the REAL reference image, using the runbook's own
     * commands. A checker-side setup failure produced three specific defects the image did not
     * have, and nothing covered the path.
     */
    @Test
    void anAgentThatNeverStartedReportsNotCheckedRatherThanThreeDefects() {
        ConformanceReport report = verify(
                configWith("1001", new String[] {"/entrypoint"}, Map.of()), ALL_GOOD,
                ImageProbe.Result.neverStarted("could not create a probe repository"));

        for (String id : java.util.List.of(Clauses.PROMPT_ON_STDIN, Clauses.HANDOFF_BUNDLES,
                Clauses.HANDOFF_DONE_LAST)) {
            assertTrue(clause(report, id).notChecked(), id + ": " + clause(report, id).detail());
        }
        assertTrue(clause(report, Clauses.GIT).passed(),
                "the clauses that DID get an answer keep it");
    }

    /** A throwing agent probe is the same answer as one that never started. */
    @Test
    void anAgentProbeThatThrowsReportsNotChecked() {
        ImageProbe throwingAgent = new ImageProbe() {
            @Override
            public com.github.dockerjava.api.command.InspectImageResponse inspect(String image) {
                return inspectionOf(configWith("1001", new String[] {"/entrypoint"}, Map.of()));
            }

            @Override
            public Result run(String image, java.util.List<String> argv) {
                return Result.of(ALL_GOOD);
            }

            @Override
            public Result runAgent(String image, java.util.List<String> argv, String prompt) {
                throw new IllegalStateException("the probe did not exit within 300s");
            }
        };

        ConformanceReport report = new AgentImageVerifier(throwingAgent).verify("acme/agent:1");

        assertTrue(clause(report, Clauses.HANDOFF_BUNDLES).notChecked());
        assertTrue(clause(report, Clauses.HANDOFF_BUNDLES).detail().contains("300s"),
                "the reason travels, or the operator cannot tell a hang from a busy daemon");
    }

    /** DONE never written and DONE written early fail the same clause and read differently. */
    @Test
    void aMissingDoneIsReportedAsMissingRatherThanAsWrittenEarly() {
        ConformanceReport neverWritten = verify(
                configWith("1001", new String[] {"/entrypoint"}, Map.of()), ALL_GOOD,
                new ImageProbe.Result("stdin=yes\n", true, java.util.List.of("1.bundle"),
                        ImageProbe.Done.NEVER_WRITTEN));

        assertFalse(clause(neverWritten, Clauses.HANDOFF_DONE_LAST).passed());
        assertTrue(clause(neverWritten, Clauses.HANDOFF_DONE_LAST).detail().contains("never written"),
                clause(neverWritten, Clauses.HANDOFF_DONE_LAST).detail());
    }

    /** Every clause the daemon could not reach, derived rather than listed by hand. */
    @Test
    void anUnreachableProbeReportsEveryRuntimeClauseNotOnlyTheOnesSomebodyListed() {
        ImageProbe broken = new ImageProbe() {
            @Override
            public com.github.dockerjava.api.command.InspectImageResponse inspect(String image) {
                return inspectionOf(configWith("1001", new String[] {"/entrypoint"}, Map.of()));
            }

            @Override
            public Result run(String image, java.util.List<String> argv) {
                throw new IllegalStateException("daemon unreachable");
            }

            @Override
            public Result runAgent(String image, java.util.List<String> argv, String prompt) {
                throw new IllegalStateException("daemon unreachable");
            }
        };

        ConformanceReport report = new AgentImageVerifier(broken).verify("acme/agent:1");

        assertEquals(Clauses.VERIFIED,
                report.verified().stream().map(ConformanceReport.Verification::id).toList(),
                "a clause silently omitted reads as a shorter contract");
    }
}