package dev.codespire.orchestrator.llm.catalog;

import dev.codespire.contract.llm.ModelParamProfile;
import dev.codespire.contract.review.TokenType;
import dev.codespire.llmcatalog.CatalogApiDialect;
import dev.codespire.llmcatalog.CatalogModel;
import dev.codespire.llmcatalog.CatalogPricing;
import dev.codespire.llmcatalog.CatalogValidator;
import dev.codespire.orchestrator.llm.ModelRate;
import dev.codespire.orchestrator.llm.PricingMode;

import java.util.EnumMap;
import java.util.Map;

/**
 * A catalog entry as the app's answer. Every catalog model is METERED; a type with no rate and no
 * {@code notBilled} mark gets no entry, so it stays unpriced. {@code cacheWrite1h} is not charged.
 */
final class CatalogEntries {

    private CatalogEntries() {
    }

    static ResolvedModel resolved(CatalogModel entry) {
        return new ResolvedModel(entry.modelId(), entry.provider(), entry.displayName(), ModelSource.CATALOG,
                PricingMode.METERED, rates(entry.pricing()), !entry.isRetired(), profile(entry.api()), entry);
    }

    static Map<TokenType, ModelRate> rates(CatalogPricing pricing) {
        Map<TokenType, ModelRate> rates = new EnumMap<>(TokenType.class);
        if (pricing == null) {
            return rates;
        }
        CatalogValidator.ratesByLedgerType(pricing).forEach((type, rate) -> {
            if (rate != null) {
                rates.put(TokenType.valueOf(type), ModelRate.rated(rate));
            }
        });
        for (String type : pricing.notBilled()) {
            rates.putIfAbsent(TokenType.valueOf(type), ModelRate.notBilled());
        }
        return rates;
    }

    private static ModelParamProfile profile(CatalogApiDialect api) {
        if (api == null || api.outputTokenParam() == null) {
            return ModelParamProfile.legacyDefault();
        }
        return new ModelParamProfile(ModelParamProfile.OutputTokenParam.valueOf(api.outputTokenParam().name()),
                api.temperature(), api.reasoningEffort(), Map.of());
    }
}
