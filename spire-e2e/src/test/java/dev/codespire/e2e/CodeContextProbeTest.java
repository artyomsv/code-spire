package dev.codespire.e2e;

import dev.codespire.e2e.gitlab.GitLabDriver.FileAction;
import dev.codespire.e2e.spire.LlmMock;
import dev.codespire.e2e.support.Await;
import dev.codespire.e2e.support.Fixtures;
import dev.codespire.e2e.support.ReadModel;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MRs 2 and 3: the code-context path, one merge request per language.
 *
 * <p>The review loop does not branch on language, so running the whole chain twice would test the
 * same code twice. What genuinely differs per language is import parsing, the symbol index and caller
 * lookup — plus the two independently-maintained extension maps that decide a file's language on
 * either half of the pipeline
 * ({@code techdebt/global/3-2-code-extension-map-duplicated-with-no-drift-guard.md}).
 *
 * <p>Both probes assert against the PROMPT, read from llm-mock's request journal, and that is the
 * point: a finding is the model's opinion and unassertable, while the prompt is a fact about our
 * code. It also removes any need to enable {@code PromptLog} — opt-in and off by default, because a
 * rendered prompt quotes source — merely so a test can see what was assembled.
 *
 * <h2>Why this is disabled</h2>
 *
 * <p>It cannot pass against a CONTAINERISED GitLab, for a structural reason rather than a defect.
 * {@code GitLabSourceFileReader} fetches source through {@code PinnedJsonClient}, whose SSRF guard
 * refuses loopback, link-local and site-local addresses <b>on every request</b>
 * ({@code PinnedJsonClient:199}). A Docker-network GitLab is RFC1918, so the fetch is refused, and
 * context providers fail soft — the review still completes, {@code ContextAssembled} is still
 * emitted, and {@code worker.context_blob} simply stays empty.
 *
 * <p>The design spec's §3.1 got this wrong: it established that
 * {@code SPIRE_SECURITY_ALLOW_INSECURE_PROVIDER_URLS} relaxes the guard, but that flag governs
 * {@code PublicHttpsGuard}, which the ORCHESTRATOR consults at provider create/update. The worker's
 * per-request guard is a different control in a different module with no such escape, and nothing
 * about relaxing the first touches the second. Every {@code spire-http}-based context provider —
 * Jira, Confluence, GitHub Issues, GitLab Issues and code — is affected identically.
 *
 * <p>Two ways forward, neither of them a test change:
 * <ol>
 *   <li>Give {@code PinnedJsonClient} the same opt-in relaxation the orchestrator guard already has.
 *       That is a change to a security control and belongs in its own reviewed commit, not smuggled
 *       into a test PR.</li>
 *   <li>Reach a GitLab on a public-resolving address, which a Docker network cannot provide.</li>
 * </ol>
 *
 * <p>Kept rather than deleted because the fixtures and assertions are correct and become runnable the
 * moment the blocker is lifted — and because the diagnosis above is the expensive part. Tracked in
 * {@code techdebt/global/3-3-context-providers-cannot-reach-a-private-network-scm.md}.
 *
 * <p><b>One thing it already proved.</b> An earlier version put the definition inside the merge
 * request's own diff, and passed — for the wrong reason: the definition's body reached the model
 * because it was in the DIFF, not because anything retrieved it. That version would have passed
 * against a completely inert code-context provider. Moving the definition to the target branch is
 * what turned it into a real assertion, and is why the failure above is trustworthy.
 */
@Disabled("Structurally blocked: PinnedJsonClient's per-request SSRF guard refuses a "
        + "Docker-network GitLab, so no spire-http context provider can fetch. See the class "
        + "javadoc and techdebt/global/3-3-context-providers-cannot-reach-a-private-network-scm.md")
class CodeContextProbeTest {

    /** Present only in the definition's body, so finding it proves the snippet was retrieved. */
    private static final String DEFINITION_MARKER = "E2E-PROBE-DEFINITION-BODY";

    private static final String BRANCH = "e2e-probe";

