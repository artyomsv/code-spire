package dev.codespire.contract.port;

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
}
