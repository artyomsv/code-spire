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
 * <p><b>The negative cases are the point.</b> An operator is told to go to whatever this returns and
 * type their account credentials there, so the question is not "does it read today's output" but "what
 * does it do when the output is not today's" — and the answer has to be "finds nothing", never "finds
 * something plausible". The three attacks below are the ones review found in the first version.
 */
class SignInPromptTest {

    private static final String HOST = "auth.openai.com";
    private static final String REAL_LINK = "https://auth.openai.com/codex/device";

    private static final List<String> MEASURED_OUTPUT = List.of(
            "Welcome to Codex [v0.146.0]",
            "OpenAI's command-line coding agent",
            "",
            "Follow these steps to sign in with ChatGPT using device code authorization:",
            "",
            "1. Open this link in your browser and sign in to your account",
            "   " + REAL_LINK,
            "",
            "2. Enter this one-time code (expires in 15 minutes)",
            "   ABCD-12345",
            "",
            "Continue only if you started this login in Codex. If a website or another person gave you"
                    + " this code, cancel.");

    private static SignInPrompt read(List<String> lines) {
        SignInPrompt prompt = new SignInPrompt(HOST);
        for (String line : lines) prompt.accept(line);
        return prompt;
    }

    @Test
    void theMeasuredOutputYieldsTheLinkTheCodeAndTheLifetime() {
        SignInPrompt prompt = read(MEASURED_OUTPUT);

        assertTrue(prompt.complete());
        assertFalse(prompt.ambiguous());
        assertEquals(REAL_LINK, prompt.link());
        assertEquals("ABCD-12345", prompt.code());
        assertEquals(Optional.of(Duration.ofMinutes(15)), prompt.expiresIn());
    }

    /** It is complete as soon as both arrive, so the screen is not waiting on the closing paragraph. */
    @Test
    void itIsCompleteAsSoonAsTheOperatorHasWhatTheyNeed() {
        SignInPrompt prompt = new SignInPrompt(HOST);
        for (String line : MEASURED_OUTPUT.subList(0, 9)) assertFalse(prompt.accept(line), line);
        assertTrue(prompt.accept("   ABCD-12345"), "the code is the last thing the operator needs");
    }

    /**
     * ATTACK 1: a decoy printed before the real link.
     *
     * <p>The first version took the first standalone https line, so a help link printed above the
     * instructions was adopted and then paired with the genuine code. The operator would have typed a
     * real one-time code into somebody else's page.
     */
    @Test
    void aDecoyLinkPrintedFirstIsNotAdopted() {
        SignInPrompt prompt = read(List.of(
                "For help, visit:",
                "   https://support.example.test/device",
                "1. Open this link in your browser and sign in to your account",
                "   " + REAL_LINK,
                "2. Enter this one-time code (expires in 15 minutes)",
                "   ABCD-12345"));

        assertEquals(REAL_LINK, prompt.link(), "only the arm's own device page may be shown");
        assertTrue(prompt.complete());
    }

    /**
     * ATTACK 2: an address whose real authority is somewhere else.
     *
     * <p>Everything before the {@code @} is userinfo, not a host. It reads as {@code auth.openai.com}
     * to a person and addresses {@code attacker.example} to a browser, which is the entire trick.
     */
    @Test
    void anAddressThatOnlyLooksLikeTheRightHostIsRefused() {
        SignInPrompt prompt = read(List.of(
                "   https://auth.openai.com@attacker.example/device",
                "   ABCD-12345"));

        assertNull(prompt.link(), "the authority is attacker.example, whatever it reads like");
        assertFalse(prompt.complete());
    }

    /**
     * ATTACK 4: the right host, another port.
     *
     * <p>Found by review after the first three were closed. The host matched exactly, so everything
     * about the address read as correct — but a different port is a different service, and
     * {@code SignInFlow} declares a host with no port precisely because only one is the device page.
     */
    @Test
    void theRightHostOnAnotherPortIsNotTheDevicePage() {
        SignInPrompt prompt = read(List.of("   https://auth.openai.com:8443/codex/device", "   ABCD-12345"));

        assertNull(prompt.link());
        assertFalse(prompt.complete());
    }

