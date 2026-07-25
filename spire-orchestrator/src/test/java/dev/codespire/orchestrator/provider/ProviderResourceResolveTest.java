package dev.codespire.orchestrator.provider;

import dev.codespire.contract.scm.Author;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The auto-resolve/validate step in {@link ProviderResource}: with a token present, the identity the
 * token resolves to wins over whatever the form carried (so rotating bot accounts cannot leave a stale
 * id behind), falling back to a submitted id only when the provider cannot resolve one; auth failures
 * surface as generic 400s; with no token (an update keeping the stored one) it is a pass-through. The
 * resolver is faked — no live SCM call, no CDI container.
 */
class ProviderResourceResolveTest {

    private ProviderResource resourceThatResolvesTo(Author owner, RuntimeException failure) {
        ProviderResource resource = new ProviderResource();
        resource.identity = new ProviderIdentityResolver() {
            // Stubs the entry point the resource actually calls. It used to stub resolve()
            // and rely on resolveForRegistration() delegating to it — a seam that vanished
            // when the account-less-token fallback moved into the adapters.
            @Override
            public Author resolveForRegistration(ProviderInput in) {
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
                "bearer", null, secret, botAccountId, true, List.of(), null, null);
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
    void aResolvedIdentityOverridesTheSubmittedId() {
        // Rotating to a different bot account must not leave the previous account's id behind: the
        // ADR-013 self-loop guard matches a comment's author against it, so a stale id makes the bot
        // answer its own comments.
        var resource = resourceThatResolvesTo(Author.of("40727", "spire-bot", "Bot"), null);
        ProviderInput out = resource.resolveIdentity(input("ghp_newbot_token", "previous-owner-id"));
        assertEquals("40727", out.botAccountId(), "the validated token is the authority on who the bot is");
        assertEquals("spire-bot", out.botUsername());
    }

    @Test
    void submittedIdIsKeptWhenTheProviderCannotResolveOne() {
        // Bitbucket access tokens cannot call /user, so whoami yields no id and the operator supplies it.
        var resource = resourceThatResolvesTo(Author.of("", "", ""), null);
        ProviderInput out = resource.resolveIdentity(input("bb_token", "manual-id-123"));
        assertEquals("manual-id-123", out.botAccountId(), "the hand-entered id survives a blank whoami");
    }

    @Test
    void noToken_isPassThrough_notValidated() {
        var resource = resourceThatResolvesTo(null, new IllegalStateException("must not be called"));
        ProviderInput in = input("", "");
        assertSame(in, resource.resolveIdentity(in), "no token to validate on an update that keeps the stored one");
    }

    @Test
    void invalidToken_isSurfacedAsGenericBadRequest() {
        var resource = resourceThatResolvesTo(null, new RuntimeException("HTTP 401 from http://10.0.0.1/user"));
        var e = assertThrows(BadRequestException.class, () -> resource.resolveIdentity(input("bad", "")));
        assertEquals("Could not validate the API token against the provider", e.getMessage(),
                "generic client message — the operator learns the token failed, nothing more");
        assertFalse(e.getMessage().contains("401"), "upstream/internal detail must not be reflected to the client");
    }
}
