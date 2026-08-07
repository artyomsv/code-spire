package dev.codespire.orchestrator.llm;

import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.TokenCount;
import dev.codespire.contract.review.TokenType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Prices a call's token partition against the catalog. Split out of {@link LlmModelRegistry} to keep
 * that class focused on CRUD — pricing is a read-only lookup with its own failure semantics (a lookup
 * fault must resolve to {@link PricingMode#UNKNOWN}, never a coerced zero), not a write-path concern.
 */
@ApplicationScoped
public class LlmModelPricer {

    private static final Logger LOG = Logger.getLogger(LlmModelPricer.class);

    @Inject
    DataSource dataSource;

    @Inject
    LlmModelRateRepository rateRepository;

    /**
     * Price one call's token partition into charge lines.
     *
     * <p>Never returns a zero cost for a price it could not find. The method this replaced answered
     * {@code 0L} for an uncatalogued model, a blank model name AND a SQLException, so a momentary
     * database fault wrote a permanent "this call was free".
     */
    public List<ChargeLine> priceCall(String model, ModelUsage usage) {
        List<TokenCount> counts = usage == null ? List.of() : usage.counts();
        if (counts.isEmpty()) {
            // Reachable with a NON-NULL usage: TokenUsageMapper.map returns an all-zero ModelUsage
            // (empty counts, reconciled=true) when a vendor reports every token dimension as zero or
            // missing — an OpenAI-compatible gateway answering prompt_tokens=0, completion_tokens=0
            // does exactly this. Deleting this branch does NOT throw on that path: pricingFor would
            // still run, the stream below would yield an empty charge-line list, recordCharges would
            // write zero rows, and the call would vanish from the ledger SILENTLY — no row, no error,
            // no attention row. That silent disappearance is the failure this whole change exists to
            // prevent. A null usage (no model name to write into the NOT NULL llm_charge.model) is the
            // secondary, call-site-guarded half of this branch, not the reachable one.
            //
            // A trap in both directions, so neither half may be removed on the strength of the other:
            // deleting this branch drops a live call from the ledger with no trace, and dropping a
            // call-site guard as "redundant because priceCall handles null" reinstates the
            // NPE-into-cs.dlq it was added to stop — the guard is what keeps the warning and the
            // dead-letter avoidance, not this branch.
            return List.of(ChargeLine.unknown(TokenType.TOTAL, 0));
        }
        Pricing pricing = pricingFor(model);
        // The catalog is consulted BEFORE the reconciled check, because an UNMETERED model's cost is an
        // asserted zero whatever the split turns out to be.
        if (pricing.mode() == PricingMode.UNMETERED) {
            return usage.reconciled()
                    ? counts.stream().map(count -> line(pricing, count)).toList()
                    : List.of(ChargeLine.unmetered(TokenType.TOTAL, usage.reportedTotal()));
        }
        // An unreconciled call has no split, so no per-type rate can be applied to it.
        if (!usage.reconciled()) {
            return List.of(ChargeLine.unknown(TokenType.TOTAL, usage.reportedTotal()));
        }
        return counts.stream().map(count -> line(pricing, count)).toList();
    }

    /** Whether a review may be started against this model: priceable, or explicitly unbilled. */
    public boolean isPriceable(String model) {
        Pricing pricing = pricingFor(model);
        if (pricing.mode() == PricingMode.UNMETERED) {
            return true;
        }
        return pricing.mode() == PricingMode.METERED
                && LlmModelPricingValidator.REQUIRED_RATES.stream().allMatch(pricing.rates()::containsKey);
    }

    private static ChargeLine line(Pricing pricing, TokenCount count) {
        if (pricing.mode() == PricingMode.UNMETERED) {
            return ChargeLine.unmetered(count.type(), count.tokens());
        }
        Long rate = pricing.rates().get(count.type());
        if (pricing.mode() == PricingMode.UNKNOWN || rate == null) {
            return ChargeLine.unknown(count.type(), count.tokens());
        }
        return ChargeLine.metered(count.type(), count.tokens(), rate);
    }

    /** What the catalog says about a model's pricing; UNKNOWN with no rates when it cannot be read. */
    private record Pricing(PricingMode mode, Map<TokenType, Long> rates) {
        static final Pricing UNKNOWN = new Pricing(PricingMode.UNKNOWN, Map.of());
    }

    private Pricing pricingFor(String model) {
        if (model == null || model.isBlank()) {
            return Pricing.UNKNOWN;
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, pricing_mode FROM llm_model WHERE name = ?")) {
            ps.setString(1, model);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Pricing.UNKNOWN;
                }
                UUID id = rs.getObject("id", UUID.class);
                PricingMode mode = PricingMode.valueOf(rs.getString("pricing_mode"));
                return new Pricing(mode, rateRepository.ratesFor(c, id));
            }
        } catch (SQLException e) {
            // Deliberately NOT a zero. A transient fault must not become permanent silent corruption.
            LOG.errorf(e, "Pricing lookup failed for model %s — recording the call as unpriced", model);
            return Pricing.UNKNOWN;
        }
    }
}
