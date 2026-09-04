package dev.codespire.orchestrator.factory;

import dev.codespire.contract.port.ScmType;
import dev.codespire.orchestrator.provider.ProviderInput;
import dev.codespire.orchestrator.provider.ProviderRegistry;
import dev.codespire.orchestrator.provider.ProviderRole;
import dev.codespire.orchestrator.provider.ScmProvider;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class MachineAccountsTest {

    @Inject
    MachineAccounts accounts;

    @Inject
    ProviderRegistry providers;

    private static ProviderInput input(String workspace, String name, String secret, String role) {
        return login(workspace, name, secret, role, name);
    }

    private static ProviderInput login(String workspace, String name, String secret, String role,
                                       String botUsername) {
        return new ProviderInput(name, "github", "https://api.github.com", workspace, "bearer",
                null, secret, "", true, List.of(), botUsername, null, role);
    }

    @Test
    void aDeploymentWithNoFactoryAccountCannotDispatch() {
        // Failing closed here is the point: the alternative is silently pushing as the review bot,
        // whose pull requests the reviewer's own author allowlist then skips.
        assertTrue(accounts.resolve(ScmType.GITHUB, "TEST-nobody-" + UUID.randomUUID()).isEmpty());
    }

    @Test
    void aReviewerRegistrationIsNotAMachineAccount() {
        // The same workspace with only a REVIEWER row: still empty. A reviewer registration must
        // never be promoted to a push identity by the absence of a factory one.
        String workspace = "TEST-rev-only-" + UUID.randomUUID();
        providers.create(input(workspace, "reviewer", "TEST-reviewer-token", null));

        assertTrue(accounts.resolve(ScmType.GITHUB, workspace).isEmpty());
    }

    @Test
    void theTwoRolesResolveToTheirOwnRowsAndNeverEachOthers() {
        // V44 lets one workspace hold both. Before the role joined every lookup's KEY, an unfiltered
        // SELECT * returned whichever row the planner yielded first — so the review path could be
        // handed the factory's push token, and the factory the reviewer's. That is the identity
        // confusion ADR-038 exists to prevent, arriving through a query that used to be unambiguous.
        String workspace = "TEST-both-" + UUID.randomUUID();
        providers.create(input(workspace, "reviewer-bot", "TEST-reviewer-token", null));
        providers.create(input(workspace, "factory-bot", "TEST-factory-token", "FACTORY"));

        Optional<ScmProvider> factory = accounts.resolve(ScmType.GITHUB, workspace);
        Optional<ScmProvider> reviewer = providers.resolve("github", workspace);
        Optional<ScmProvider> byWorkspace = providers.resolveByWorkspace(workspace);

        assertEquals("factory-bot", factory.orElseThrow().name());
        assertEquals("reviewer-bot", reviewer.orElseThrow().name(),
                "the review path must get the reviewer, whatever else the workspace holds");
        assertEquals("reviewer-bot", byWorkspace.orElseThrow().name(),
                "and so must the saga path, which resolves by workspace alone");
        assertEquals("factory-bot",
                providers.resolve("github", workspace, ProviderRole.FACTORY).orElseThrow().name());
    }

    /**
     * A registration with no login is not a usable account, and the ONE resolve says so.
     *
     * <p>The login is what the forge authenticates the push as; packing a null one throws inside
     * {@code MachineAccountCredential}. {@code RunResource} guarded that on the REST arm, where a
     * throw is a 500 the caller reads. The {@code /fix} path re-derived the same lookup and
     * dropped the guard, and there a throw escapes the Kafka consumer: the record is redelivered
     * forever and the author who typed {@code /fix} is told nothing at all.
     *
     * <p>So the check is asserted HERE, on the one method both arms go through, rather than once
     * per caller. Blank and absent are tested separately because they take different paths into
     * the row — {@code ProviderRegistry} stores a blank as SQL null, and a test that only covered
     * one would pass with half the guard deleted.
     */
    @Test
    void anAccountWithNoLoginCannotAuthenticateAPushSoItIsNotResolved() {
        String blank = "TEST-blank-login-" + UUID.randomUUID();
        providers.create(login(blank, "factory-bot", "TEST-factory-token", "FACTORY", ""));
        assertTrue(accounts.resolve(ScmType.GITHUB, blank).isEmpty(),
                "a blank login is stored as SQL null and would be packed as a null push identity");

        String absent = "TEST-null-login-" + UUID.randomUUID();
        providers.create(login(absent, "factory-bot", "TEST-factory-token", "FACTORY", null));
        assertTrue(accounts.resolve(ScmType.GITHUB, absent).isEmpty(),
                "and an absent one is the same account with the same missing push identity");

        // The discriminating half: the filter must not swallow a usable account.
        String usable = "TEST-has-login-" + UUID.randomUUID();
        providers.create(login(usable, "factory-bot", "TEST-factory-token", "FACTORY", "factory-bot"));
        assertEquals("factory-bot", accounts.resolve(ScmType.GITHUB, usable).orElseThrow().botUsername());
    }

    @Test
    void aRoleThatIsNeitherIsRefusedAtRegistration() {
        assertThrows(IllegalArgumentException.class,
                () -> providers.create(input("TEST-bad-" + UUID.randomUUID(), "x", "TEST-x", "OVERLORD")));
    }
}
