package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.ActionCommand.FetchDiff;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.DiffFetched;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.review.CodeReferences;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.ChangeType;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.DiffLine;
import dev.codespire.contract.scm.FilePatch;
import dev.codespire.contract.scm.Hunk;
import dev.codespire.contract.scm.LineType;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.worker.adapters.WorkerCodeReferences;
import dev.codespire.worker.adapters.WorkerContextReferences;
import dev.codespire.worker.adapters.WorkerScmClients;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffWorkerCodeReferencesTest {

    private static final RepoRef REPO = new RepoRef("sandbox", "demo-repo");
    private static final FetchDiff COMMAND =
            new FetchDiff("review::sandbox/demo-repo#7", REPO, 7, "cafe1234", null);

    private final WorkerCodeReferences refs = new WorkerCodeReferences();

    private static FilePatch patch(String path, String language, DiffLine... lines) {
        return new FilePatch(null, path, ChangeType.MODIFIED, language, false, false,
                List.of(new Hunk(1, 1, 1, lines.length, List.of(lines))));
    }

    /** A DiffSource that hands back the given diff and otherwise plain PR metadata. */
    private static DiffSource fakeDiffSource(Diff diff) {
        return new DiffSource() {
            @Override
            public ScmType type() {
                return ScmType.BITBUCKET_CLOUD;
            }

            @Override
            public String apiHost() {
                return "api.example.invalid";
            }

            @Override
            public PullRequest fetchPullRequest(RepoRef repo, long prId) {
                return new PullRequest(repo, prId, "Demo PR", "desc", "feature/demo", "main",
                        diff.headCommit(), Author.of("1", "bot", "bot"), "http://pr");
            }

            @Override
            public String fetchTextFileOnBranch(RepoRef repo, String branch, String path) {
                return null;
            }

            @Override
            public Diff fetchDiff(RepoRef repo, long prId, String commit) {
                return diff;
            }
        };
    }

    /** Wires a real DiffWorker against the given diff source and code-reference bean, runs
     *  fetchDiff, and returns everything it emitted. */
    private static List<IntegrationEvent> runFetchDiff(DiffSource diffSource, WorkerCodeReferences codeReferences) {
        List<IntegrationEvent> emitted = new ArrayList<>();
        DiffWorker worker = new DiffWorker();
        worker.references = new WorkerContextReferences();
        worker.codeRefs = codeReferences;
        worker.results = new ResultsEmitter() {
            @Override
            public void emit(IntegrationEvent event) {
                emitted.add(event);
            }
        };
        worker.scm = new WorkerScmClients() {
            @Override
            public Clients forCommand(ActionCommand command) {
                return new Clients(diffSource, null);
            }
        };
        worker.fetchDiff(COMMAND);
        return emitted;
    }

    @Test
    void changedPathsAndIdentifiersAreCollectedPerLanguage() {
        Diff diff = new Diff("cafe1234", List.of(
                patch("src/main/java/dev/example/Alpha.java", "java",
                        new DiffLine(LineType.ADDED, null, 1, "pricingHelper.chargeFor(item);")),
                patch("spire-ui/src/Beta.tsx", "typescript",
                        new DiffLine(LineType.ADDED, null, 1, "const c = formatCost(x)"))), false);

        CodeReferences result = refs.inDiff(diff);

        assertEquals(java.util.Set.of("src/main/java/dev/example/Alpha.java", "spire-ui/src/Beta.tsx"),
                result.changedPaths());
        assertTrue(result.identifiers().contains("chargeFor"));
        assertTrue(result.identifiers().contains("formatCost"));
    }

    @Test
    void anUnsupportedLanguageContributesNothingAndDoesNotFail() {
        Diff diff = new Diff("cafe1234", List.of(
                patch("infra/main.tf", "terraform",
                        new DiffLine(LineType.ADDED, null, 1, "resource \"aws_s3_bucket\" \"b\" {}"))), false);

        CodeReferences result = refs.inDiff(diff);

        assertTrue(result.identifiers().isEmpty());
        // isEmpty() is an OR, so an empty identifier set alone satisfies it — assert the path
        // directly too, or a leaked path would pass unnoticed.
        assertFalse(result.changedPaths().contains("infra/main.tf"));
        assertTrue(result.isEmpty());
    }

    @Test
    void aBinaryOrTooLargePatchIsSkipped() {
        FilePatch binary = new FilePatch(null, "logo.png", ChangeType.MODIFIED, "unknown",
                true, false, List.of());
        Diff diff = new Diff("cafe1234", List.of(binary), false);

        assertFalse(refs.inDiff(diff).changedPaths().contains("logo.png"));
    }

    /**
     * Spec §8.3 test 2, guarding the separation {@link CodeReferences}' javadoc exists for: the
     * neutral {@code references} set (mined from PR title/branch/description by
     * {@link WorkerContextReferences}) and {@code codeReferences} (mined from the diff's changed
     * lines by {@link WorkerCodeReferences}) are populated by different extractors reading different
     * inputs, and must never cross-feed. Run against the real {@link DiffWorker}, not a unit test of
     * either composition root alone, so the assertion is about what actually lands on the emitted
     * event.
     */
    @Test
    void codeIdentifiersDoNotEnterTheNeutralReferencesSet() {
        Diff diff = new Diff("cafe1234", List.of(
                patch("src/main/java/dev/example/Alpha.java", "java",
                        new DiffLine(LineType.ADDED, null, 1, "pricingHelper.chargeFor(item);"))), false);

        List<IntegrationEvent> emitted = runFetchDiff(fakeDiffSource(diff), refs);

        DiffFetched fetched = assertInstanceOf(DiffFetched.class, emitted.getFirst());
        // The two sets are populated by different extractors and must not cross-feed: the ticket
        // providers would scan hundreds of identifiers, and a ticket-shaped token in code would be
        // fetched as a real ticket.
        assertTrue(fetched.codeReferences().identifiers().contains("chargeFor"));
        assertFalse(fetched.references().contains("chargeFor"));
    }

    /**
     * Code context is enrichment, exactly like the repo-rules read beside it: a bug in extraction
     * (a malformed patch, a future {@code LanguageSupport} defect) must degrade to no code
     * references rather than sink a review that would otherwise have succeeded on the plain diff.
     */
    @Test
    void extractionFailureDegradesToEmptyInsteadOfFailingTheReview() {
        Diff diff = new Diff("cafe1234", List.of(
                patch("src/main/java/dev/example/Alpha.java", "java",
                        new DiffLine(LineType.ADDED, null, 1, "pricingHelper.chargeFor(item);"))), false);
        WorkerCodeReferences throwing = new WorkerCodeReferences() {
            @Override
            public CodeReferences inDiff(Diff patchedDiff) {
                throw new IllegalStateException("simulated extraction bug");
            }
        };

        List<IntegrationEvent> emitted = runFetchDiff(fakeDiffSource(diff), throwing);

        DiffFetched fetched = assertInstanceOf(DiffFetched.class, emitted.getFirst());
        assertTrue(fetched.codeReferences().isEmpty(),
                "a failed extraction must fall back to empty, not fail the review");
    }
}
