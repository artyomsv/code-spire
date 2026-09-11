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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        wc.symbolIndex = new PostgresSymbolIndex();
        wc.symbolIndexEnabled = true;
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
    void legacyCodeWithoutPlatformDoesNotDiscardOtherSourcesOrRules() throws Exception {
        String json = """
                [{"type":"code","baseUrl":"https://gitlab.example.test","authKind":"bearer","secret":"old"},
                 {"type":"jira","baseUrl":"https://site.example.test","authKind":"basic","username":"bot","secret":"old"},
                 {"type":"confluence","baseUrl":"https://site.example.test/wiki","authKind":"basic","username":"bot","secret":"old"}]
                """;
        var providers = clients().forCommand(command(ENCRYPTION.encryptString(json, ContextCredential.aad(WORKSPACE))));
        assertEquals(3, providers.size());
        assertInstanceOf(dev.codespire.context.jira.JiraContextProvider.class, providers.get(0));
        assertInstanceOf(dev.codespire.context.confluence.ConfluenceContextProvider.class, providers.get(1));
        assertInstanceOf(RulesContextProvider.class, providers.get(2));
    }

    @Test
    void codeCredentialCarriesItsPathAllowListIntoTheConstructedProvider() throws Exception {
        ContextCredential cred = new ContextCredential("code", "github", "https://api.github.com", "bearer",
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
        ContextCredential cred = new ContextCredential("code", "github", "https://api.github.com", "bearer",
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
     * Rung 2 index must actually REACH the provider, and nothing proved it did.
     *
     * <p>{@link CodeContextProvider} has a three-argument constructor that leaves the index null —
     * which is rung 1 exactly, and passes every rung-1 test. So a wiring that silently dropped the
     * index would compile, stay green, and quietly ship a feature that never ran: no caller
     * candidates cited, and no rows written for the next review either. The same argument that
     * exposed {@link CodeContextProvider#pathAllowList()} applies here.
     */
    @Test
    void theSymbolIndexReachesTheConstructedProvider() throws Exception {
        ContextCredential cred = new ContextCredential("code", "github", "https://api.github.com", "bearer",
                null, "gh-token", null);

        assertTrue(providerFor(clients(), cred).hasSymbolIndex(),
                "rung 2 is enabled, so the provider must have been handed the index");
    }

    /**
     * The kill switch turns rung 2 off without turning code context off.
     *
     * <p>Off must degrade to rung 1 — a provider with no index — rather than to no provider at all:
     * an operator disabling the index is declining the caller lookups and the writes, not the
     * import-resolved snippets that shipped before them.
     */
    @Test
    void turningTheIndexOffLeavesARungOneProvider() throws Exception {
        ContextCredential cred = new ContextCredential("code", "github", "https://api.github.com", "bearer",
                null, "gh-token", null);
        WorkerContextClients off = clients();
        off.symbolIndexEnabled = false;

        assertFalse(providerFor(off, cred).hasSymbolIndex(),
                "with the switch off the provider must be rung 1, not rung 2");
    }

    private static CodeContextProvider providerFor(WorkerContextClients clients, ContextCredential cred)
            throws Exception {
        return clients.forCommand(command(pack(cred))).stream()
                .filter(CodeContextProvider.class::isInstance)
                .map(CodeContextProvider.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("forCommand did not construct a CodeContextProvider"));
    }

    @Test
    void anExplicitPlatformSelectsTheReaderRegardlessOfHostname() throws Exception {
        assertEquals(GitLabSourceFileReader.class, readerClassFor("gitlab", "https://source.acme.example"));
        assertEquals(GitHubSourceFileReader.class, readerClassFor("github", "https://gitlab.acme.example"));
    }

    /**
     * Every reader is wrapped by the circuit breaker before it reaches the provider, so the selection
     * is only visible through the wrapper — asserted here rather than assumed, since a wiring that
     * skipped the breaker would leave one struggling host able to stall every review.
     */
    private Class<?> readerClassFor(String platform, String baseUrl) throws Exception {
        ContextCredential cred =
                new ContextCredential("code", platform, baseUrl, "bearer", null, "token", null);

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
