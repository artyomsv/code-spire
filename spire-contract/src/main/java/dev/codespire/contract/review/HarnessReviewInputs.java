package dev.codespire.contract.review;

import java.util.List;
import java.util.Map;

/**
 * The files a harness review's agent reads at {@code /inputs} (harness reviews, step 6): {@code context.md} and, on a
 * re-review, {@code prior-findings.md}. Plain text on the bus, as findings already are (ADR-014); never a diff
 * (ADR-011).
 */
public record HarnessReviewInputs(List<InputFile> files) {

    /** The total UTF-8 bytes of every file's text. */
    public static final int MAX_BYTES = 224 * 1024;

    public static final String CONTEXT = "context.md";

    public static final String PRIOR_FINDINGS = "prior-findings.md";

    public static final HarnessReviewInputs NONE = new HarnessReviewInputs(List.of());

    public record InputFile(String name, String text) {
    }

    public Map<String, String> asMap() {
        throw new UnsupportedOperationException("not built yet");
    }
}
