package dev.codespire.worker.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.ActionCommand.PostComments;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.CommentsPosted;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.ReviewVerdict;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/** D10: a command or event recorded before the verdict fields existed still deserializes. */
class PostCommentsWireCompatibilityTest {

    private static final RepoRef REPO = new RepoRef("TEST-ws", "TEST-repo");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void anOldPostCommentsPayloadDeserializesWithNullVerdictFields() throws Exception {
        PostComments current = new PostComments("review::TEST-ws/TEST-repo#1", REPO, 1, "TEST-commit",
                new ReviewResult(List.of(), "TEST", ModelUsage.of("TEST-MODEL", 1, 1)), null);
        ObjectNode old = MAPPER.valueToTree(current);
        old.remove("assessment");
        old.remove("verdict");
        old.remove("reviewerMayApprove");
        old.remove("runToken");
        assertFalse(old.has("verdict"), "the payload under test predates the field");

        PostComments back = assertInstanceOf(PostComments.class, MAPPER.treeToValue(old, ActionCommand.class));
        assertNull(back.assessment());
        assertNull(back.verdict());
        assertNull(back.reviewerMayApprove());
        assertNull(back.runToken());
    }

    @Test
    void withRunTokenSurvivesTheOtherWithers() {
        // Live on 2026-10-16 (spire-test PR 55): a re-run on the same head found the earlier run's
        // review by its header and reused it, so nothing new was posted. The run token tells a
        // redelivery of this run apart from a re-run; every wither must carry it.
        PostComments current = new PostComments("review::TEST-ws/TEST-repo#1", REPO, 1, "TEST-commit",
                new ReviewResult(List.of(), "TEST", ModelUsage.of("TEST-MODEL", 1, 1)), null)
                .withRunToken("TEST-run-7")
                .withVerdict(ReviewVerdict.APPROVE, ReviewVerdict.APPROVE, true)
                .withSuppressedCount(1);
        assertEquals("TEST-run-7", current.runToken());
        assertEquals(ReviewVerdict.APPROVE, current.verdict());
        assertEquals(1, current.suppressedCount());
    }

    @Test
    void withVerdictCarriesAllThreeAndKeepsTheRest() {
        PostComments current = new PostComments("review::TEST-ws/TEST-repo#1", REPO, 1, "TEST-commit",
                new ReviewResult(List.of(), "TEST", ModelUsage.of("TEST-MODEL", 1, 1)), null)
                .withSuppressedCount(2)
                .withVerdict(ReviewVerdict.APPROVE, ReviewVerdict.COMMENT, false);
        assertEquals(ReviewVerdict.APPROVE, current.assessment());
        assertEquals(ReviewVerdict.COMMENT, current.verdict());
        assertEquals(Boolean.FALSE, current.reviewerMayApprove());
        assertEquals(2, current.suppressedCount(), "a wither must enumerate every component");
    }

    @Test
    void withSuppressedCountKeepsTheVerdictFields() {
        // Mutation check t1-a survived the test above: withVerdict ran last and hid a wither that
        // dropped a component. Order the withers the other way round and assert what the first one set.
        PostComments current = new PostComments("review::TEST-ws/TEST-repo#1", REPO, 1, "TEST-commit",
                new ReviewResult(List.of(), "TEST", ModelUsage.of("TEST-MODEL", 1, 1)), null)
                .withVerdict(ReviewVerdict.REQUEST_CHANGES, ReviewVerdict.COMMENT, false)
                .withSuppressedCount(3);
        assertEquals(ReviewVerdict.REQUEST_CHANGES, current.assessment());
        assertEquals(ReviewVerdict.COMMENT, current.verdict());
        assertEquals(Boolean.FALSE, current.reviewerMayApprove());
        assertEquals(3, current.suppressedCount());
    }

    @Test
    void theLegacyConstructorLeavesEveryVerdictFieldNull() {
        // Mutation check t1-b survived: nothing built a command through the 9-argument constructor
        // (D10's "post as before"). A default smuggled in there would turn every legacy call site native.
        PostComments legacy = new PostComments("review::TEST-ws/TEST-repo#1", REPO, 1, "TEST-commit",
                new ReviewResult(List.of(), "TEST", ModelUsage.of("TEST-MODEL", 1, 1)), null, List.of(), null, 4);
        assertNull(legacy.assessment());
        assertNull(legacy.verdict());
        assertNull(legacy.reviewerMayApprove());
        assertNull(legacy.runToken());
        assertEquals(4, legacy.suppressedCount());
    }

    @Test
    void anOldCommentsPostedPayloadDeserializesWithNullPostedFields() throws Exception {
        CommentsPosted current = new CommentsPosted("review::TEST-ws/TEST-repo#1", 1, "TEST-commit",
                "review:TEST-ref", List.of());
        ObjectNode old = MAPPER.valueToTree(current);
        old.remove("postedVerdict");
        old.remove("reviewRef");
        old.remove("reviewUrl");

        CommentsPosted back = assertInstanceOf(CommentsPosted.class,
                MAPPER.treeToValue(old, IntegrationEvent.class));
        assertNull(back.postedVerdict());
        assertNull(back.reviewRef());
        assertNull(back.reviewUrl());
        assertEquals("review:TEST-ref", back.summaryThreadRef());
    }
}
