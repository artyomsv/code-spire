package dev.codespire.orchestrator.llm;

import dev.codespire.llmcatalog.CatalogModel;
import dev.codespire.llmcatalog.CatalogPricing;
import dev.codespire.orchestrator.llm.catalog.CatalogSnapshot;
import dev.codespire.orchestrator.llm.catalog.ModelSource;
import dev.codespire.orchestrator.llm.catalog.ResolvedModel;
import dev.codespire.orchestrator.llm.catalog.LlmModelCatalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Catalog models as {@link LlmModelView} rows, beside the manual ones. */
final class CatalogModelViews {

    private CatalogModelViews() {
    }

    /** Every catalog model none of whose names a manual model has, as a read-only view. */
    static List<LlmModelView> unshadowed(CatalogSnapshot snapshot, List<LlmModelView> manualViews) {
        Set<String> manualNames = manualViews.stream().map(LlmModelView::name).collect(Collectors.toSet());
        List<LlmModelView> views = new ArrayList<>();
        for (CatalogModel entry : snapshot.models()) {
            if (Collections.disjoint(entry.names(), manualNames)) {
                views.add(view(LlmModelCatalog.asResolved(entry)));
            }
        }
        return views;
    }

    /**
     * Whether the name still resolves once its manual model is gone: the catalog has it, not retired,
     * with an input and an output price. Read from the catalog entry itself, not through the resolver,
     * which would still answer with the manual row being deleted.
     */
    static boolean stillResolvesAfterDelete(CatalogSnapshot snapshot, String name) {
        return snapshot.byName(name)
                .filter(entry -> !entry.isRetired())
                .map(CatalogModel::pricing)
                .filter(CatalogModelViews::hasInputAndOutput)
                .isPresent();
    }

    private static boolean hasInputAndOutput(CatalogPricing pricing) {
        return pricing.input() != null && pricing.output() != null;
    }

    private static LlmModelView view(ResolvedModel model) {
        Map<String, Long> rates = new LinkedHashMap<>();
        List<String> notBilled = new ArrayList<>();
        model.rates().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    if (entry.getValue().billed()) {
                        rates.put(entry.getKey().name(), entry.getValue().millicentsPerMillion());
                    } else {
                        notBilled.add(entry.getKey().name());
                    }
                });
        return new LlmModelView(null, model.type(), model.name(), model.label(), model.pricingMode().name(), rates,
                model.profile().outputTokenParam().name(), model.profile().supportsTemperature(),
                model.profile().reasoningEffort(), model.profile().extraParams(), model.enabled(), null,
                List.copyOf(notBilled), ModelSource.CATALOG.name());
    }
}
