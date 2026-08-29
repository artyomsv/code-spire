package dev.codespire.orchestrator.security;

import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * The caller's OIDC subject, resolved one way in one place.
 *
 * <p>Two callers need it — {@code /api/me} reports it so an admin has something to link, and the
 * per-author analytics read authorizes against it — and they must agree. A mapping created from one
 * spelling of "who is this" and checked against another silently leaves every operator unlinked.
 *
 * <p><b>Matched on {@link JsonWebToken}, not on Quarkus's internal principal class.</b> An earlier
 * version tested {@code instanceof io.quarkus.oidc.runtime.OidcJwtCallerPrincipal}, which lives in a
 * {@code runtime} package that is not API. If an upgrade moved it, the check would go quietly false
 * and both callers would fall back to {@code getPrincipal().getName()} — which for a JWT is
 * {@code preferred_username}, not {@code sub}. Usernames get reassigned in an identity provider, so a
 * deleted-and-recreated account would inherit the previous person's analytics. {@code JsonWebToken}
 * is the supported interface that principal already implements, and it is what the fallback should
 * have keyed on all along.
 */
public final class OidcSubjects {

    private OidcSubjects() {
    }

    /**
     * @return the {@code sub} claim, or the principal name when the identity carries no JWT — which
     *     is the {@code %dev} profile, where authentication is off entirely. Never null, so callers
     *     always have one value to render and to key on.
     */
    public static String of(SecurityIdentity identity) {
        if (identity == null || identity.isAnonymous()) {
            return "";
        }
        if (identity.getPrincipal() instanceof JsonWebToken jwt) {
            String subject = jwt.getSubject();
            if (subject != null && !subject.isBlank()) {
                return subject;
            }
        }
        return identity.getPrincipal().getName();
    }
}
