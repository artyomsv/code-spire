package dev.codespire.orchestrator.llm;

/**
 * The default LLM provider's packed credential, or the reason a paid call must not be made with it.
 *
 * <p><b>One answer rather than two, on purpose.</b> "Resolve the default credential" and "confirm the
 * model can be priced" used to be separate calls, and the second was easy to forget: the review path
 * asked both, while the conversation path resolved a credential and emitted a paid
 * {@code AnswerFollowUp} with no price check at all. Answering both together makes the next emit site
 * guarded by construction instead of by remembering.
 *
 * @param packed  the encrypted credential, null exactly when {@code refusal} is set
 * @param model   the model that would have been spent on; blank when there is no provider to name
 * @param refusal why it must not be spent, null exactly when {@code packed} is set
 */
public record DefaultLlm(String packed, String model, Refusal refusal) {

    /** Why a paid call is refused. Two causes with different fixes, so they are never conflated. */
    public enum Refusal {
        /** No enabled provider is marked as the global default. */
        NO_DEFAULT_PROVIDER,
        /**
         * The default provider names a model the ledger cannot price. Reachable without passing the
         * registry guard: V30 leaves every legacy zero-priced model rateless, so an upgraded
         * deployment is in this state before an operator touches anything.
         */
        MODEL_NOT_PRICEABLE
    }

    public DefaultLlm {
        if ((packed == null) == (refusal == null)) {
            throw new IllegalArgumentException(
                    "A default LLM is either spendable or refused, never both and never neither");
        }
        model = model == null ? "" : model;
    }

    public static DefaultLlm spendable(String packed, String model) {
        return new DefaultLlm(packed, model, null);
    }

    public static DefaultLlm noDefaultProvider() {
        return new DefaultLlm(null, "", Refusal.NO_DEFAULT_PROVIDER);
    }

    public static DefaultLlm notPriceable(String model) {
        return new DefaultLlm(null, model, Refusal.MODEL_NOT_PRICEABLE);
    }

    public boolean isSpendable() {
        return refusal == null;
    }

    /**
     * One line for the review timeline, so a skipped paid call is visible where the operator is already
     * looking. Both emit sites take it from here, so they cannot describe the same refusal differently.
     */
    public String detail() {
        if (refusal == null) {
            return "";
        }
        return switch (refusal) {
            case NO_DEFAULT_PROVIDER -> "no default LLM provider configured";
            case MODEL_NOT_PRICEABLE -> "model '" + model + "' has no usable pricing";
        };
    }

    /** The same fact as a dashboard note, naming the fix — a silent skip is what this project got burned by. */
    public String note() {
        if (refusal == null) {
            return "";
        }
        return switch (refusal) {
            case NO_DEFAULT_PROVIDER ->
                    "No default LLM provider configured — register one in Settings → LLM.";
            case MODEL_NOT_PRICEABLE -> "Model '" + model + "' has no usable pricing — set input and"
                    + " output rates in Settings → LLM → Models, or mark it UNMETERED if it is"
                    + " self-hosted.";
        };
    }
}
