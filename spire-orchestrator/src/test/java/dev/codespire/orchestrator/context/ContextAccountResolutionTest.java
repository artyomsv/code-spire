package dev.codespire.orchestrator.context;

import dev.codespire.orchestrator.provider.ProviderInput;
import dev.codespire.orchestrator.provider.ProviderRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ContextAccountResolutionTest {
    @Inject ContextProviderRegistry sources;
    @Inject ProviderRegistry accounts;

    private ProviderInput account(boolean enabled, String token) {
        return new ProviderInput("Shared account", "atlassian", "https://account.example.test", null,
                "basic", "bot@example.test", token, "", enabled, List.of(), null, null, "CONTEXT");
    }

    @Test
    void sharedAccountSuppliesFreshSecretAndEachSourcesOwnKeys() {
        UUID account = UUID.fromString(accounts.create(account(true, "first-token")).id());
        UUID first = UUID.fromString(sources.create(new ContextProviderInput("First project", "jira",
                "https://account.example.test", account.toString(), "ONE", true)).id());
        UUID second = UUID.fromString(sources.create(new ContextProviderInput("Second project", "jira",
                "https://account.example.test", account.toString(), "TWO", true)).id());
        try {
            var resolved = sources.resolveAllEnabled().stream().filter(s -> s.id().equals(first) || s.id().equals(second)).toList();
            assertEquals(2, resolved.size());
            assertEquals(List.of("ONE", "TWO"), resolved.stream().map(ContextProviderConfig::projectKeys).sorted().toList());
            assertTrue(resolved.stream().allMatch(s -> s.secret().equals("first-token") && s.platform().equals("atlassian")));
            assertEquals(List.of("First project", "Second project"), accounts.get(account).orElseThrow().usedBy());
            var refusal = assertThrows(ProviderRegistry.AccountConflict.class, () -> accounts.delete(account));
            assertTrue(refusal.getMessage().contains("First project"));
            assertTrue(refusal.getMessage().contains("Second project"));
            accounts.update(account, account(true, "rotated-token"));
            assertEquals("rotated-token", sources.resolveById(first).orElseThrow().secret());
            sources.update(second, new ContextProviderInput("Second project", "jira", "https://account.example.test",
                    account.toString(), "TWO", false));
            assertEquals(1, sources.resolveAllEnabled().stream().filter(s -> s.id().equals(first) || s.id().equals(second)).count());
        } finally {
            sources.delete(first); sources.delete(second); accounts.delete(account);
        }
    }

    @Test
    void disablingAnAccountRemovesEveryReferencingSourceFromResolution() {
        UUID account = UUID.fromString(accounts.create(account(true, "never-fall-back-to-this")).id());
        UUID source = UUID.fromString(sources.create(new ContextProviderInput("Enabled source", "jira",
                "https://account.example.test", account.toString(), null, true)).id());
        try {
            assertTrue(sources.resolveAllEnabled().stream().anyMatch(s -> s.id().equals(source)));
            accounts.update(account, account(false, null));
            assertTrue(sources.resolveAllEnabled().stream().noneMatch(s -> s.id().equals(source)));
            assertTrue(sources.resolveById(source).isEmpty());
            assertEquals(false, sources.get(source).orElseThrow().accountEnabled());
        } finally { sources.delete(source); accounts.delete(account); }
    }

    @Test
    void sourceCannotSendAnAccountsSecretToAnotherOriginOrUseAnIncompatibleKind() {
        UUID account = UUID.fromString(accounts.create(account(true, "private-token")).id());
        try {
            assertThrows(jakarta.ws.rs.BadRequestException.class, () -> sources.create(new ContextProviderInput(
                    "Wrong host", "jira", "https://other.example.test", account.toString(), null, true)));
            assertThrows(jakarta.ws.rs.BadRequestException.class, () -> sources.create(new ContextProviderInput(
                    "Wrong kind", "github-issues", "https://account.example.test", account.toString(), null, true)));
        } finally { accounts.delete(account); }
    }
}