    /** And 443 written out is the same page, not a different one. */
    @Test
    void theDefaultPortSpelledOutIsStillTheDevicePage() {
        assertEquals("https://auth.openai.com:443/codex/device",
                read(List.of("   https://auth.openai.com:443/codex/device", "   ABCD-12345")).link());
    }

    /** And the neighbouring trick: a longer host that merely starts with the right name. */
    @Test
    void aHostThatMerelyStartsWithTheRightNameIsRefused() {
        assertNull(read(List.of("   https://auth.openai.com.attacker.example/device", "   ABCD-12345")).link());
        assertNull(read(List.of("   http://auth.openai.com/codex/device", "   ABCD-12345")).link(),
                "plain http is not the page either");
    }

    /**
     * ATTACK 3: a banner token shaped like a code.
     *
     * <p>{@code BETA-2026} matches the code's shape exactly. What excludes it is ORDER: nothing before
     * the verified link can be the code, and the flow prints the link first.
     */
    @Test
    void aCodeShapedBannerPrintedBeforeTheLinkIsNotTheCode() {
        SignInPrompt prompt = read(List.of(
                "   BETA-2026",
                "   " + REAL_LINK,
                "   ABCD-12345"));

        assertEquals("ABCD-12345", prompt.code());
    }

    /** Two different addresses for one flow is not a choice to make on somebody's behalf. */
    @Test
    void twoDifferentDeviceAddressesAreRefusedRatherThanPickedBetween() {
        SignInPrompt prompt = read(List.of(
                "   " + REAL_LINK,
                "   https://auth.openai.com/other/device",
                "   ABCD-12345"));

        assertTrue(prompt.ambiguous());
        assertFalse(prompt.complete(), "a sign-in nobody can resolve must fail, not guess");
    }

    /** Same rule for the code. */
    @Test
    void twoDifferentCodesAreRefusedRatherThanPickedBetween() {
        SignInPrompt prompt = read(List.of("   " + REAL_LINK, "   ABCD-12345", "   ZZZZ-99999"));

        assertTrue(prompt.ambiguous());
        assertFalse(prompt.complete());
    }

    /** The same address twice is one address, not two. A repeat must not look like an attack. */
    @Test
    void theSameLinkAndCodeRepeatedIsNotAmbiguous() {
        SignInPrompt prompt = read(List.of("   " + REAL_LINK, "   " + REAL_LINK, "   ABCD-12345", "   ABCD-12345"));

        assertFalse(prompt.ambiguous());
        assertTrue(prompt.complete());
    }

    /** A vendor that stops printing a code leaves this incomplete, and the sign-in fails visibly. */
    @Test
    void anOutputWithNoCodeFindsNothingRatherThanSomethingWrong() {
        SignInPrompt prompt = read(List.of("Follow these steps to sign in:", "   " + REAL_LINK));

        assertFalse(prompt.complete());
        assertNull(prompt.code());
    }

    /** A link inside a sentence is prose about the flow, not the address to visit. */
    @Test
    void aLinkMentionedInASentenceIsNotTheAddressToVisit() {
        SignInPrompt prompt = read(List.of("Read more at " + REAL_LINK + " before continuing", "   ABCD-12345"));

        assertNull(prompt.link());
        assertFalse(prompt.complete());
    }

    /** No stated lifetime is not a failure: the caller's own ceiling stands in. */
    @Test
    void aMissingExpiryIsAbsentRatherThanGuessed() {
        SignInPrompt prompt = read(List.of("   " + REAL_LINK, "   ABCD-12345"));

        assertTrue(prompt.complete());
        assertEquals(Optional.empty(), prompt.expiresIn());
    }

    /** Nonsense that starts with https must not throw; it is simply not an address. */
    @Test
    void somethingUnparseableIsNotAnAddress() {
        assertNull(read(List.of("https://[not a uri", "   ABCD-12345")).link());
    }
}
