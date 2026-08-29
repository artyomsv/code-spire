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

    @Test
    @Order(4)
    void s4_repliesToTheBotsOwnAnswerStayInOneConversation() {
        String discussionId = firstFindingDiscussionId();

        for (int turn = 1; turn <= 2; turn++) {
            long before = botNotesIn(discussionId);
            env.human().replyToDiscussion(env.projectId(), mrIid, discussionId,
                    "Follow-up question number " + turn + "?");
            long expected = before + 1;
            Await.until("S4 the bot answered turn " + turn,
                    () -> botNotesIn(discussionId) >= expected ? Optional.of(true) : Optional.empty());
        }

        // Turns accumulate on the conversation ROOT. A row per answer would reset the count, which is
        // how multi-turn conversations died on Bitbucket: the cap could never fire and later turns
        // were stored under a non-finding ref.
        List<ReadModel.Thread> withTurns = ReadModel.threads(reviewId).stream()
                .filter(thread -> thread.turnCount() > 0)
                .toList();
        assertEquals(1, withTurns.size(),
                "S4 — one conversation root carries every turn, not one row per answer: " + withTurns);
        assertTrue(withTurns.getFirst().turnCount() >= 3,
                "S4 — three exchanges so far (S3 plus two here): " + withTurns);
    }

    @Test
    @Order(5)
    void s5_theTurnCapPostsOneNoticeAndAMentionOverridesIt() {
        String discussionId = firstFindingDiscussionId();

        // Reply until the cap fires. Bounded by Await's own deadline rather than a guessed count, so
        // the scenario does not depend on the configured cap being any particular number.
        Await.until("S5 the turn cap fired", () -> {
            if (ReadModel.events(reviewId, "TurnCapNotified") >= 1) {
                return Optional.of(true);
            }
            env.human().replyToDiscussion(env.projectId(), mrIid, discussionId, "And another question?");
            return Optional.empty();
        });

        long noticesAtCap = ReadModel.events(reviewId, "TurnCapNotified");
        long answersAtCap = botNotesIn(discussionId);

        // One more plain reply must post NOTHING. The notice is once per thread — repeating it would
        // be the bot talking past a human who has already been told.
        env.human().replyToDiscussion(env.projectId(), mrIid, discussionId, "One more plain reply.");
        Await.absent("S5 no second turn-cap notice",
                () -> ReadModel.events(reviewId, "TurnCapNotified"));
        assertEquals(noticesAtCap, ReadModel.events(reviewId, "TurnCapNotified"));

        // An explicit @-mention overrides the cap and gets a real answer.
        env.human().replyToDiscussion(env.projectId(), mrIid, discussionId,
                "@" + Environment.BOT_USERNAME + " please answer this one.");
        Await.until("S5 an @-mention overrides the cap",
                () -> botNotesIn(discussionId) > answersAtCap ? Optional.of(true) : Optional.empty());
    }

    @Test
    @Order(6)
    void s6_aMentionOnAnUnflaggedLineIsAnsweredAndCreatesNoFinding() {
        long findingsBefore = ReadModel.findingsCount(reviewId);
        long botNotesBefore = allBotNotes();

        env.human().createDiscussionOnLine(env.projectId(), mrIid, JAVA_PATH, 1,
                "@" + Environment.BOT_USERNAME + " what does this package do?");

        Await.until("S6 the bot answered a mention on an unflagged line",
                () -> allBotNotes() > botNotesBefore ? Optional.of(true) : Optional.empty());

        assertEquals(findingsBefore, ReadModel.findingsCount(reviewId),
                "S6 — answering a mention must not create a finding");
    }

    /**
     * Mode G words this as "answered in the summary thread", which is GitHub's shape: there, the
     * summary comment owns a real thread a reply can land inside. GitLab has no threading under an
     * individual note — a plain merge-request comment and the bot's answer are both individual notes
     * — so the assertion is the behaviour rather than the container: the comment is answered at merge
     * request level, and NOT turned into a new inline thread. Mode G's own "where providers
     * legitimately differ" list is for exactly this.
     */
    @Test
    @Order(7)
    void s7_aPlainMergeRequestCommentIsAnsweredAtMergeRequestLevel() {
        long anchoredBefore = anchoredBotDiscussions();
        long topLevelBefore = topLevelBotNotes();

        env.human().addNote(env.projectId(), mrIid, "Is this change safe to merge on a Friday?");

        Await.until("S7 a plain merge-request comment is answered",
                () -> topLevelBotNotes() > topLevelBefore ? Optional.of(true) : Optional.empty());

        assertEquals(anchoredBefore, anchoredBotDiscussions(),
                "S7 — answering a plain comment must not open a new inline thread");
    }

    /** Bot notes that are not anchored to a file: the summary and any merge-request-level answer. */
    private static long topLevelBotNotes() {
        long count = 0;
        for (JsonNode discussion : env.human().discussions(env.projectId(), mrIid)) {
            for (JsonNode note : discussion.get("notes")) {
                boolean ours = Environment.BOT_USERNAME
                        .equals(note.get("author").get("username").asText());
                if (ours && !note.hasNonNull("position")) {
                    count++;
                }
            }
        }
        return count;
    }

    private static long anchoredBotDiscussions() {
        long count = 0;
        for (JsonNode discussion : env.human().discussions(env.projectId(), mrIid)) {
            JsonNode first = discussion.get("notes").get(0);
            if (Environment.BOT_USERNAME.equals(first.get("author").get("username").asText())
                    && first.hasNonNull("position")) {
                count++;
            }
        }
        return count;
    }

    @Test
    @Order(8)
    void s8_slashReviewUpdatesTheSummaryInPlace() {
        long runsBefore = ReadModel.events(reviewId, "ReviewRequested");
        long summariesBefore = discussionsWithRef(summaryDiscussionId());

        env.human().addNote(env.projectId(), mrIid, "/review");

        Await.until("S8 a second run completed", () -> {
            boolean rerun = ReadModel.events(reviewId, "ReviewRequested") > runsBefore;
            return rerun && "completed".equals(ReadModel.status(reviewId))
                    ? Optional.of(true) : Optional.empty();
        });

        assertEquals(summariesBefore, discussionsWithRef(summaryDiscussionId()),
                "S8 — the summary comment is updated in place, never duplicated");
    }

    private static String summaryThreadRef() {
        return ReadModel.threads(reviewId).stream()
                .filter(ReadModel.Thread::isSummary)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no summary thread recorded"))
                .threadRef();
    }

    /**
     * The summary comment's recorded ref is GitLab's NOTE id, not a discussion id — an inline finding
     * gets a threaded discussion with a hash id, while a plain merge-request comment is an individual
     * note. Comparing the recorded ref against discussion ids therefore matched nothing, and the
     * counts on both sides of the S8 assertion were zero: it held as 0 == 0 while proving nothing.
     * That is why this resolves the note id to its owning discussion instead.
     */
    private static String summaryDiscussionId() {
        String ref = summaryThreadRef();
        for (JsonNode discussion : env.human().discussions(env.projectId(), mrIid)) {
            if (ref.equals(discussion.get("id").asText())) {
                return ref;
            }
            for (JsonNode note : discussion.get("notes")) {
                if (ref.equals(note.get("id").asText())) {
                    return discussion.get("id").asText();
                }
            }
        }
        throw new AssertionError("the recorded summary ref " + ref
                + " matches no discussion or note on the merge request");
    }

    private static long discussionsWithRef(String ref) {
        long count = 0;
        for (JsonNode discussion : env.human().discussions(env.projectId(), mrIid)) {
            if (ref.equals(discussion.get("id").asText())) {
                count++;
            }
        }
        if (count == 0) {
            throw new AssertionError("no discussion has id " + ref + " — a count of zero on both "
                    + "sides of a comparison proves nothing");
        }
        return count;
    }

    private static long allBotNotes() {
        long count = 0;
        for (JsonNode discussion : env.human().discussions(env.projectId(), mrIid)) {
            for (JsonNode note : discussion.get("notes")) {
                if (Environment.BOT_USERNAME.equals(note.get("author").get("username").asText())) {
                    count++;
                }
            }
        }
        return count;
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
