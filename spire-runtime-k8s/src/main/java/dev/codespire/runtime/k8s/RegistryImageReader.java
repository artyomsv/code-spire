package dev.codespire.runtime.k8s;

import dev.codespire.runtime.ImageDescription;
import dev.codespire.runtime.RegistryCredential;
import java.net.http.HttpClient;
import java.util.Objects;

/**
 * Reads an image's labels and its pin from the registry, with no daemon and no pod. Only its shape exists so far:
 * {@link #describe} refuses until the registry read is built.
 */
public final class RegistryImageReader {

    private final HttpClient http;

    private final RegistryCredential credential;

    public RegistryImageReader(HttpClient http, RegistryCredential credentialOrNull) {
        this.http = Objects.requireNonNull(http, "http");
        this.credential = credentialOrNull;
    }

    public ImageDescription describe(String image) {
        throw new UnsupportedOperationException("the Kubernetes arm cannot read image metadata for " + image
                + " yet: the registry read is not built");
    }
}
