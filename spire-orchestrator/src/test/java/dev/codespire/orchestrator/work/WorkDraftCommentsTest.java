package dev.codespire.orchestrator.work;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The ticket comment that carries a written specification (M4 slice 3, A13), per tracker markup. The Jira case is
 * proved here rather than through a Jira source in the service test: the outbox sends the text it is given, and the
 * markup is chosen from the item's source type ({@code UntrustedTextTest.theMarkupFollowsTheSourceType}).
 */
class WorkDraftCommentsTest {
    static final String DIGEST = "a".repeat(64);

    @Test
    void theSpecificationIsPostedNeutralisedOnJira() {
        String text = WorkDraftComments.specification("## Outcome\n[~accountid:TEST-1] !https://TEST.example.test/p.png! {noformat}",
                DIGEST, UntrustedText.Markup.WIKI);

        assertTrue(text.startsWith(WorkDraftComments.SPECIFICATION_HEAD), text);
        assertTrue(text.contains(DIGEST), text);
        assertEquals(2, text.split("\\{noformat\\}", -1).length - 1, text);
        assertTrue(text.contains("{\u200Bnoformat}"), text);
        assertTrue(text.contains("[~accountid:TEST-1] !https://TEST.example.test/p.png!"), text);
    }

    @Test
    void theMarkdownCommentFencesTheSpecificationVerbatim() {
        String text = WorkDraftComments.specification("## Outcome\nList<String> @TEST-user ````", DIGEST, UntrustedText.Markup.MARKDOWN);

        assertTrue(text.contains("`````\n## Outcome\nList<String> @TEST-user ````\n`````"), text);
        assertFalse(text.contains("&lt;"), text);
        assertTrue(text.contains("on the dashboard"), text);
    }

    static final java.util.List<WorkArtifacts.Step> STEPS = java.util.List.of(
            new WorkArtifacts.Step("TEST-s1", "@TEST-user <b>x</b>", "TEST-instruction", "TEST-done"),
            new WorkArtifacts.Step("TEST-s2", "[~accountid:TEST-1] !https://TEST.example.test/p.png!", "TEST-instruction", "TEST-done"));

    /** The step titles are a list outside any quote block, so each is neutralised for the tracker's markup (A13). */
    @Test
    void thePlanCommentIsNeutralisedOnMarkdown() {
        String text = WorkDraftComments.plan(STEPS, DIGEST, UntrustedText.Markup.MARKDOWN);

        assertTrue(text.startsWith(WorkDraftComments.PLAN_HEAD), text);
        assertTrue(text.contains(DIGEST), text);
        assertTrue(text.contains("1. @\u200BTEST-user &lt;b>x&lt;/b>"), text);
        assertFalse(text.contains("<b>"), text);
    }

    @Test
    void thePlanCommentIsNeutralisedOnJira() {
        String text = WorkDraftComments.plan(STEPS, DIGEST, UntrustedText.Markup.WIKI);

        assertTrue(text.contains("2. \\[\\~accountid:TEST-1] \\!https://TEST.example.test/p.png\\!"), text);
    }
}
