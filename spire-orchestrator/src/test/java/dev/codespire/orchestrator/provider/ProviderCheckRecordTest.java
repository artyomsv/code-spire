package dev.codespire.orchestrator.provider;

import dev.codespire.contract.scm.Author;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The outcome has to survive a round trip, since the panel reads it back on every poll. */
@QuarkusTest
class ProviderCheckRecordTest {

    @Inject
    ProviderRegistry registry;

    private ProviderView created() {
        return registry.create(new ProviderInput("TEST-provider", "stub", "https://scm.example.invalid",
                "TEST-WS-" + UUID.randomUUID(), "bearer", null, "TEST-SECRET", "acct-1", true,
                List.of(), "test-bot", null));
    }

    @Test
    void aNewProviderHasNeverBeenChecked() {
        ProviderView view = created();
        assertNull(view.lastCheckAt());
        assertNull(view.lastCheckOk());
        assertNull(view.lastCheckError());
    }

    @Test
    void aFailedCheckIsStoredWithItsDetail() {
        ProviderView view = created();
        registry.recordCheck(UUID.fromString(view.id()), false, "Authentication rejected (HTTP 401)");
        ProviderView reread = registry.get(UUID.fromString(view.id())).orElseThrow();
        assertNotNull(reread.lastCheckAt());
        assertFalse(reread.lastCheckOk());
        assertEquals("Authentication rejected (HTTP 401)", reread.lastCheckError());
    }

    /** Success must null the stored error, or a stale message outlives the failure it described. */
    @Test
    void aPassingCheckClearsThePreviousError() {
        ProviderView view = created();
        registry.recordCheck(UUID.fromString(view.id()), false, "Authentication rejected (HTTP 401)");
        registry.recordCheck(UUID.fromString(view.id()), true, null);
        ProviderView reread = registry.get(UUID.fromString(view.id())).orElseThrow();
        assertTrue(reread.lastCheckOk());
        assertNull(reread.lastCheckError());
    }

    // ---- ProviderResource.update: recording success requires an actual re-validation ----------
    //
    // resolveIdentity(...) only calls out to the SCM when a secret is supplied (its own Javadoc:
    // "with no token ... it is a pass-through"). recordCheck(true, null) must therefore be gated
    // on the same condition, or an update that never touches the credential would fabricate a
    // "check passed" outcome and silently clear a real rejection. Goes through ProviderResource
    // itself (not just the registry) since the guard lives there; the identity resolver is faked
    // per ProviderResourceResolveTest's established pattern — no live SCM call, no HTTP layer.

    private ProviderInput githubInput(String workspace, String secret, String name) {
        return new ProviderInput(name, "github", "https://scm.example.invalid", workspace,
                "bearer", null, secret, "acct-1", true, List.of(), "test-bot", null);
    }

    private ProviderResource resourceWithFakeIdentity(ProviderIdentityResolver identity) {
        ProviderResource resource = new ProviderResource();
        resource.registry = registry;
        resource.identity = identity;
        // scm.example.invalid deliberately never resolves via DNS; skip the SSRF host check
        // (the real https+public-address enforcement is exercised elsewhere, not by this test).
        resource.allowInsecureProviderUrls = true;
        return resource;
    }

    /** The negative case that protects the decision: silence must not read as success. */
    @Test
    void anUpdateWithNoNewSecretDoesNotClearARejectedCredential() {
        String workspace = "TEST-WS-" + UUID.randomUUID();
        ProviderView view = registry.create(githubInput(workspace, "TEST-SECRET", "TEST-provider"));
        UUID id = UUID.fromString(view.id());
        registry.recordCheck(id, false, "Authentication rejected (HTTP 401)");

        ProviderResource resource = resourceWithFakeIdentity(new ProviderIdentityResolver() {
            @Override
            public Author resolveForRegistration(ProviderInput in) {
                throw new IllegalStateException("must not be called: no new secret was supplied");
            }
        });
        // No secret -> keeps the stored one; only the name changes.
        resource.update(view.id(), githubInput(workspace, null, "TEST-provider-renamed"));

        ProviderView reread = registry.get(id).orElseThrow();
        assertFalse(reread.lastCheckOk(), "an update that never touched the credential must not clear its rejection");
        assertEquals("Authentication rejected (HTTP 401)", reread.lastCheckError());
    }

    /** The positive counterpart: a genuine re-validation does clear the rejection. */
    @Test
    void anUpdateWithANewSecretClearsARejectedCredential() {
        String workspace = "TEST-WS-" + UUID.randomUUID();
        ProviderView view = registry.create(githubInput(workspace, "TEST-SECRET", "TEST-provider"));
        UUID id = UUID.fromString(view.id());
        registry.recordCheck(id, false, "Authentication rejected (HTTP 401)");

        ProviderResource resource = resourceWithFakeIdentity(new ProviderIdentityResolver() {
            @Override
            public Author resolveForRegistration(ProviderInput in) {
                return Author.of("acct-1", "test-bot", "Test Bot");
            }
        });
        resource.update(view.id(), githubInput(workspace, "TEST-NEW-SECRET", "TEST-provider"));

        ProviderView reread = registry.get(id).orElseThrow();
        assertTrue(reread.lastCheckOk(), "a genuine re-validation must clear the prior rejection");
        assertNull(reread.lastCheckError());
    }
}
