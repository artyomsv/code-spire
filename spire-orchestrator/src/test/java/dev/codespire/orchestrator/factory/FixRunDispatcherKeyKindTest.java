package dev.codespire.orchestrator.factory;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** A {@code /fix} run is paid with a key of its harness's own vendor (harness reviews 3b). A plain unit test. */
class FixRunDispatcherKeyKindTest extends FixRunDispatcherFixture {

    /** The fix harness's own key vendor pays it, and none of that vendor's keys is the empty-pool refusal. */
    @Test
    void aFixRunPaysWithItsHarnessKind() {
        configuredHarness = "codex";

        assertInstanceOf(FixRunDispatcher.Dispatched.class, dispatch());
        assertEquals(List.of(Optional.of("openai")), keyKindsAsked);

        resetToTheHappyPath();
        selection = new HarnessCredentialPool.Selection.Empty();
        FixRunDispatcher.Refused refused = assertInstanceOf(FixRunDispatcher.Refused.class, dispatch());
        // It names the vendor it looked for: keys of another vendor may well exist, and do not pay this harness.
        assertEquals("no usable `openai` API key is configured for the codex harness, so there is no key for a fix run"
                + " to call the model with; a key of another vendor does not pay it", refused.why());
    }
}
