package dev.codespire.orchestrator.llm;

import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.TokenCount;
import dev.codespire.contract.llm.HarnessTokenReport;
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

    /**
     * A call paid for by a subscription rather than per token (M3.5 part F): rate 0 and cost 0, with the
     * real token counts, whatever the model's own pricing says. Priced by how the call PAID rather than by
     * model, so one model can serve an API-key run and a subscription run in the same deployment.
     *
     * <p>Missing usage stays UNKNOWN, exactly as in {@link #priceCall}: a subscription makes a reported
     * call cost zero; it does not make an unreported one free.
     */
    public List<ChargeLine> priceUnmetered(ModelUsage usage) {
        List<TokenCount> counts = usage == null ? List.of() : usage.counts();
        if (counts.isEmpty()) return List.of(ChargeLine.unknown(TokenType.TOTAL, 0));
        if (!usage.reconciled()) return List.of(ChargeLine.unmetered(TokenType.TOTAL, usage.reportedTotal()));
        return counts.stream().map(count -> ChargeLine.unmetered(count.type(), count.tokens())).toList();
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

    /**
     * Whether a HARNESS run may start against this model: every token type that harness can report has
     * a rate or an operator's not-billed assertion.
     *
     * <p>The weaker question — {@link #isPriceable(String)}, which asks only about INPUT and OUTPUT —
     * is what let work item 36 start, spend, report CACHED_INPUT and REASONING, and then stop with an
     * unknown cost. A harness reports what it reports; asking after the money is gone is too late.
     */
    public boolean isPriceable(String model, String harness) {
        return unpricedTypes(model, harness).isEmpty();
    }

    /**
     * The token types this harness can report that this model cannot price, in a stable order, so a
     * refusal can name what to enter instead of saying "pricing".
     */
    public List<TokenType> unpricedTypes(String model, String harness) {
        Pricing pricing = pricingFor(model);
        if (pricing.mode() == PricingMode.UNMETERED) {
            return List.of();
        }
        if (pricing.mode() != PricingMode.METERED) {
            return List.copyOf(HarnessTokenReport.reportedBy(harness).stream().sorted().toList());
        }
        return HarnessTokenReport.reportedBy(harness).stream().sorted()
                .filter(type -> !pricing.rates().containsKey(type)).toList();
    }

    private static ChargeLine line(Pricing pricing, TokenCount count) {
        if (pricing.mode() == PricingMode.UNMETERED) {
            return ChargeLine.unmetered(count.type(), count.tokens());
        }
        ModelRate known = pricing.rates().get(count.type());
        if (pricing.mode() == PricingMode.UNKNOWN || known == null) {
            return ChargeLine.unknown(count.type(), count.tokens());
        }
        // An asserted zero: the operator said this vendor bills nothing for this type. It is a measured
        // line with a zero cost, not an unpriced one, and the ledger's own check requires that shape.
        if (!known.billed()) {
            return ChargeLine.unmetered(count.type(), count.tokens());
        }
        long rate = known.millicentsPerMillion();
        try {
            return ChargeLine.metered(count.type(), count.tokens(), rate);
        } catch (ArithmeticException overflow) {
            // A rate stored before MAX_RATE_MILLICENTS_PER_MILLION existed. UNKNOWN for the same reason a
            // lookup fault is: the cost could not be established, and inventing one — the wrapped
            // negative this replaces, or a saturated maximum — would be a number nobody can trace to a
            // real charge. Letting the throw escape instead would dead-letter the whole result event,
            // losing the review's findings over one unpriceable line.
            LOG.errorf(overflow, "Rate %d for %s on this call is too large to price (%d tokens) —"
                    + " recording the line as unpriced; correct the rate in Settings → LLM → Models",
                    rate, count.type(), count.tokens());
            return ChargeLine.unknown(count.type(), count.tokens());
        }
    }

    /** What the catalog says about a model's pricing; UNKNOWN with no rates when it cannot be read. */
    private record Pricing(PricingMode mode, Map<TokenType, ModelRate> rates) {
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
