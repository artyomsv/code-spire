package dev.codespire.runworker;

import dev.codespire.harness.HarnessAdapter;
import dev.codespire.harness.HarnessType;
import dev.codespire.harness.codex.CodexAdapter;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The harness arms this deployment can dispatch.
 *
 * <p>A composition root, and the only place a harness NAME appears. Everything else reads
 * {@link HarnessAdapter}, so adding an arm is a bean and an entry here rather than an edit to the
 * dispatch path — the same shape {@code ProviderClients} already uses for SCM adapters, and the
 * reason {@code CoreIsProviderNeutralTest} can allowlist composition roots by name.
 */
@ApplicationScoped
public class HarnessRegistry {

    private final Map<HarnessType, HarnessAdapter> arms = arms();

    private static Map<HarnessType, HarnessAdapter> arms() {
        Map<HarnessType, HarnessAdapter> byType = new LinkedHashMap<>();
        byType.put(HarnessType.CODEX, new CodexAdapter());
        return Map.copyOf(byType);
    }

    /**
     * @throws IllegalArgumentException naming what was asked for. A run dispatched to an arm this
     *                                  deployment does not have must fail loudly at the start
     *                                  rather than after a container has been created and paid for.
     */
    public HarnessAdapter forName(String harness) {
        if (harness == null || harness.isBlank()) {
            throw new IllegalArgumentException("a run must name its harness");
        }
        HarnessType type;
        try {
            type = HarnessType.valueOf(harness.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown harness: " + harness, e);
        }
        HarnessAdapter adapter = arms.get(type);
        if (adapter == null) {
            throw new IllegalArgumentException(
                    "harness " + type + " is known but not installed in this deployment");
        }
        return adapter;
    }
}
