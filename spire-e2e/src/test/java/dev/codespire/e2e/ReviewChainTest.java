package dev.codespire.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.e2e.gitlab.GitLabDriver.FileAction;
import dev.codespire.e2e.spire.LlmMock;
import dev.codespire.e2e.support.Await;
import dev.codespire.e2e.support.Fixtures;
import dev.codespire.e2e.support.ReadModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MR 1: Mode G's S1–S11, in Mode G's order.
 *
 * <p>ONE ORDERED CHAIN, not twelve independent tests — S5 needs S4's turns, S9 needs S1's findings. A
 * break in S1 reddens everything after it, which is accepted because the alternative is not testing
 * the conversation at all. The mitigations are that every failure message leads with the step name,
 * that diagnostics are captured on failure, and that the two riskiest concerns (per-language code
 * context, and the rename) live in their own merge requests rather than inside this chain.
 *
 * <p>The fixture files are ADDED by the merge request rather than edited on it, because the mock keys
 * on a marker appearing as an ADDED diff line — which is also what a real first review sees.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReviewChainTest {

    private static final String JAVA_PATH = "src/main/java/e2e/Defects.java";

    private static final String TS_PATH = "src/ui/defects.ts";

    private static final String BRANCH = "e2e-topic";

    private static Environment env;

    private static long mrIid;

    private static String reviewId;

    @BeforeAll
    static void openTheMergeRequest() {
        LlmMock.reset();
        env = Environment.provision("e2e-chain");

        env.human().commit(env.projectId(), "main", null, "Add a starter file",
                List.of(FileAction.create("README.md", "E2E chain fixture.\n")));

        env.human().commit(env.projectId(), BRANCH, "main", "Introduce the marked defects", List.of(
                FileAction.create(JAVA_PATH, Fixtures.read("fixtures/chain/" + JAVA_PATH)),
                FileAction.create(TS_PATH, Fixtures.read("fixtures/chain/" + TS_PATH))));

        mrIid = env.human().openMergeRequest(env.projectId(), BRANCH, "main", "E2E chain");
        reviewId = env.reviewId(mrIid);
    }

    @Test
    @Order(1)
    void s1_theReviewPostsOneInlineCommentPerFindingAndOneSummary() {
        Await.until("S1 review completed", () ->
                "completed".equals(ReadModel.status(reviewId)) ? Optional.of(true) : Optional.empty());

        assertEquals(3, ReadModel.findingsCount(reviewId),
                "S1 — the mock returns three findings for this fixture");

        // Our side: exactly one summary thread, and one thread per finding anchored at its own line.
        //
        // Deliberately NOT filtered by isOurs. markSummaryThread does not set it — the flag governs
        // conversation scope (which threads the bot answers), and the summary comment is answered
        // through the summary path rather than by being "ours". Asserting isOurs here would be
        // asserting an implementation detail the code documents as intentionally absent.
        List<ReadModel.Thread> threads = ReadModel.threads(reviewId);
        assertEquals(1, threads.stream().filter(ReadModel.Thread::isSummary).count(),
                "S1 — exactly one summary comment: " + threads);
        assertEquals(List.of("7", "11", "6"),
                threads.stream().filter(thread -> !thread.isSummary())
                        .map(ReadModel.Thread::line).toList(),
                "S1 — each finding is anchored at the line its marker sits on: " + threads);

        // GitLab's side. The read model saying we posted is not proof that GitLab has it, and the two
        // disagreeing is precisely the class of defect this suite exists to catch.
        assertEquals(4, botDiscussions(),
                "S1 — three inline findings plus one summary, present on GitLab itself");

        assertTrue(LlmMock.prompts().size() >= 1, "S1 — the worker actually called the model");
    }

    @Test
    @Order(2)
    void s3_aReplyUnderAFindingIsAnsweredInThatThreadWithFencedCode() {
        String discussionId = firstFindingDiscussionId();
        long before = botNotesIn(discussionId);

        env.human().replyToDiscussion(env.projectId(), mrIid, discussionId,
                "Why is this a problem when the denominator is validated upstream?");

        long expected = before + 1;
        Await.until("S3 the bot answered in the finding thread",
                () -> botNotesIn(discussionId) >= expected ? Optional.of(true) : Optional.empty());

        String answer = lastBotNoteIn(discussionId);
        assertTrue(answer.contains("```"),
                "S3 — the locked FOLLOWUP contract requires a fence. Indented code renders as prose on "
                        + "the SCM, which this project shipped once: " + answer);
    }

    /**
     * Runs AFTER S3 on purpose: there is nothing to expand until a conversation exists, and the
     * findings card shows a finding's own text in full with no expander.
     */
    @Test
    @Order(3)
    void s2_theThreadEndpointReturnsTheFullConversationNotThePreview() {
        String discussionId = firstFindingDiscussionId();

        String thread = env.spire().get("/api/reviews/" + env.workspace() + "/" + env.slug()
                + "/" + mrIid + "/threads/" + discussionId).toString();

        assertTrue(thread.contains("E2E fixture reply"),
                "S2 — the endpoint must return the bot's full answer, not the <=160-char preview the "
                        + "card used to show: " + thread);
    }

    /** The first inline (position-anchored) discussion the bot opened. */
    private static String firstFindingDiscussionId() {
        for (JsonNode discussion : env.human().discussions(env.projectId(), mrIid)) {
            JsonNode note = discussion.get("notes").get(0);
            boolean ours = Environment.BOT_USERNAME.equals(note.get("author").get("username").asText());
            if (ours && note.hasNonNull("position")) {
                return discussion.get("id").asText();
            }
        }
        throw new AssertionError("no inline finding discussion found on the merge request");
    }

    private static long botNotesIn(String discussionId) {
        long count = 0;
        for (JsonNode note : notesIn(discussionId)) {
            if (Environment.BOT_USERNAME.equals(note.get("author").get("username").asText())) {
                count++;
            }
        }
        return count;
    }

    private static String lastBotNoteIn(String discussionId) {
        String last = "";
        for (JsonNode note : notesIn(discussionId)) {
            if (Environment.BOT_USERNAME.equals(note.get("author").get("username").asText())) {
                last = note.get("body").asText();
            }
        }
        return last;
    }

    private static List<JsonNode> notesIn(String discussionId) {
        for (JsonNode discussion : env.human().discussions(env.projectId(), mrIid)) {
            if (discussionId.equals(discussion.get("id").asText())) {
                List<JsonNode> notes = new ArrayList<>();
                discussion.get("notes").forEach(notes::add);
                return notes;
            }
        }
        return List.of();
    }

    /** Discussions whose opening note the bot wrote. */
    private static long botDiscussions() {
        long count = 0;
        for (JsonNode discussion : env.human().discussions(env.projectId(), mrIid)) {
            JsonNode first = discussion.get("notes").get(0);
            if (Environment.BOT_USERNAME.equals(first.get("author").get("username").asText())) {
                count++;
            }
        }
        return count;
    }
}
