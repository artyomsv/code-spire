package dev.codespire.orchestrator.review.run;

import dev.codespire.orchestrator.llm.CallRefs;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Harness reviews, step 7: a reply run's thread and turn are read back from the call slot its row stores. */
class ReplyCallTest {

    @Test
    void aSlotSplitsAtItsLastColon() {
        assertEquals(new ReplyCall("a:b", "123"), ReplyCall.ofSlot("a:b:123"));
    }

    @Test
    void aSlotWithoutATurnIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ReplyCall.ofSlot("TEST-thread"));
        assertThrows(IllegalArgumentException.class, () -> ReplyCall.ofSlot("TEST-thread:"));
        assertThrows(IllegalArgumentException.class, () -> ReplyCall.ofSlot(":123"));
        assertThrows(IllegalArgumentException.class, () -> ReplyCall.ofSlot(null));
    }

    /** A GitHub review's summary thread ref holds "::" (GitHubCommentSink), and must survive the round trip. */
    @Test
    void followUpSlotAndOfSlotRoundTrip() {
        String thread = "review::4242";
        String slot = CallRefs.followUpSlot(thread, "987654");

        ReplyCall call = ReplyCall.ofSlot(slot);

        assertEquals(new ReplyCall(thread, "987654"), call);
        assertEquals(slot, call.slot());
    }
}
