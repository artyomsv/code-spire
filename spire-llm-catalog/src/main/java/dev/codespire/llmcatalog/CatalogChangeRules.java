package dev.codespire.llmcatalog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The rules a catalog change must meet against the published file. CI only: the app has no base to
 * compare with. A model is never removed (it is RETIRED instead), and a changed price says when it
 * was read by changing {@code observedAt}.
 */
public final class CatalogChangeRules {

    private CatalogChangeRules() {
    }

    public static List<String> problems(CatalogDocument base, CatalogDocument head) {
        Map<String, CatalogModel> heads = new HashMap<>();
        for (CatalogModel model : head.models()) {
            heads.put(model.modelId(), model);
        }
        List<String> problems = new ArrayList<>();
        for (CatalogModel before : base.models()) {
            CatalogModel after = heads.get(before.modelId());
            if (after == null) {
                problems.add("model " + before.modelId() + " was removed; retire it with lifecycle.status: RETIRED");
            } else if (priceChanged(before.pricing(), after.pricing()) && sameObservedAt(before, after)) {
                problems.add("model " + before.modelId() + ": a price changed but pricing.observedAt did not");
            }
        }
        return List.copyOf(problems);
    }

    private static boolean priceChanged(CatalogPricing before, CatalogPricing after) {
        if (before == null || after == null) {
            return before != after;
        }
        return !Arrays.equals(rateFields(before), rateFields(after))
                || !Objects.equals(before.notBilled(), after.notBilled());
    }

    private static Long[] rateFields(CatalogPricing pricing) {
        return new Long[] {pricing.input(), pricing.output(), pricing.cacheRead(), pricing.cacheWrite(),
                pricing.cacheWrite1h(), pricing.reasoning()};
    }

    private static boolean sameObservedAt(CatalogModel before, CatalogModel after) {
        if (before.pricing() == null || after.pricing() == null) {
            return false;
        }
        return Objects.equals(before.pricing().observedAt(), after.pricing().observedAt());
    }
}
