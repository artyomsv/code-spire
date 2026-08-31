package dev.codespire.orchestrator.security;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import org.eclipse.microprofile.jwt.Claims;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * How the caller's identity is resolved (P4 / FR-11).
 *
 * <p>Worth its own tests because <b>the JWT branch never runs under {@code @TestSecurity}</b>: that
 * annotation yields a {@link QuarkusPrincipal}, so every authorization test in this project exercises
 * only the fallback. The branch that matters in production was therefore the one nothing covered.
 *
 * <p>What it has to get right is that {@code sub} wins over the principal name. They differ: for a
 * JWT the name is {@code preferred_username}, and usernames get reassigned in an identity provider —
 * so a deleted-and-recreated account would inherit the previous person's analytics.
 */
class OidcSubjectsTest {

    @Test
    void prefersTheSubjectClaimOverThePrincipalName() {
        SecurityIdentity identity = identityOf(jwt("TEST-SUBJECT-1", "TEST-USERNAME"));

        assertEquals("TEST-SUBJECT-1", OidcSubjects.of(identity));
    }

    /**
     * The {@code %dev} profile runs unauthenticated and carries no JWT, so the fallback must still
     * produce something — both callers key on this value and would otherwise disagree.
     */
    @Test
    void fallsBackToThePrincipalNameWhenThereIsNoJwt() {
        SecurityIdentity identity = identityOf(new QuarkusPrincipal("TEST-DEV-OPERATOR"));

        assertEquals("TEST-DEV-OPERATOR", OidcSubjects.of(identity));
    }

    /** A token with a blank subject is no better than none — fall back rather than key on "". */
    @Test
    void fallsBackWhenTheSubjectClaimIsBlank() {
        SecurityIdentity identity = identityOf(jwt("   ", "TEST-USERNAME"));

        assertEquals("TEST-USERNAME", OidcSubjects.of(identity));
    }

    @Test
    void anAnonymousCallerHasNoSubject() {
        assertEquals("", OidcSubjects.of(QuarkusSecurityIdentity.builder().setAnonymous(true).build()));
        assertEquals("", OidcSubjects.of(null));
    }

    private static SecurityIdentity identityOf(Principal principal) {
        return QuarkusSecurityIdentity.builder().setPrincipal(principal).build();
    }

    /**
     * A minimal {@link JsonWebToken}. Matching on this interface rather than on Quarkus's internal
     * {@code OidcJwtCallerPrincipal} is the point of the class under test: the internal one lives in a
     * {@code runtime} package that is not API, and a move would make the check go quietly false.
     */
    private static JsonWebToken jwt(String subject, String name) {
        return new JsonWebToken() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public Set<String> getClaimNames() {
                return Set.of(Claims.sub.name());
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T getClaim(String claimName) {
                return Claims.sub.name().equals(claimName) ? (T) subject : null;
            }
        };
    }
}
