package dev.codespire.runtime;

import java.util.Objects;

/**
 * A file or directory from the HOST, mounted into every container of a run unit.
 *
 * <p>The corporate CA bundle is the case this exists for (FR-F14): an operator behind a TLS
 * inspecting proxy has a bundle on the worker host, and every container of the unit needs it or the
 * clone fails at the forge and the agent's model calls fail at the proxy.
 *
 * <p><b>There is no writable host mount, and that is the point of this type rather than a flag on
 * {@link Mount}.</b> Every other mount in a unit is an ephemeral named volume the unit owns and the
 * teardown destroys; this one reaches OUTSIDE the unit onto the machine the worker runs on, and the
 * agent container runs untrusted model output at full shell access. A writable host bind is a host
 * compromise with no further step required. {@link Mount} carries {@code readOnly} as a typed
 * boolean because both answers are legitimate there; here only one is, so it is not expressible.
 *
 * <p>{@code hostPath} is deliberately NOT validated for EXISTENCE here. This is the pure SPI and
 * it has no filesystem; the worker refuses a missing path at startup instead, which is where a
 * bad value can still be corrected by an operator rather than discovered by a run — see
 * {@code EnterpriseEnvironmentConfig}. Some container runtimes CREATE a missing bind source as an
 * empty directory rather than failing, so an unchecked path does not fail: it mounts a directory
 * where a certificate file should be, and every TLS call fails with an error naming neither.
 *
 * <p><b>Absoluteness IS checked, and existence is the only thing the "no filesystem" argument
 * covers.</b> A relative source is not a path to a container runtime at all — it is a VOLUME
 * NAME, so {@code ca-bundle.crt} would silently create an empty named volume at the mount point,
 * which is precisely the outcome the worker's startup refusal exists to prevent, walking through
 * it. Absoluteness is syntax, and {@code path} below is checked the same way.
 */
public record HostMount(String hostPath, String path) {

    public HostMount {
        Objects.requireNonNull(hostPath, "hostPath");
        Objects.requireNonNull(path, "path");
        if (hostPath.isBlank()) {
            throw new IllegalArgumentException("a host mount must name a host path");
        }
        if (!isAbsolute(hostPath)) {
            throw new IllegalArgumentException("a host mount source must be an absolute path,"
                    + " was: " + hostPath + ". A relative source is a VOLUME NAME to a container"
                    + " runtime, not a file, so it would mount an empty volume where the file"
                    + " should be.");
        }
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("a host mount path must be absolute, was: " + path);
        }
    }

    /**
     * Both spellings a host path takes.
     *
     * <p>The Windows drive form is accepted because a developer runs the worker on the host it
     * drives; refusing it would make the guard fire on a correct configuration, which is the one
     * way a guard becomes something people disable.
     */
    private static boolean isAbsolute(String hostPath) {
        return hostPath.startsWith("/")
                || hostPath.matches("^[A-Za-z]:[\\\\/].*");
    }
}
