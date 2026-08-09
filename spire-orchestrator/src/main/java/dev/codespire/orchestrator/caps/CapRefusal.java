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

    /** One line for the review timeline, where the operator is already looking. */
    public String detail() {
        if (reason == null) {
            return "";
        }
        return switch (reason) {
            case DIFF_TOO_LARGE -> "diff too large to review (" + measured + ")";
            case SPEND_CAP_REACHED -> "spend cap reached — " + measured + " of " + limit + " used";
            case CALL_CAP_REACHED -> "call cap reached — " + measured + " of " + limit + " calls used";
        };
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

    private static String dollars(long millicents) {
        return String.format("$%.2f", (double) millicents / MILLICENTS_PER_DOLLAR);
    }
}
