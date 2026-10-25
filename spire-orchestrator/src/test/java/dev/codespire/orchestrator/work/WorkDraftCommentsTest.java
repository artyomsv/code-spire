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
}
