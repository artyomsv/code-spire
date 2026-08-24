package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.IntegrationEvent.ManualCommandReceived;
import dev.codespire.contract.review.Severity;
import dev.codespire.contract.scm.ThreadLocation;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConversationFindingsTest {

    @Test
    void bareFindingIsMinor() {
        ConversationFindings.ParsedFinding result = ConversationFindings.parse("");
        assertEquals(Severity.MINOR, result.severity());
        assertEquals("", result.message());
    }

    @Test
    void aLeadingSeverityWordSetsTheSeverity() {
        ConversationFindings.ParsedFinding result = ConversationFindings.parse("major shadows the field");
        assertEquals(Severity.MAJOR, result.severity());
        assertEquals("shadows the field", result.message());
    }

    @Test
    void severityIsCaseInsensitive() {
        ConversationFindings.ParsedFinding result = ConversationFindings.parse("BLOCKER drops the lock");
        assertEquals(Severity.BLOCKER, result.severity());
        assertEquals("drops the lock", result.message());
    }

    @Test
    void aFirstWordThatIsNotASeverityIsPartOfTheMessage() {
        // "/finding this shadows the field" must file a MINOR with that note, not refuse on a typo.
        ConversationFindings.ParsedFinding result = ConversationFindings.parse("this shadows the field");
        assertEquals(Severity.MINOR, result.severity());
        assertEquals("this shadows the field", result.message());
    }

    @Test
    void theEventsOwnLocationWins() {
        ConversationFindings.Outcome outcome = ConversationFindings.resolve(
                command("major shadows the field", new ThreadRef("t-900"),
                        new ThreadLocation("src/Foo.java", 44)),
                new ThreadLocation("src/Stale.java", 9));

        assertInstanceOf(ConversationFindings.Filed.class, outcome);
        ConversationFindings.Filed filed = (ConversationFindings.Filed) outcome;
        assertEquals(new ThreadRef("t-900"), filed.threadRef());
        assertEquals("src/Foo.java", filed.path());
        assertEquals(44, filed.line());
        assertEquals(Severity.MAJOR, filed.severity());
        assertEquals("shadows the field", filed.message());
    }

    @Test
    void theStoredThreadLocationIsTheFallback() {
        // Not every provider reports a location on every comment; review_thread has recorded where
        // a human-started inline thread sits since V17/V27.
        ConversationFindings.Outcome outcome = ConversationFindings.resolve(
                command("", new ThreadRef("t-900"), null),
                new ThreadLocation("src/Foo.java", 44));

        assertInstanceOf(ConversationFindings.Filed.class, outcome);
        ConversationFindings.Filed filed = (ConversationFindings.Filed) outcome;
        assertEquals(new ThreadRef("t-900"), filed.threadRef());
        assertEquals("src/Foo.java", filed.path());
        assertEquals(44, filed.line());
        assertEquals(Severity.MINOR, filed.severity());
        assertEquals("", filed.message());
    }

    @Test
    void withNoAnchorAtAllItRefusesWithAnExplanation() {
        // A summary or top-level comment. Refusing SILENTLY is the failure this project has already
        // shipped twice -- the turn cap posted nothing when reached, and a dead tunnel looked
        // identical. A command that does nothing must say so.
        ConversationFindings.Outcome outcome = ConversationFindings.resolve(
                command("major something", null, null), null);

        assertInstanceOf(ConversationFindings.Refused.class, outcome);
        ConversationFindings.Refused refused = (ConversationFindings.Refused) outcome;
        assertTrue(refused.replyText().contains("needs to be on a specific line"),
                "Refusal text should contain the expected message");
    }

    @Test
    void noThreadRefIsAlsoRefused() {
        ConversationFindings.Outcome outcome = ConversationFindings.resolve(
                new ManualCommandReceived(new RepoRef("acme", "widgets"), 7, "finding", "major something",
                        Author.of("u-1", "octocat", "Octocat"), null, new ThreadLocation("src/Foo.java", 44), null),
                null);

        assertInstanceOf(ConversationFindings.Refused.class, outcome);
    }

    private static ManualCommandReceived command(String args, ThreadRef thread, ThreadLocation loc) {
        return new ManualCommandReceived(new RepoRef("acme", "widgets"), 7, "finding", args,
                Author.of("u-1", "octocat", "Octocat"), thread, loc, null);
    }
}
