package dev.codespire.orchestrator.security;

import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * The names a signed-in operator is shown by, with every email address taken out.
 *
 * <p>Email is never persisted (CLAUDE.md), and an identity provider does not promise that its name
 * claims are not emails: on the first production realm {@code preferred_username} IS the email
 * address, and the principal name follows it. So any value with an {@code @} is dropped, and the
 * caller gets an empty string instead. The dashboard then shows the display name, or the subject.
 */
public final class OperatorNames {

    private static final String NAME_CLAIM = "name";
    private static final String USERNAME_CLAIM = "preferred_username";

    private OperatorNames() {
    }

    /** The login, or empty when the provider's login is an email address or there is none. */
    public static String username(SecurityIdentity identity) {
        if (identity == null || identity.isAnonymous()) {
            return "";
        }
        String claimed = claim(identity, USERNAME_CLAIM);
        return withoutEmail(claimed.isEmpty() ? identity.getPrincipal().getName() : claimed);
    }

    /** The {@code name} claim, else the login, else empty. Never an email address. */
    public static String displayName(SecurityIdentity identity) {
        if (identity == null || identity.isAnonymous()) {
            return "";
        }
        String name = withoutEmail(claim(identity, NAME_CLAIM));
        return name.isEmpty() ? username(identity) : name;
    }

    /** {@code value} trimmed, or empty when it is blank or looks like an email address. */
    public static String withoutEmail(String value) {
        if (value == null || value.isBlank() || value.contains("@")) {
            return "";
        }
        return value.trim();
    }

    private static String claim(SecurityIdentity identity, String claimName) {
        if (identity.getPrincipal() instanceof JsonWebToken jwt
                && jwt.getClaim(claimName) instanceof String text) {
            return text.trim();
        }
        return "";
    }
}
