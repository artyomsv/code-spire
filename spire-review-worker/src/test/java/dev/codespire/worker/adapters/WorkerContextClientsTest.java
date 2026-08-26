package dev.codespire.worker.adapters;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.context.code.BitbucketSourceFileReader;
import dev.codespire.context.code.CodeContextProvider;
import dev.codespire.context.code.GitHubSourceFileReader;
import dev.codespire.context.code.GitLabSourceFileReader;
import dev.codespire.context.code.SourceFileReader;
import dev.codespire.contract.command.ActionCommand.GatherContext;
import dev.codespire.contract.context.ContextCredential;
import dev.codespire.contract.port.ContextProvider;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.encryption.EncryptionService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The {@code code} branch of {@link WorkerContextClients#forCommand} is the one place a credential's
 * path allow-list actually reaches the constructed {@link CodeContextProvider}. Task 10 added
 * allow-list enforcement inside the provider precisely because a review found nothing upstream of it
 * proved the allow-list was ever carried through — a two-argument {@code CodeContextProvider}
 * constructor defaults it to empty (unrestricted), so calling the wrong overload here would make the
 * control dead code again while an operator's configured allow-list silently did nothing. This
 * asserts the wiring end to end, not just that construction compiles.
 */
class WorkerContextClientsTest {

    private static final String WORKSPACE = "acme";
    private static final RepoRef REPO = new RepoRef(WORKSPACE, "widgets");
    private static final EncryptionService ENCRYPTION =
            new EncryptionService(EncryptionService.generateKeysetBase64());

    private WorkerContextClients clients() {
        WorkerContextClients wc = new WorkerContextClients();
        wc.encryption = ENCRYPTION;
        wc.mapper = new ObjectMapper();
        wc.codeReferences = new WorkerCodeReferences();
        return wc;
    }

    private static GatherContext command(String cipher) {
        return new GatherContext("review::" + WORKSPACE + "/widgets#1", REPO, 1, "cafe1234",
                Set.of(), cipher, null, null);
    }

    private static String pack(ContextCredential cred) throws Exception {
        String json = new ObjectMapper().writeValueAsString(List.of(cred));
        return ENCRYPTION.encryptString(json, ContextCredential.aad(WORKSPACE));
    }

    @Test
    void codeCredentialCarriesItsPathAllowListIntoTheConstructedProvider() throws Exception {
        ContextCredential cred = new ContextCredential("code", "https://api.github.com", "bearer",
                null, "gh-token", "src/main/, src/allowed/");

        List<ContextProvider> providers = clients().forCommand(command(pack(cred)));

        CodeContextProvider code = providers.stream()
                .filter(CodeContextProvider.class::isInstance)
                .map(CodeContextProvider.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("forCommand did not construct a CodeContextProvider"));
        assertEquals(Set.of("src/main/", "src/allowed/"), code.pathAllowList());
    }

    @Test
    void aBlankProjectKeysColumnLeavesTheAllowListEmptyMeaningUnrestricted() throws Exception {
        ContextCredential cred = new ContextCredential("code", "https://api.github.com", "bearer",
                null, "gh-token", null);

        List<ContextProvider> providers = clients().forCommand(command(pack(cred)));

        CodeContextProvider code = providers.stream()
                .filter(CodeContextProvider.class::isInstance)
                .map(CodeContextProvider.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("forCommand did not construct a CodeContextProvider"));
        assertEquals(Set.of(), code.pathAllowList());
    }

    /**
     * One generic {@code code} credential covers three raw-content APIs and carries no platform field,
     * so {@code readerFor} infers it from the base URL's host. Which reader that picks has no other
     * observable trace: routing a self-managed GitLab to the GitHub reader produces 404s
     * indistinguishable from "the file isn't there", so context is silently never contributed. Both
     * pre-existing tests used {@code api.github.com}, which is the fallback branch, and asserted only
     * the allow-list — so nothing covered the selection at all (PR 63 QA review).
     */
    @Test
    void aGitLabHostSelectsTheGitLabReader() throws Exception {
        assertEquals(GitLabSourceFileReader.class, readerClassFor("https://gitlab.acme.example"));
    }

    @Test
    void aBitbucketHostSelectsTheBitbucketReader() throws Exception {
        assertEquals(BitbucketSourceFileReader.class, readerClassFor("https://api.bitbucket.org/2.0"));
    }

    /** GitHub is the fallback — its hostname is the least predictable of the three. */
    @Test
    void anyOtherHostFallsBackToTheGitHubReader() throws Exception {
        assertEquals(GitHubSourceFileReader.class, readerClassFor("https://api.github.com"));
        assertEquals(GitHubSourceFileReader.class, readerClassFor("https://source.acme.example"));
    }

    /**
     * Every reader is wrapped by the circuit breaker before it reaches the provider, so the selection
     * is only visible through the wrapper — asserted here rather than assumed, since a wiring that
     * skipped the breaker would leave one struggling host able to stall every review.
     */
    private Class<?> readerClassFor(String baseUrl) throws Exception {
        ContextCredential cred =
                new ContextCredential("code", baseUrl, "bearer", null, "token", null);

        CodeContextProvider code = clients().forCommand(command(pack(cred))).stream()
                .filter(CodeContextProvider.class::isInstance)
                .map(CodeContextProvider.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("forCommand did not construct a CodeContextProvider"));

        SourceFileReader reader = code.reader();
        assertInstanceOf(CircuitBreakingSourceFileReader.class, reader,
                "the code reader must stay behind the shared per-host circuit breaker");
        return ((CircuitBreakingSourceFileReader) reader).delegate().getClass();
    }
}
