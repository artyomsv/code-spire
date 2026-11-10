package dev.codespire.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What a unit's model proxy saw, read once after the agent has gone (harness reviews step 3).
 *
 * <p>{@code rejected} counts a 401 on a model call path only. A 401 elsewhere (a seat's account check) or any 403 (a
 * challenge, an entitlement answer) is not a refused credential, and marking a pool member on one would retire a
 * healthy seat until an operator replaces it. {@code refusedPaths} are the distinct targets the model proxy refused
 * before contacting the vendor, as it logs them: a fixed token ({@code <refused>}), never the target the agent sent,
 * which may name a person or hold a value.
 */
public record ModelProxyReport(int requests, int rejected, List<String> refusedPaths) {

    /**
     * The routes whose 401 means the vendor refused the injected credential. The model proxy logs a declared exact path
     * by name, so a harness names its model call exactly (Codex's seat route does, beside its prefix).
     */
    public static final Set<String> MODEL_CALL_PATHS = Set.of("/v1/responses", "/backend-api/codex/responses");

    /** A unit with no model proxy, or one whose log could not be read. */
    public static final ModelProxyReport NONE = new ModelProxyReport(0, 0, List.of());

    public ModelProxyReport {
        refusedPaths = List.copyOf(Objects.requireNonNull(refusedPaths, "refusedPaths"));
        if (requests < 0 || rejected < 0 || rejected > requests) {
            throw new IllegalArgumentException("a model proxy report counts requests (" + requests
                    + ") and the rejected among them (" + rejected + ")");
        }
    }
}
