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
        assertFalse(old.has("verdict"), "the payload under test predates the field");

        PostComments back = assertInstanceOf(PostComments.class, MAPPER.treeToValue(old, ActionCommand.class));
        assertNull(back.assessment());
        assertNull(back.verdict());
        assertNull(back.reviewerMayApprove());
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
