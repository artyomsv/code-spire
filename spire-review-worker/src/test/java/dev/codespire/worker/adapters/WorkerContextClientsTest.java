package dev.codespire.worker.adapters;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.context.code.CodeContextProvider;
import dev.codespire.contract.command.ActionCommand.GatherContext;
import dev.codespire.contract.context.ContextCredential;
import dev.codespire.contract.port.ContextProvider;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.encryption.EncryptionService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
