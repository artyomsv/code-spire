package dev.codespire.contract.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconciliationTypesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void generateReviewRoundTripsWithPriorRun() throws Exception {
        PriorRun prior = new PriorRun("aaa111", "summary-9",
                List.of(new PriorFinding("src/A.java", 7, Severity.MAJOR, "leak", "thread-1")));
        ActionCommand cmd = new ActionCommand.GenerateReview(
                "review::ws/repo#1", new RepoRef("ws", "repo"), 1L, "bbb222",
                "ctx-1", 1, null, null, null, prior);
        ActionCommand back = mapper.readValue(mapper.writeValueAsString(cmd), ActionCommand.class);
        assertEquals(prior, ((ActionCommand.GenerateReview) back).priorRun());
    }

    @Test
    void oldGenerateReviewJsonWithoutPriorRunStillDeserializes() throws Exception {
        // Wire compat: messages produced before this change carry no priorRun field.
        String legacy = """
                {"type":"GenerateReview","reviewId":"review::ws/repo#1",
                 "repo":{"workspace":"ws","slug":"repo"},"prId":1,"commit":"bbb222",
                 "contextRef":"ctx-1","attempt":1,"providerOverride":null,
                 "scmCredential":null,"llmCredential":null}""";
        ActionCommand.GenerateReview back =
                (ActionCommand.GenerateReview) mapper.readValue(legacy, ActionCommand.class);
        assertNull(back.priorRun());
    }

    @Test
    void nineArgConvenienceConstructorLeavesPriorRunNull() {
        ActionCommand.GenerateReview cmd = new ActionCommand.GenerateReview(
                "review::ws/repo#1", new RepoRef("ws", "repo"), 1L, "bbb222",
                "ctx-1", 1, null, null, null);
        assertNull(cmd.priorRun());
    }

    @Test
    void postCommentsDefaultsVerdictsToEmptyList() throws Exception {
        ActionCommand.PostComments cmd = new ActionCommand.PostComments(
                "review::ws/repo#1", new RepoRef("ws", "repo"), 1L, "bbb222", null, null);
        assertTrue(cmd.verdicts().isEmpty());
        assertNull(cmd.priorSummaryRef());
        ActionCommand.PostComments back =
                (ActionCommand.PostComments) mapper.readValue(mapper.writeValueAsString(cmd), ActionCommand.class);
        assertTrue(back.verdicts().isEmpty());
    }

    @Test
    void reviewGeneratedCarriesVerdictsAndReconcileUsage() throws Exception {
        List<FindingVerdict> verdicts = List.of(new FindingVerdict(
                "thread-1", "src/A.java", 7, FindingVerdict.Status.RESOLVED, "fix confirmed"));
        IntegrationEvent evt = new IntegrationEvent.ReviewGenerated(
                "review::ws/repo#1", 1L, "bbb222", null, verdicts,
                new ModelUsage("gpt-x", 10, 5, 42));
        IntegrationEvent.ReviewGenerated back =
                (IntegrationEvent.ReviewGenerated) mapper.readValue(mapper.writeValueAsString(evt), IntegrationEvent.class);
        assertEquals(verdicts, back.verdicts());
        assertEquals(42, back.reconcileUsage().costMillicents());
    }

    // --- AuthorReplied.topLevel wire compat (summary-comment conversations) ---

    @Test
    void authorRepliedTopLevelRoundTrips() throws Exception {
        IntegrationEvent evt = new IntegrationEvent.AuthorReplied(
                new RepoRef("ws", "repo"), 1L, "review::ws/repo#1", new ThreadRef("sum-1"),
                "c-1", "looks wrong", Author.of("42", "octocat", "Octo Cat"), true);
        IntegrationEvent.AuthorReplied back =
                (IntegrationEvent.AuthorReplied) mapper.readValue(mapper.writeValueAsString(evt), IntegrationEvent.class);
        assertTrue(back.topLevel());
        assertEquals(evt, back);
    }

    @Test
    void legacyAuthorRepliedJsonWithoutTopLevelDefaultsFalse() throws Exception {
        // Wire compat: events serialized before this field existed carry no topLevel key.
        String legacy = """
                {"type":"AuthorReplied","repo":{"workspace":"ws","slug":"repo"},"prId":1,
                 "reviewId":"review::ws/repo#1","threadRef":{"value":"t-1"},"commentId":"c-1",
                 "text":"why?","author":{"providerUserId":"42","username":"octocat","displayName":"Octo Cat"}}""";
        IntegrationEvent.AuthorReplied back =
                (IntegrationEvent.AuthorReplied) mapper.readValue(legacy, IntegrationEvent.class);
        assertFalse(back.topLevel());
    }

    @Test
    void sevenArgAuthorRepliedConstructorDefaultsTopLevelFalse() {
        IntegrationEvent.AuthorReplied e = new IntegrationEvent.AuthorReplied(
                new RepoRef("ws", "repo"), 1L, "review::ws/repo#1", new ThreadRef("t-1"),
                "c-1", "why?", Author.of("42", "octocat", "Octo Cat"));
        assertFalse(e.topLevel());
    }

    @Test
    void commentsPostedCarriesThreadOutcomes() throws Exception {
        IntegrationEvent evt = new IntegrationEvent.CommentsPosted(
                "review::ws/repo#1", 1L, "bbb222", "sum-1", List.of(),
                List.of(new IntegrationEvent.CommentsPosted.ThreadOutcome(
                        "thread-1", FindingVerdict.Status.RESOLVED, "reply-5", true)));
        IntegrationEvent.CommentsPosted back =
                (IntegrationEvent.CommentsPosted) mapper.readValue(mapper.writeValueAsString(evt), IntegrationEvent.class);
        assertEquals(1, back.threadOutcomes().size());
        assertTrue(back.threadOutcomes().getFirst().resolved());
    }
}
