package dev.codespire.orchestrator.security;

import dev.codespire.orchestrator.operator.OperatorDirectory;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import org.eclipse.microprofile.jwt.Claims;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /api/me} records the signed-in operator, and never their email address.
 *
 * <p>Found on the first production install: nothing called {@link OperatorDirectory#seen}, so
 * Settings → Operators said "Nobody has signed in yet" to an operator who had. A plain unit test
 * because the JWT branch never runs under {@code @TestSecurity} (see {@link OidcSubjectsTest}), and
 * the email case lives only there: on that realm {@code preferred_username} is the email address.
 */
class AuthResourceRecordsOperatorTest {

    private static final String SUBJECT = "TEST-SUBJECT-0001";

    @Test
    void recordsTheNameAndAnEmptyUsernameWhenTheLoginIsAnEmail() {
        RecordingDirectory directory = new RecordingDirectory();

        resource(directory, identityOf(jwt(Map.of(
                Claims.sub.name(), SUBJECT,
                "preferred_username", "test-operator@example.invalid",
                "name", "TEST Operator"), "test-operator@example.invalid"))).me();

        assertEquals(List.of(List.of(SUBJECT, "", "TEST Operator")), directory.seen);
    }

    @Test
    void recordsAPlainLoginAsTheUsername() {
        RecordingDirectory directory = new RecordingDirectory();

        resource(directory, identityOf(jwt(Map.of(
                Claims.sub.name(), SUBJECT,
                "preferred_username", "test-operator",
                "name", "TEST Operator"), "test-operator"))).me();

        assertEquals(List.of(List.of(SUBJECT, "test-operator", "TEST Operator")), directory.seen);
    }

    /** No {@code name} claim and an email login: nothing is left to show but the subject. */
    @Test
    void neverFallsBackToAnEmailForTheDisplayName() {
        RecordingDirectory directory = new RecordingDirectory();

        resource(directory, identityOf(jwt(Map.of(
                Claims.sub.name(), SUBJECT,
                "preferred_username", "test-operator@example.invalid"), "test-operator@example.invalid"))).me();

        assertEquals(List.of(List.of(SUBJECT, "", "")), directory.seen);
    }

    /** No JWT and a principal named by email: the subject itself would be the email, so nothing is written. */
    @Test
    void recordsNothingWhenTheSubjectIsAnEmail() {
        RecordingDirectory directory = new RecordingDirectory();

        resource(directory, QuarkusSecurityIdentity.builder()
                .setPrincipal(new io.quarkus.security.runtime.QuarkusPrincipal("test-operator@example.invalid"))
                .build()).me();

        assertTrue(directory.seen.isEmpty());
    }

    @Test
    void recordsNothingForAnAnonymousCaller() {
        RecordingDirectory directory = new RecordingDirectory();

        resource(directory, QuarkusSecurityIdentity.builder().setAnonymous(true).build()).me();

        assertTrue(directory.seen.isEmpty());
    }

    /** {@code seen} never throws by design, and the session probe must answer even if it did. */
    @Test
    void stillAnswersWhenRecordingFails() {
        OperatorDirectory failing = new OperatorDirectory() {
            @Override
            public void seen(String subject, String username, String displayName) {
                throw new IllegalStateException("TEST failure");
            }
        };

        AuthResource.Me me = resource(failing, identityOf(jwt(Map.of(Claims.sub.name(), SUBJECT), "test-operator"))).me();

        assertEquals(SUBJECT, me.subject());
    }

    private static AuthResource resource(OperatorDirectory directory, SecurityIdentity identity) {
        AuthResource resource = new AuthResource();
        resource.directory = directory;
        resource.identity = identity;
        resource.authEnabled = true;
        return resource;
    }

    private static SecurityIdentity identityOf(JsonWebToken jwt) {
        return QuarkusSecurityIdentity.builder().setPrincipal(jwt).build();
    }

    /** Overrides the one method {@code me} reaches, so no database is opened (CLAUDE.md, saga fakes). */
    private static final class RecordingDirectory extends OperatorDirectory {
        final List<List<String>> seen = new ArrayList<>();

        @Override
        public void seen(String subject, String username, String displayName) {
            seen.add(List.of(subject, username, displayName));
        }
    }

    private static JsonWebToken jwt(Map<String, String> claims, String principalName) {
        return new JsonWebToken() {
            @Override
            public String getName() {
                return principalName;
            }

            @Override
            public Set<String> getClaimNames() {
                return claims.keySet();
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T getClaim(String claimName) {
                return (T) claims.get(claimName);
            }
        };
    }
}
