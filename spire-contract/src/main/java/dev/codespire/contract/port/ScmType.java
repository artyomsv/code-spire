package dev.codespire.contract.port;

import java.util.Optional;

/**
 * The SCM platforms the core understands. Each value knows its
 * {@code scm_provider.type} string ({@link #providerType()}), so the wire/enum
 * form and the registry's stored string never drift.
 */
public enum ScmType {

    BITBUCKET_CLOUD("bitbucket-cloud"),
    BITBUCKET_DC("bitbucket-dc"),
    GITHUB("github"),
    GITLAB("gitlab");

    private final String providerType;

    ScmType(String providerType) {
        this.providerType = providerType;
    }

    /** The {@code scm_provider.type} string used by the provider registry. */
    public String providerType() {
        return providerType;
    }

    /**
     * The type whose {@link #providerType()} equals {@code providerType}, or empty when the string
     * names nothing we support. Empty is deliberately not a default: a caller that cannot identify
     * the platform must decline to act rather than assume one.
     */
    public static Optional<ScmType> fromProviderType(String providerType) {
        if (providerType == null || providerType.isBlank()) {
            return Optional.empty();
        }
        for (ScmType type : values()) {
            if (type.providerType.equals(providerType)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
