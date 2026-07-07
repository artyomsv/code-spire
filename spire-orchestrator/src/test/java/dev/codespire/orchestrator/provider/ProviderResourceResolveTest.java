package dev.codespire.orchestrator.provider;

import dev.codespire.contract.scm.Author;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The auto-resolve/validate step in {@link ProviderResource}: with a token
 * present it fills a blank bot account id from the token owner and surfaces auth
 * failures as 400s; with the id supplied it keeps it; with no token (update
 * keeping the stored one) it is a pass-through. The resolver is faked — no live
 * SCM call, no CDI container.
 */
class ProviderResourceResolveTest {

    private ProviderResource resourceThatResolvesTo(Author owner, RuntimeException failure) {
        ProviderResource resource = new ProviderResource();
        resource.identity = new ProviderIdentityResolver() {
            @Override
            public Author resolve(ProviderInput in) {
                if (failure != null) {
                    throw failure;
                }
                return owner;
            }
        };
        return resource;
    }

    private static ProviderInput input(String secret, String botAccountId) {
        return new ProviderInput("GitHub", "github", "https://api.github.com", "artyomsv",
                "bearer", null, secret, botAccountId, true, List.of());
    }

    @Test
    void blankBotAccountId_isFilledFromTheTokenOwner() {
        var resource = resourceThatResolvesTo(Author.of("40727", "spire-bot", "Bot"), null);
        ProviderInput out = resource.resolveIdentity(input("ghp_realtoken", ""));
        assertEquals("40727", out.botAccountId(), "resolved from whoami");
        assertEquals("ghp_realtoken", out.secret(), "token preserved");
        assertEquals("artyomsv", out.workspace());
    }

    @Test
    void providedBotAccountId_isKept() {
        var resource = resourceThatResolvesTo(Author.of("40727", "spire-bot", "Bot"), null);
        ProviderInput out = resource.resolveIdentity(input("ghp_realtoken", "manual-id-123"));
        assertEquals("manual-id-123", out.botAccountId(), "an explicit id is respected");
    }

    @Test
    void noToken_isPassThrough_notValidated() {
        var resource = resourceThatResolvesTo(null, new IllegalStateException("must not be called"));
        ProviderInput in = input("", "");
        assertSame(in, resource.resolveIdentity(in), "no token to validate on an update that keeps the stored one");
    }

    @Test
    void invalidToken_isSurfacedAsBadRequest() {
        var resource = resourceThatResolvesTo(null, new RuntimeException("HTTP 401"));
        var e = assertThrows(BadRequestException.class, () -> resource.resolveIdentity(input("bad", "")));
        assertTrue(e.getMessage().contains("401"), "the SCM error is surfaced to the operator");
    }
}
