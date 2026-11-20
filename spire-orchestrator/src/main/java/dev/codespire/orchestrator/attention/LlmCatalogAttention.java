package dev.codespire.orchestrator.attention;

import dev.codespire.contract.attention.AttentionView;
import dev.codespire.contract.attention.AttentionView.Severity;
import dev.codespire.contract.review.TokenType;
import dev.codespire.orchestrator.llm.catalog.LlmCatalogState;
import dev.codespire.orchestrator.llm.catalog.LlmCatalogStateRepository;
import dev.codespire.orchestrator.llm.catalog.LlmModelCatalog;
import dev.codespire.orchestrator.llm.catalog.ModelSource;
import io.quarkus.runtime.configuration.DurationConverter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The attention rows the model catalog adds: where a missing price is fixed when the model comes from the
 * catalog, and {@code LLM_CATALOG_STALE} when the configured URL has not answered for too long or served a
 * file that failed validation (spec §6.6, §7.6).
 */
@ApplicationScoped
public class LlmCatalogAttention {

    static final String STALE = "LLM_CATALOG_STALE";
    /** The prefix LlmCatalogRefresh writes for a file that failed validation. */
    static final String INVALID_PREFIX = "invalid: ";
    private static final int INTERVALS_BEFORE_STALE = 3;
    private static final Duration STALE_WHEN_INTERVAL_OFF = Duration.ofHours(24);
    /** The values Quarkus reads as "never run the schedule" for {@code every}. */
    private static final java.util.Set<String> SWITCHED_OFF = java.util.Set.of("off", "disabled");

    @Inject
    LlmModelCatalog catalog;

    @Inject
    LlmCatalogStateRepository states;

    @ConfigProperty(name = "spire.llm.catalog.refresh-interval")
    String refreshInterval;

    Clock clock = Clock.systemUTC();

    /**
     * The text for a pricing row when the model comes from the catalog, or empty for a manual or unknown
     * model, whose text stays "set rates in Settings". A read fault falls back to the manual text.
     */
    Optional<String> catalogText(String model, Collection<TokenType> unpriced) {
        if (model == null || model.isBlank() || !fromCatalog(model)) {
            return Optional.empty();
        }
        String types = unpriced.stream().map(Enum::name).collect(Collectors.joining(", "));
        return Optional.of("Model " + model + " comes from the catalog and has no price for " + types
                + ". Update the catalog, or add a manual model with this name.");
    }

    private boolean fromCatalog(String model) {
        try {
            return catalog.resolve(model).map(resolved -> resolved.source() == ModelSource.CATALOG).orElse(false);
        } catch (LlmModelCatalog.CatalogueReadFailed unreadable) {
            return false;
        }
    }

    /** {@code LLM_CATALOG_STALE}, when remote loading is on and the URL has not served a good file lately. */
    void staleRow(List<AttentionView> rows) {
        if (catalog.remoteLoadingOff()) {
            return;
        }
        LlmCatalogState state = states.read();
        boolean invalid = state.lastError() != null && state.lastError().startsWith(INVALID_PREFIX);
        if (!invalid && !tooLongSinceASuccess(state)) {
            return;
        }
        String error = state.lastError() == null ? "none recorded" : state.lastError();
        rows.add(new AttentionView(STALE, Severity.WARNING, catalog.url(),
                "The model catalog at " + catalog.url() + " has not been read successfully for a while (last error: "
                        + error + "). The last good catalog stays in use; check the URL, or set it to none to use"
                        + " the copy built into the image.", "/settings/llm"));
    }

    /**
     * Measured from the last 200 or 304, or, before the first one, from when the bundled copy was stored.
     * Nothing stored yet means the first read is still under way, which is not stale.
     */
    private boolean tooLongSinceASuccess(LlmCatalogState state) {
        Instant since = state.lastSuccessAt() != null ? state.lastSuccessAt() : state.loadedAt();
        if (since == null) {
            return false;
        }
        return Duration.between(since, clock.instant()).compareTo(staleAfter()) > 0;
    }

    private Duration staleAfter() {
        if (refreshInterval == null || SWITCHED_OFF.contains(refreshInterval.strip().toLowerCase(java.util.Locale.ROOT))) {
            return STALE_WHEN_INTERVAL_OFF;
        }
        return DurationConverter.parseDuration(refreshInterval.strip()).multipliedBy(INTERVALS_BEFORE_STALE);
    }
}
