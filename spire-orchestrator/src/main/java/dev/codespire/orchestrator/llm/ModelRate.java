package dev.codespire.orchestrator.llm;

/**
 * What the catalog knows about one token type's price: a rate, or an operator's assertion that the
 * vendor does not bill it (M3.5 part D, decision 4B).
 *
 * <p>These are three states, not two, and keeping them apart is the whole point. A rate is a price
 * somebody read off a vendor's page. An assertion is a person saying "this vendor charges nothing for
 * this". A type with no entry at all is nobody having said — and it stays UNKNOWN, because a run that
 * reports it has spent something this deployment cannot account for. Collapsing the third into the
 * second is how a fabricated zero enters a ledger and quietly shrinks every total computed over it.
 */
public record ModelRate(Long millicentsPerMillion, boolean billed) {

    public ModelRate {
        if (billed && (millicentsPerMillion == null || millicentsPerMillion <= 0))
            throw new IllegalArgumentException("A billed rate is a price above zero");
        if (!billed && millicentsPerMillion != null)
            throw new IllegalArgumentException("A type the vendor does not bill carries no rate");
    }

    public static ModelRate rated(long millicentsPerMillion) {
        return new ModelRate(millicentsPerMillion, true);
    }

    /** The operator asserts the vendor bills nothing for this type. An asserted zero, never a coerced one. */
    public static ModelRate notBilled() {
        return new ModelRate(null, false);
    }
}
