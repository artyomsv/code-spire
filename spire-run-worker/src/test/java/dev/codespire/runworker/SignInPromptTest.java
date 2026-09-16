package dev.codespire.runworker;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reading the link and the code out of the vendor CLI's own paragraph (M3.5 part F).
 *
 * <p>The sample below is the SHAPE measured from {@code @openai/codex@0.146.0} on 2026-09-16, with a
 * placeholder code — a real one is a live authorization handle and does not belong in a repository.
 *
 * <p>The negative cases matter more than the positive one. This parses prose, so the question is not
 * "does it read today's output" but "what does it do when that output changes" — and the answer has to
 * be "finds nothing", never "finds something wrong". An operator sent to a guessed address is worse
 * than an operator told the sign-in did not start.
 */
class SignInPromptTest {

    private static final List<String> MEASURED_OUTPUT = List.of(
            "Welcome to Codex [v0.146.0]",
            "OpenAI's command-line coding agent",
            "",
            "Follow these steps to sign in with ChatGPT using device code authorization:",
            "",
            "1. Open this link in your browser and sign in to your account",
            "   https://auth.openai.com/codex/device",
            "",
            "2. Enter this one-time code (expires in 15 minutes)",
            "   ABCD-12345",
            "",
            "Continue only if you started this login in Codex. If a website or another person gave you"
                    + " this code, cancel.");

    private static SignInPrompt read(List<String> lines) {
        SignInPrompt prompt = new SignInPrompt();
        for (String line : lines) prompt.accept(line);
        return prompt;
    }

    @Test
    void theMeasuredOutputYieldsTheLinkTheCodeAndTheLifetime() {
        SignInPrompt prompt = read(MEASURED_OUTPUT);

        assertTrue(prompt.complete());
        assertEquals("https://auth.openai.com/codex/device", prompt.link());
        assertEquals("ABCD-12345", prompt.code());
        assertEquals(Optional.of(Duration.ofMinutes(15)), prompt.expiresIn());
    }

    /** It is complete as soon as both arrive, so the screen is not waiting on the closing paragraph. */
    @Test
    void itIsCompleteAsSoonAsTheOperatorHasWhatTheyNeed() {
        SignInPrompt prompt = new SignInPrompt();
        for (String line : MEASURED_OUTPUT.subList(0, 9)) assertFalse(prompt.accept(line), line);
        assertTrue(prompt.accept("   ABCD-12345"), "the code is the last thing the operator needs");
    }

    /** A vendor that stops printing a code leaves this incomplete, and the sign-in fails visibly. */
    @Test
    void anOutputWithNoCodeFindsNothingRatherThanSomethingWrong() {
        SignInPrompt prompt = read(List.of(
                "Follow these steps to sign in:",
                "   https://auth.openai.com/codex/device",
                "Open the link and approve this device."));

        assertFalse(prompt.complete());
        assertNull(prompt.code());
    }

    /**
     * A link inside a sentence is prose about the flow, not the address an operator is sent to.
     *
     * <p>Without the anchor, the first https in any sentence would be adopted — including one in a
     * banner, a warning, or a documentation pointer.
     */
    @Test
    void aLinkMentionedInASentenceIsNotTheAddressToVisit() {
        SignInPrompt prompt = read(List.of(
                "Read more at https://example.test/help before continuing",
                "   ABCD-12345"));

        assertNull(prompt.link());
        assertFalse(prompt.complete());
    }

    /** And a token inside a sentence is not the one-time code. */
    @Test
    void aCodeShapedWordInASentenceIsNotTheCode() {
        SignInPrompt prompt = read(List.of(
                "Your plan is ABCD-12345 and it renews monthly",
                "   https://auth.openai.com/codex/device"));

        assertNull(prompt.code());
        assertFalse(prompt.complete());
    }

    /** No stated lifetime is not a failure: the caller's own ceiling stands in. */
    @Test
    void aMissingExpiryIsAbsentRatherThanGuessed() {
        SignInPrompt prompt = read(List.of("   https://auth.openai.com/codex/device", "   ABCD-12345"));

        assertTrue(prompt.complete());
        assertEquals(Optional.empty(), prompt.expiresIn());
    }

    /** The first link and the first code win; a later line cannot move an operator mid-sign-in. */
    @Test
    void aSecondLinkOrCodeDoesNotReplaceTheFirst() {
        SignInPrompt prompt = read(List.of(
                "   https://auth.openai.com/codex/device",
                "   ABCD-12345",
                "   https://elsewhere.test/device",
                "   ZZZZ-99999"));

        assertEquals("https://auth.openai.com/codex/device", prompt.link());
        assertEquals("ABCD-12345", prompt.code());
    }
}
