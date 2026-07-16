package dev.codespire.llm;

/** The parsed follow-up reply. Plan 1: just the answer text; Plan 2 adds a structured verdict. */
public record FollowUpAnswer(String text) {

    public static FollowUpAnswer of(String rawModelText) {
        return new FollowUpAnswer(rawModelText == null ? "" : rawModelText.trim());
    }
}
