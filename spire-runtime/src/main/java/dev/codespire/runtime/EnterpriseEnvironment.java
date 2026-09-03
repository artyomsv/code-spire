package dev.codespire.runtime;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a corporate deployment must add to EVERY container of a run unit (FR-F14).
 *
 * <p>A CA bundle and the proxy variables, injected at run time and never baked into an image. The
 * image is the operator's toolchain and is often shared, rebuilt or pulled from a registry; the
 * proxy and the trust store belong to the machine the unit runs on. Baking them in means a rebuild
 * to change a proxy, an image that cannot move between environments, and — for anything with a
 * credential in it — a secret in a layer that {@code docker image history} prints.
 *
 * <p><b>It lives on {@link RunUnitSpec} rather than on each {@link ContainerSpec}, and that is the
 * whole mechanism.</b> The requirement is "every container of the unit": with a per-container
 * field, spreading it correctly is the builder's discipline and forgetting one is a silent
 * omission — the init container missing the bundle is a clone that fails at the forge, which reads
 * like a credential problem. Held once at the unit level and folded in by
 * {@link RunUnitSpec#environmentFor} and {@link RunUnitSpec#hostMounts}, there is no per-container
 * decision to get wrong, and a future arm cannot apply it to two containers out of three.
 *
 * <p><b>The registry credential is deliberately NOT here.</b> It authenticates an image PULL, which
 * is the runtime's business, and everything on this record is handed to a container. Putting it
 * here would place a credential in the unit spec, where {@code docker inspect} on the created
 * container would print it — which is precisely what FR-F14 forbids. It is configured on the
 * runtime instead; see {@link RegistryCredential}.
 */
public record EnterpriseEnvironment(List<HostMount> mounts, Map<String, String> environment) {

    /** The ordinary deployment: no corporate CA, no proxy. */
    public static final EnterpriseEnvironment NONE = new EnterpriseEnvironment(List.of(), Map.of());

    public EnterpriseEnvironment {
        mounts = List.copyOf(Objects.requireNonNull(mounts, "mounts"));
        environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
    }

    /**
     * Values are redacted for the same reason {@link ContainerSpec}'s are.
     *
     * <p>Nothing here is expected to be a credential — a proxy URL, a no-proxy list, a path — but
     * a proxy URL with basic auth embedded is a supported and common corporate form
     * ({@code http://user:pass@proxy:3128}), so "expected" is not a property this can rely on.
     */
    @Override
    public String toString() {
        return "EnterpriseEnvironment[mounts=" + mounts
                + ", environment=" + environment.keySet() + " (values redacted)]";
    }
}
