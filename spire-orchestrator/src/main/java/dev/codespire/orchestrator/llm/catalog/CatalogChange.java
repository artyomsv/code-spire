package dev.codespire.orchestrator.llm.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.llmcatalog.CatalogModel;
import dev.codespire.llmcatalog.CatalogPricing;
import dev.codespire.llmcatalog.CatalogValidator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one accepted catalog file changed against the one before it (spec §6.4): models added, models
 * that became RETIRED, and models whose prices moved, with old and new rates. Stored as
 * {@code last_change} so an unexpected repricing is visible on the install, not only in git.
 */
public record CatalogChange(String oldSha, String newSha, List<String> added, List<String> retired,
        List<RePriced> rePriced) {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CACHE_WRITE_1H = "CACHE_WRITE_1H";
    private static final String NOT_BILLED = "notBilled";
    private static final int SHORT_SHA = 12;

    /** One model's rates by ledger type (plus CACHE_WRITE_1H), before and after. */
    public record RePriced(String modelId, Map<String, Object> before, Map<String, Object> after) {
    }

    /** What {@code after} changed against {@code before}, each named by its sha. */
    public static CatalogChange between(CatalogSnapshot before, CatalogSnapshot after) {
        Map<String, CatalogModel> earlier = new HashMap<>();
        before.models().forEach(model -> earlier.put(model.modelId(), model));
        List<String> added = new ArrayList<>();
        List<String> retired = new ArrayList<>();
        List<RePriced> rePriced = new ArrayList<>();
        for (CatalogModel model : after.models()) {
            CatalogModel was = earlier.get(model.modelId());
            if (was == null) {
                added.add(model.modelId());
                continue;
            }
            if (model.isRetired() && !was.isRetired()) {
                retired.add(model.modelId());
            }
            Map<String, Object> oldRates = rates(was.pricing());
            Map<String, Object> newRates = rates(model.pricing());
            if (!oldRates.equals(newRates)) {
                rePriced.add(new RePriced(model.modelId(), oldRates, newRates));
            }
        }
        return new CatalogChange(before.sha256(), after.sha256(), List.copyOf(added), List.copyOf(retired), List.copyOf(rePriced));
    }

    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A catalog change could not be written as JSON", e);
        }
    }

    /** The INFO line: the sha pair and the counts, never model text from a remote file. */
    public String summary() {
        return "LLM catalog " + shortSha(oldSha) + " -> " + shortSha(newSha) + ": " + added.size() + " added, "
                + retired.size() + " retired, " + rePriced.size() + " re-priced";
    }

    private static Map<String, Object> rates(CatalogPricing pricing) {
        Map<String, Object> rates = new LinkedHashMap<>();
        if (pricing == null) {
            return rates;
        }
        CatalogValidator.ratesByLedgerType(pricing).forEach((type, rate) -> {
            if (rate != null) {
                rates.put(type, rate);
            }
        });
        if (pricing.cacheWrite1h() != null) {
            rates.put(CACHE_WRITE_1H, pricing.cacheWrite1h());
        }
        if (!pricing.notBilled().isEmpty()) {
            rates.put(NOT_BILLED, pricing.notBilled());
        }
        return rates;
    }

    private static String shortSha(String sha) {
        return sha == null ? "none" : sha.substring(0, Math.min(SHORT_SHA, sha.length()));
    }
}
