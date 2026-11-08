package dev.codespire.runworker;

/**
 * Which run units hold their model credential in a model proxy rather than in the agent (harness reviews step 3,
 * {@code spire.run.credential-proxy.mode}). A proxied unit needs an egress network, so with
 * {@code spire.run.egress.mode=off} no unit is proxied whatever this says ({@link ModelProxyImageCheck} warns).
 */
public enum CredentialProxyMode {

    /** No unit is proxied: every agent holds its credential, as before step 3. */
    OFF,

    /** Units built for a harness review are proxied (the default); factory builds are not, until measured there. */
    REVIEWS,

    /** Every unit is proxied, factory builds included. */
    ALL;

    /** Whether a unit of this kind is proxied under this mode, before the egress mode and the harness have their say. */
    boolean appliesTo(boolean reviewRun) {
        return this == ALL || this == REVIEWS && reviewRun;
    }
}
