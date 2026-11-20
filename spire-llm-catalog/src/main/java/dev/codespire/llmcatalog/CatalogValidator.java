package dev.codespire.llmcatalog;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rules a catalog file must meet before anyone prices with it. CI and the app run the same
 * rules; CI adds the change rules in {@link CatalogChangeRules}. Each problem names the model and the rule.
 */
public final class CatalogValidator {

    private static final Set<String> REASONING_EFFORTS = Set.of("low", "medium", "high");
    private static final Set<String> NEVER_NOT_BILLED = Set.of("INPUT", "OUTPUT");
    private static final String HTTPS = "https://";

    private CatalogValidator() {
    }

    /** Every problem in the document; an empty list means it is valid. */
    public static List<String> problems(CatalogDocument doc, LocalDate today) {
        List<String> problems = new ArrayList<>();
        Set<String> providers = providerProblems(doc.providers(), problems);
        Set<String> names = new HashSet<>();
        for (CatalogModel model : doc.models()) {
            modelProblems(model, providers, names, problems);
            if (model.pricing() == null) {
                problems.add(label(model) + ": pricing is required");
            } else {
                pricingProblems(model, today, problems);
            }
        }
        return List.copyOf(problems);
    }

    private static Set<String> providerProblems(List<CatalogProvider> providers, List<String> problems) {
        Set<String> codes = new HashSet<>();
        for (CatalogProvider provider : providers) {
            if (provider.code() == null || !Vendors.TYPES.contains(provider.code())) {
                problems.add("provider " + provider.code() + ": code must be one of " + Vendors.TYPES);
            } else if (!codes.add(provider.code())) {
                problems.add("provider " + provider.code() + ": listed twice");
            }
        }
        return codes;
    }

    private static void modelProblems(CatalogModel model, Set<String> providers, Set<String> names,
            List<String> problems) {
        if (model.modelId() == null || model.modelId().isBlank()) {
            problems.add("a model has no modelId");
            return;
        }
        if (!providers.contains(model.provider())) {
            problems.add(label(model) + ": provider " + model.provider() + " is not a listed provider");
        }
        for (String name : model.names()) {
            if (name == null || name.isBlank() || !names.add(name)) {
                problems.add(label(model) + ": name " + name + " is blank or already used by another model");
            }
        }
        if (model.lifecycle() == null || model.lifecycle().status() == null) {
            problems.add(label(model) + ": lifecycle.status is required");
        }
        apiProblems(model, problems);
    }

    private static void apiProblems(CatalogModel model, List<String> problems) {
        if (model.api() == null || model.api().outputTokenParam() == null) {
            problems.add(label(model) + ": api.outputTokenParam is required");
            return;
        }
        String effort = model.api().reasoningEffort();
        if (effort != null && !REASONING_EFFORTS.contains(effort)) {
            problems.add(label(model) + ": api.reasoningEffort must be null, low, medium or high");
        }
    }

    private static void pricingProblems(CatalogModel model, LocalDate today, List<String> problems) {
        CatalogPricing pricing = model.pricing();
        if (pricing.input() == null) {
            problems.add(label(model) + ": pricing.input is required");
        }
        if (pricing.output() == null) {
            problems.add(label(model) + ": pricing.output is required");
        }
        rates(pricing).forEach((field, rate) -> rateProblem(model, field, rate, problems));
        rateProblem(model, "cacheWrite1h", pricing.cacheWrite1h(), problems);
        notBilledProblems(model, problems);
        if (pricing.sourceUrl() == null || !pricing.sourceUrl().startsWith(HTTPS)) {
            problems.add(label(model) + ": pricing.sourceUrl must be an https URL");
        }
        if (pricing.observedAt() == null || pricing.observedAt().isAfter(today)) {
            problems.add(label(model) + ": pricing.observedAt is required and may not be in the future");
        }
    }

    private static void rateProblem(CatalogModel model, String field, Long rate, List<String> problems) {
        if (rate != null && (rate <= 0 || rate > CatalogLimits.MAX_RATE_MILLICENTS_PER_MILLION)) {
            problems.add(label(model) + ": pricing." + field + " must be positive and at most "
                    + CatalogLimits.MAX_RATE_MILLICENTS_PER_MILLION);
        }
    }

    private static void notBilledProblems(CatalogModel model, List<String> problems) {
        Map<String, Long> byType = ratesByLedgerType(model.pricing());
        for (String type : model.pricing().notBilled()) {
            if (!CatalogLimits.LEDGER_TYPES.contains(type)) {
                problems.add(label(model) + ": notBilled type " + type + " is not one of " + CatalogLimits.LEDGER_TYPES);
            } else if (NEVER_NOT_BILLED.contains(type)) {
                problems.add(label(model) + ": " + type + " may not be notBilled");
            } else if (byType.get(type) != null) {
                problems.add(label(model) + ": notBilled type " + type + " may not also carry a rate");
            }
        }
    }

    /** Rates by catalog field name, in file order. */
    private static Map<String, Long> rates(CatalogPricing pricing) {
        Map<String, Long> rates = new LinkedHashMap<>();
        rates.put("input", pricing.input());
        rates.put("output", pricing.output());
        rates.put("cacheRead", pricing.cacheRead());
        rates.put("cacheWrite", pricing.cacheWrite());
        rates.put("reasoning", pricing.reasoning());
        return rates;
    }

    /** Rates by the ledger token type each field is charged as. */
    public static Map<String, Long> ratesByLedgerType(CatalogPricing pricing) {
        Map<String, Long> rates = new LinkedHashMap<>();
        rates.put("INPUT", pricing.input());
        rates.put("CACHED_INPUT", pricing.cacheRead());
        rates.put("CACHE_WRITE", pricing.cacheWrite());
        rates.put("OUTPUT", pricing.output());
        rates.put("REASONING", pricing.reasoning());
        return rates;
    }

    private static String label(CatalogModel model) {
        return "model " + model.modelId();
    }
}
