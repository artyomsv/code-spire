package dev.codespire.orchestrator.work;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The ticket comment that carries a written specification (M4 slice 3, A13), per tracker markup. The Jira case is
 * proved here rather than through a Jira source in the service test: the outbox sends the text it is given, and the
 * markup is chosen from the item's source type ({@code UntrustedTextTest.theMarkupFollowsTheSourceType}).
 */
class WorkDraftCommentsTest {
    static final String DIGEST = "a".repeat(64);
    /** GitHub refuses a comment body longer than this; the effect marker follows the text. */
    private static final int GITHUB_COMMENT_CHARS = 65_536;
    private static final int MARKER_CHARS = 200;

    @Test
    void theSpecificationIsPostedNeutralisedOnJira() {
        String text = WorkDraftComments.specification("## Outcome\n[~accountid:TEST-1] !https://TEST.example.test/p.png! {noformat}",
                DIGEST, UntrustedText.Markup.WIKI);

        assertTrue(text.startsWith(WorkDraftComments.SPECIFICATION_HEAD), text);
        assertTrue(text.contains(DIGEST), text);
        assertEquals(2, text.split("\{noformat\}", -1).length - 1, text);
        assertTrue(text.contains("{\u200Bnoformat}"), text);
        assertTrue(text.contains("[~accountid:TEST-1] !https://TEST.example.test/p.png!"), text);
    }

    /** Readable Markdown: every line of the specification in a blockquote, so its headings and lists render. */
    @Test
    void theMarkdownCommentQuotesTheSpecificationAsABlockquote() {
        String text = WorkDraftComments.specification("## Outcome\n- TEST-criterion\n\nTEST-text\n", DIGEST, UntrustedText.Markup.MARKDOWN);

        assertTrue(text.startsWith(WorkDraftComments.SPECIFICATION_HEAD), text);
        assertTrue(text.contains("\n\nDigest: " + DIGEST + "\n\n> ## Outcome\n> - TEST-criterion\n>\n> TEST-text\n\n"
                + "Approve or revise it on the dashboard."), text);
    }

    /** No remote image loads from a ticket comment the model wrote, a tracking pixel least of all. */
    @Test
    void anImageInTheSpecificationIsRemoved() {
        String text = markdown("TEST-a ![TEST-pixel](https://TEST.example.test/p.png) TEST-b");

        assertTrue(text.contains("> TEST-a (image removed) TEST-b"), text);
        assertFalse(text.contains("p.png"), text);
        assertFalse(text.contains("!["), text);
    }

    /** A tag, a comment or a forged effect marker renders as text: no "<" is followed by what would open a tag. */
    @Test
    void rawHtmlInTheSpecificationRendersAsText() {
        String text = markdown("<img src=https://TEST.example.test/p.png> <details><summary>TEST</summary></details> <!-- work-effect:TEST-x -->");

        assertFalse(Pattern.compile("<[A-Za-z!/?]").matcher(text).find(), text);
        assertTrue(text.contains("<\u200Bimg src="), text);
        assertTrue(text.contains("<\u200B!-- work-effect:TEST-x -->"), "a forged effect marker is broken: " + text);
    }

    /** A link is its text with the address in a code span; a bare address is a code span too. Nothing is clickable. */
    @Test
    void aLinkInTheSpecificationIsPlainTextWithItsAddressInACodeSpan() {
        String text = markdown("[TEST-docs](https://TEST.example.test/docs) and https://TEST.example.test/raw.");

        assertTrue(text.contains("> TEST-docs (`https:\u200B//TEST.example.test/docs`) and `https:\u200B//TEST.example.test/raw`."), text);
        assertFalse(text.contains("]("), text);
        assertFalse(text.contains("://"), text);
    }

    /** A mention, an entity that decodes to one, and a team mention ping nobody. */
    @Test
    void aMentionInTheSpecificationPingsNobody() {
        String text = markdown("@TEST-user, @TEST-org/TEST-team and &#64;TEST-user");

        assertTrue(text.contains("@\u200BTEST-user"), text);
        assertFalse(Pattern.compile("@[^\u200B]").matcher(text).find(), text);
        assertFalse(Pattern.compile("&[#\w]").matcher(text).find(), "an entity could decode to a mention: " + text);
    }

    /** GitHub and GitLab issue, pull request, merge request and label references link nothing. */
    @Test
    void aReferenceInTheSpecificationLinksNothing() {
        String text = markdown("#123, TEST-org/TEST-repo#45, GH-6, !7 and ~TEST-label");

        assertTrue(text.contains("#\u200B123"), text);
        assertFalse(Pattern.compile("(#|!|GH-)\d|~\w").matcher(text).find(), text);
    }

    /** The specification is capped at 16 KiB; its most expanding content still fits one GitHub comment. */
    @Test
    void theLargestSpecificationFitsAGitHubComment() {
        for (String unit : java.util.List.of("![]()\n", "@\n", "<&#\n", "[](x)\n"))
            assertTrue(markdown(unit.repeat(16 * 1024 / unit.length())).length() <= GITHUB_COMMENT_CHARS - MARKER_CHARS, unit);
    }

    static final java.util.List<WorkArtifacts.Step> STEPS = java.util.List.of(
            new WorkArtifacts.Step("TEST-s1", "@TEST-user <b>x</b>", "TEST-instruction", "TEST-done"),
            new WorkArtifacts.Step("TEST-s2", "[~accountid:TEST-1] !https://TEST.example.test/p.png!", "TEST-instruction", "TEST-done"));

    /** The step titles are quoted as the specification is: a list in a blockquote, each title inert (A13). */
    @Test
    void thePlanCommentIsNeutralisedOnMarkdown() {
        String text = WorkDraftComments.plan(STEPS, DIGEST, UntrustedText.Markup.MARKDOWN);

        assertTrue(text.startsWith(WorkDraftComments.PLAN_HEAD), text);
        assertTrue(text.contains("\n\nDigest: " + DIGEST + "\n\n> 1. @\u200BTEST-user <\u200Bb>x<\u200B/b>\n> 2. "), text);
        assertFalse(text.contains("<b>"), text);
        assertFalse(text.contains("://"), text);
    }

    /** No line of a Markdown comment may start with a slash, which GitLab would run as a quick action of the bot (A13). */
    @Test
    void neitherCommentCarriesAQuickAction() {
        String plan = WorkDraftComments.plan(java.util.List.of(new WorkArtifacts.Step("TEST-s1", "TEST-tidy\n/unlabel ~spire\n/close",
                "TEST-instruction", "TEST-done")), DIGEST, UntrustedText.Markup.MARKDOWN);
        String specification = WorkDraftComments.specification("## Outcome\n````\n```\n/close\n```\n````\n/label ~TEST", DIGEST,
                UntrustedText.Markup.MARKDOWN);

        for (String text : java.util.List.of(plan, specification))
            for (String line : text.split("\n", -1)) assertFalse(line.startsWith("/"), text);
        assertFalse(plan.contains("\n/"), plan);
        assertTrue(plan.contains("\n> 1. TEST-tidy /unlabel ~\u200Bspire /close\n"), plan);
        assertTrue(specification.contains("\n> /close\n"), specification);
    }

    @Test
    void thePlanCommentIsNeutralisedOnJira() {
        String text = WorkDraftComments.plan(STEPS, DIGEST, UntrustedText.Markup.WIKI);

        assertTrue(text.contains("2. \[\~accountid:TEST-1] \!https://TEST.example.test/p.png\!"), text);
    }

    private static String markdown(String specification) {
        return WorkDraftComments.specification(specification, DIGEST, UntrustedText.Markup.MARKDOWN);
    }
}
