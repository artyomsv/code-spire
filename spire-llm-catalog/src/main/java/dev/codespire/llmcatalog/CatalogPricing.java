package dev.codespire.llmcatalog;

import java.time.LocalDate;
import java.util.List;

/**
 * Rates in millicents per 1M tokens. A null rate with no {@code notBilled} mark is unpriced.
 * {@code cacheWrite1h} is recorded, not charged: the ledger has one cache-write type.
 */
public record CatalogPricing(Long input, Long output, Long cacheRead, Long cacheWrite, Long cacheWrite1h,
        Long reasoning, List<String> notBilled, String sourceUrl, LocalDate observedAt) {

    public CatalogPricing {
        notBilled = notBilled == null ? List.of() : List.copyOf(notBilled);
    }
}
