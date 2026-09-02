package dev.codespire.contract.port;

import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.OAuthApp;

/**
 * Proves which SCM account belongs to the operator at the browser (FR-11).
 *
 * <p><b>Why a sign-in and not a lookup.</b> The bot's API token can confirm that a handle exists;
 * nothing it returns says the person signed into the dashboard <em>is</em> that handle, so any
 * design built on it is a claim anyone could make about anyone. Matching an OIDC username against
 * an SCM handle has the same shape with an extra step: on a coincidental match it shows one person
 * another person's performance data, and nothing on screen looks wrong. An SCM sign-in is the only
 * mechanism where the answer comes from the party that actually knows it.
 *
 * <p>The access token obtained here is used once, to ask who the operator is, and then discarded.
 * It is never stored: the durable record is the operator's stable SCM id, which is what
 * {@code review_status.author_id} already carries — so nothing this port produces is a credential
 * that could later be stolen.
 *
 * <p>Implemented by the read adapters, which already know how to ask their platform "who am I" and
 * already return the <em>same</em> stable id a review records. Reusing that answer is what
 * guarantees a link actually matches the rows it is supposed to unlock.
 */
public interface OperatorOAuth {

    ScmType type();

    /**
     * Where to send the operator's browser to sign in.
     *
     * @param state       an unguessable value the callback must present back
     * @param redirectUri this deployment's callback, which must also be registered on the app
     */
    String authorizeUrl(OAuthApp app, String state, String redirectUri);

    /**
     * Exchanges the callback's code and returns whoever authorized it.
     *
     * @throws RuntimeException the adapter's own API exception when the platform refuses
     */
    Author identify(OAuthApp app, String code, String redirectUri);
}
