package dev.codespire.runtime;

/** Opaque digest binding runtime resources to their owning work attempt; carries no credential. */
public record PublicationKey(String value) {
    public PublicationKey {
        if(value==null || !value.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("A full publication binding is required");
    }
}
