package dev.codespire.orchestrator.provider;

import java.util.Locale;

/**
 * What an SCM registration is FOR (ADR-038).
 *
 * <p>Two identities, two authority sets. The reviewer posts comments and is the subject of the
 * author allowlist; the factory pushes branches as a dedicated machine account. Sharing one identity
 * would let allowlisting the factory's account as a PR author grant the review bot allowed-author
 * rights on {@code /review}, {@code /finding} and {@code /fix} — the widening ADR-036 forbids.
 *
 * <p>A role on the existing registry rather than a second table: same Tink encryption, same settings
 * UI, same bot-identity resolution on save. What changes is that the role is part of every lookup's
 * KEY, because since V44 one workspace can hold both.
 */
public enum ProviderRole {
    REVIEWER,
    FACTORY;

    /** Null and blank mean REVIEWER, so every caller that predates the role is unchanged. */
    public static ProviderRole of(String raw) {
        if (raw == null || raw.isBlank()) {
            return REVIEWER;
        }
        try {
            return valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown provider role: " + raw
                    + " (expected REVIEWER or FACTORY)", e);
        }
    }
}
