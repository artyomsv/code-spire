package dev.codespire.orchestrator.caps;

/**
 * Why a gate refused to spend, in words an operator reads on the timeline.
 *
 * <p>Modelled on {@code DefaultLlm}, whose javadoc records why one vocabulary matters: two emit sites
 * once described the same refusal differently. Deliberately NOT an extension of
 * {@code DefaultLlm.Refusal}, which answers "can this LLM be used" — a cap refusal is a budget policy
 * decision, and folding them together would drag budget logic into credential resolution.
 */
public record CapRefusal(Reason reason, String measured, String limit) {

    public enum Reason {
        DIFF_TOO_LARGE,
        SPEND_CAP_REACHED,
        CALL_CAP_REACHED
    }

    /** Millicents per dollar — money is stored in millicents and shown in dollars. */
    private static final long MILLICENTS_PER_DOLLAR = 100_000L;

    public static CapRefusal allow() {
        return new CapRefusal(null, "", "");
    }

    public static CapRefusal diffTooLarge(int changedFiles, long sizeBytes) {
        return new CapRefusal(Reason.DIFF_TOO_LARGE,
                changedFiles + " files / " + sizeBytes + " bytes", "");
    }

    public static CapRefusal spendCapReached(long spentMillicents, long capMillicents) {
        return new CapRefusal(Reason.SPEND_CAP_REACHED,
                dollars(spentMillicents), dollars(capMillicents));
    }

    public static CapRefusal callCapReached(int calls, int cap) {
        return new CapRefusal(Reason.CALL_CAP_REACHED, String.valueOf(calls), String.valueOf(cap));
    }

    public boolean allowed() {
        return reason == null;
    }

    public boolean refused() {
        return reason != null;
    }

    /**
     * One line for the review timeline, where the operator is already looking.
     *
     * <p>Names the MEASURED figure and never the configured limit. The three surfaces this reaches —
     * the timeline, the review's note and the {@code CAP_REACHED} attention row — are all readable by
     * {@code spire-viewer}, and ADR-022's third rule makes configuration admin-only <em>including its
     * reads</em>: a cap is the deployment's spend policy, not a fact about this review. What is measured
     * here IS this review's own context and stays. The precedent is in this repository — ADR-023's
     * review round dropped {@code rateMillicentsPerMillion} from {@code ReviewDetail} for exactly this.
     * The limit is still carried on the record and rendered by {@link #logDetail()}.
     */
    public String detail() {
        if (reason == null) {
            return "";
        }
        return switch (reason) {
            case DIFF_TOO_LARGE -> "diff too large to review (" + measured + ")";
            case SPEND_CAP_REACHED -> "spend cap reached — " + measured + " spent in the current window";
            case CALL_CAP_REACHED -> "call cap reached — " + measured + " calls in the current window";
        };
    }

    /**
     * {@link #detail()} plus the configured limit, for the log only — which no viewer reads, and which
     * is where an operator diagnosing a refusal needs both halves of the comparison.
     *
     * <p>Deliberately a second rendering rather than a second vocabulary: it is {@code detail()} with a
     * suffix, so the two can never describe the same refusal differently — the drift this record was
     * created to prevent.
     */
    public String logDetail() {
        if (reason == null || limit.isEmpty()) {
            return detail();
        }
        return detail() + " (configured limit: " + limit + ")";
    }

    /** The review's note field, which says what the operator can DO about it. */
    public String note() {
        if (reason == null) {
            return "";
        }
        return switch (reason) {
            case DIFF_TOO_LARGE -> "Not reviewed: " + detail()
                    + ". Raise the diff limit in Settings -> General, or split the pull request.";
            case SPEND_CAP_REACHED, CALL_CAP_REACHED -> "Not reviewed: " + detail()
                    + ". Capacity returns as older usage ages out, or raise the cap in Settings -> General.";
        };
    }

    // Locale.ROOT because this is a money figure in operator-facing text that is also asserted in
    // tests: a de-DE default renders "$0,01". The repo's other String.format passes it for the same
    // reason (ReviewProjection).
    private static String dollars(long millicents) {
        return String.format(java.util.Locale.ROOT, "$%.2f", (double) millicents / MILLICENTS_PER_DOLLAR);
    }
}
