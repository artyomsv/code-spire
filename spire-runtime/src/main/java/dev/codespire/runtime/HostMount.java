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
 * <p>{@code hostPath} is deliberately NOT validated for existence here. This is the pure SPI and it
 * has no filesystem; the worker refuses a missing path at startup instead, which is where a bad
 * value can still be corrected by an operator rather than discovered by a run — see
 * {@code EnterpriseEnvironmentConfig}. Docker silently CREATES a missing bind source as an empty
 * directory, so an unchecked path does not fail: it mounts a directory where a certificate file
 * should be, and every TLS call fails with an error naming neither.
 */
public record HostMount(String hostPath, String path) {

    public HostMount {
        Objects.requireNonNull(hostPath, "hostPath");
        Objects.requireNonNull(path, "path");
        if (hostPath.isBlank()) {
            throw new IllegalArgumentException("a host mount must name a host path");
        }
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("a host mount path must be absolute, was: " + path);
        }
    }
}
