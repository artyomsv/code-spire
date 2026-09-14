package dev.codespire.scm.github;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.codespire.contract.event.IntegrationEvent.RepositoryActivity;
import dev.codespire.contract.port.RawWebhook;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class GitHubIngressActivityTest {
    final ObjectMapper mapper=new ObjectMapper();
    final GitHubIngress ingress=new GitHubIngress("TEST-secret",mapper,Set.of("fix"));
    ObjectNode payload()throws Exception {return (ObjectNode)mapper.readTree("""
{"repository":{"full_name":"TEST-owner/TEST-repo"},"ref":"refs/heads/TEST-work","after":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","sender":{"id":900123,"login":"TEST-bot"},"pusher":{"name":"TEST-bot"},"commits":[{"author":{"name":"TEST-bot"}}]}
        """);}
    List<dev.codespire.contract.event.IntegrationEvent> events(ObjectNode root,String event)throws Exception {
        return ingress.activity(new RawWebhook(Map.of("X-GitHub-Event",event),mapper.writeValueAsBytes(root)));
    }
    @Test void pushUsesTransportStableIdentityInsteadOfCommitAuthor()throws Exception {
        var activity=(RepositoryActivity)events(payload(),"push").getFirst();
        assertEquals("900123",activity.actorId());assertEquals("refs/heads/TEST-work",activity.branch());
        assertEquals("TEST-owner/TEST-repo",activity.repo().full());assertEquals("b".repeat(40),activity.head());
    }

    ObjectNode review(String state)throws Exception {
        var root=payload().put("action","submitted");
        var pr=root.putObject("pull_request").put("number",42);pr.putObject("head").put("ref","TEST-work").put("sha","b".repeat(40));
        root.putObject("review").put("id",17).put("state",state);return root;
    }
    @Test void ordinaryReviewCommentIsHumanActivityNotAnApproval()throws Exception {
        var activity=(RepositoryActivity)events(review("commented"),"pull_request_review").getFirst();
        assertEquals("comment",activity.kind());assertEquals("17",activity.commentId());assertFalse(activity.fixCommand());
    }
    @Test void submittedApprovalCarriesOnlyAReferenceForLiveReread()throws Exception {
        var activity=(RepositoryActivity)events(review("approved"),"pull_request_review").getFirst();
        assertEquals("approval",activity.kind());assertEquals("17",activity.reviewId());assertEquals(42,activity.prId());
    }
    @Test void editedReviewIsNotANewApproval()throws Exception {
        assertTrue(events(review("approved").put("action","edited"),"pull_request_review").isEmpty());
    }
    @Test void createdFixCommentUsesTheExistingRegisteredCommandGrammar()throws Exception {
        var root=payload().put("action","created");root.putObject("issue").put("number",42).putObject("pull_request");
        root.putObject("comment").put("id",18).put("body","  /FIX  TEST-finding  ");
        var activity=(RepositoryActivity)events(root,"issue_comment").getFirst();assertTrue(activity.fixCommand());assertEquals("18",activity.commentId());
        ((ObjectNode)root.path("comment")).put("body","TEST says /fix");
        assertFalse(((RepositoryActivity)events(root,"issue_comment").getFirst()).fixCommand());
    }
    @Test void issueCommentOutsideAPullRequestIsNotScmTakeover()throws Exception {
        var root=payload().put("action","created");root.putObject("issue").put("number",42);root.putObject("comment").put("id",18).put("body","TEST hello");
        assertTrue(events(root,"issue_comment").isEmpty());
    }
}
