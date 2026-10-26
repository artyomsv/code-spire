package dev.codespire.orchestrator.work;

import dev.codespire.worksource.WorkSourceType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ticket text and artifacts quoted into a prompt or a tracker comment (M4 slice 3, A13): the text can neither close
 * its fence nor render as markup, and control characters do not reach the agent or the ticket.
 */
class UntrustedTextTest {

    @Test
    void theFenceOutgrowsAnyBacktickRunInside() {
        String fenced = UntrustedText.fenced("TEST-label", "TEST-a ```` TEST-b");

        assertTrue(fenced.contains("\n`````\nTEST-a ```` TEST-b\n`````\n"), fenced);
        assertFalse(fenced.contains("``````"), fenced);
    }

    @Test
    void aTextWithoutBackticksGetsThreeOfThem() {
        assertTrue(UntrustedText.fenced("TEST-label", "TEST-text").contains("\n```\nTEST-text\n```\n"));
    }

    @Test
    void fencedTextIsHeadedAsData() {
        String fenced = UntrustedText.fenced("TEST-label", "TEST-text");

        assertTrue(fenced.startsWith(UntrustedText.DATA_HEADING), fenced);
        assertTrue(fenced.contains("TEST-label"), fenced);
    }

    @Test
    void controlCharactersAreStripped() {
        assertEquals("TEST-ared\n\tb", UntrustedText.stripControls("TEST\u0007-a\u001b[31mred\u001b[0m\n\tb\r"));
    }

    @Test
    void markdownTextOutsideAFenceIsNeutralised() {
        assertEquals("@\u200BTEST-user &lt;script>", UntrustedText.neutralise("@TEST-user <script>", UntrustedText.Markup.MARKDOWN));
    }

    @Test
    void markdownQuotesAreNotEscapedInsideTheFence() {
        assertEquals("```\nList<String> @TEST-user\n```",
                UntrustedText.quoteBlock("List<String> @TEST-user", UntrustedText.Markup.MARKDOWN));
    }

    @Test
    void aMarkdownQuoteOutgrowsItsBackticks() {
        assertEquals("````\nTEST ``` x\n````", UntrustedText.quoteBlock("TEST ``` x", UntrustedText.Markup.MARKDOWN));
    }

    /** GitLab runs a note line that starts with a slash as a quick action of the bot (A13); none may start with one. */
    @Test
    void noMarkdownLineStartsAQuickAction() {
        String neutralised = UntrustedText.neutralise("TEST-tidy\n/close\n  /unlabel ~spire", UntrustedText.Markup.MARKDOWN);
        String quoted = UntrustedText.quoteBlock("## Outcome\n```\n/close\n```\n/label ~TEST", UntrustedText.Markup.MARKDOWN);

        for (String text : java.util.List.of(neutralised, quoted))
            for (String line : text.split("\n", -1)) assertFalse(line.stripLeading().startsWith("/") && !line.startsWith("    "), text);
        for (String line : quoted.split("\n", -1)) assertTrue(line.startsWith("    "), "every quoted line is indented code: " + quoted);
        assertTrue(quoted.contains("    /close\n"), quoted);
    }

    @Test
    void jiraTextOutsideNoformatIsEscaped() {
        assertEquals("\\[\\~accountid:TEST-1] \\!https://TEST.example.test/p.png\\! \\{code} a\\|b",
                UntrustedText.neutralise("[~accountid:TEST-1] !https://TEST.example.test/p.png! {code} a|b", UntrustedText.Markup.WIKI));
    }

    @Test
    void jiraQuotesCannotCloseNoformat() {
        String quoted = UntrustedText.quoteBlock("x {noformat} [~accountid:TEST-1] {NOFORMAT}", UntrustedText.Markup.WIKI);

        assertTrue(quoted.startsWith("{noformat}\n"), quoted);
        assertTrue(quoted.endsWith("\n{noformat}"), quoted);
        assertEquals(2, quoted.split("\\{noformat\\}", -1).length - 1, quoted);
        assertFalse(quoted.toLowerCase(java.util.Locale.ROOT).replace("{noformat}\n", "").replace("\n{noformat}", "").contains("{noformat}"), quoted);
        assertTrue(quoted.contains("[~accountid:TEST-1]"), quoted);
    }

    @Test
    void theMarkupFollowsTheSourceType() {
        assertEquals(UntrustedText.Markup.WIKI, UntrustedText.Markup.of(WorkSourceType.JIRA));
        assertEquals(UntrustedText.Markup.MARKDOWN, UntrustedText.Markup.of(WorkSourceType.GITHUB));
        assertEquals(UntrustedText.Markup.MARKDOWN, UntrustedText.Markup.of(WorkSourceType.GITLAB));
    }
}