    @Test
    void aJavaDefinitionAndItsCallerReachTheModel() {
        runProbe("e2e-probe-java", "probe-java", "src/main/java/e2e/probe",
                "pricing/Pricer.java",
                List.of("Changed.java", "Caller.java"),
                "Changed.java",
                "        return Pricer.chargeFor(tokens);",
                "        return Pricer.chargeFor(tokens) + 1;",
                "Caller.java");
    }

    @Test
    void aTypeScriptDefinitionAndItsCallerReachTheModel() {
        runProbe("e2e-probe-ts", "probe-ts", "src/ui/probe",
                "pricer.ts",
                List.of("changed.ts", "caller.ts"),
                "changed.ts",
                "  return chargeFor(tokens);",
                "  return chargeFor(tokens) + 1;",
                "caller.ts");
    }

    /**
     * TWO reviews, and the definition lives on the TARGET branch.
     *
     * <p>Two reviews because the symbol index only knows files that reviews have READ, so rung 2 has
     * nothing to offer on a first review — asserting a caller citation there would fail for a correct
     * implementation. The first push populates the index; the second is the one under test.
     *
     * <p>The definition is committed to {@code main} and never appears in the merge request's diff,
     * and that is what makes rung 1 mean anything. With the definition inside the diff, its body
     * reaches the model whether or not code context works at all — the assertion would pass against a
     * completely inert provider. The only route left for it now is retrieval.
     *
     * <p>The caller, by contrast, IS in the first round's diff, because a file no review has ever read
     * cannot be in the index for the second round to find.
     */
    private void runProbe(String prefix, String fixtureDir, String directory, String definitionFile,
                          List<String> changedFiles, String changedFile, String originalLine,
                          String editedLine, String expectedCallerFile) {
        LlmMock.reset();
        Environment env = Environment.provision(prefix);

        env.human().commit(env.projectId(), "main", null, "Add the definition on the target branch",
                List.of(FileAction.create("README.md", "E2E probe fixture.\n"),
                        FileAction.create(directory + "/" + definitionFile,
                                Fixtures.read("fixtures/" + fixtureDir + "/" + basename(definitionFile)))));

        List<FileAction> seed = changedFiles.stream()
                .map(name -> FileAction.create(directory + "/" + name,
                        Fixtures.read("fixtures/" + fixtureDir + "/" + basename(name))))
                .toList();
        env.human().commit(env.projectId(), BRANCH, "main", "Add the probe sources", seed);

        long mrIid = env.human().openMergeRequest(env.projectId(), BRANCH, "main", "E2E context probe");
        String reviewId = env.reviewId(mrIid);

        Await.until("probe round 1 completed", () ->
                "completed".equals(ReadModel.status(reviewId)) ? Optional.of(true) : Optional.empty());

        String changedPath = directory + "/" + changedFile;
        String edited = Fixtures.read("fixtures/" + fixtureDir + "/" + basename(changedFile))
                .replace(originalLine, editedLine);

        long runsBefore = ReadModel.events(reviewId, "ReviewRequested");
        env.human().commit(env.projectId(), BRANCH, null, "Change the call site",
                List.of(FileAction.update(changedPath, edited)));

        Await.until("probe round 2 completed", () -> {
            boolean rerun = ReadModel.events(reviewId, "ReviewRequested") > runsBefore;
            return rerun && "completed".equals(ReadModel.status(reviewId))
                    ? Optional.of(true) : Optional.empty();
        });

        String prompts = String.join("\n\n", LlmMock.prompts());

        assertTrue(prompts.contains(DEFINITION_MARKER),
                "rung 1 — the changed file's import must resolve to the definition and the "
                        + "definition's body must reach the model. Nothing in the prompts contained "
                        + DEFINITION_MARKER + ", which appears only inside that definition.");

        assertTrue(prompts.contains(expectedCallerFile),
                "rung 2 — the symbol index must name a real caller of the changed file's symbol. The "
                        + "index only knows files reviews have read, which is why this asserts on the "
                        + "SECOND review rather than the first.");
    }

    private static String basename(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
