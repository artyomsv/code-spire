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
    /** A pull request closed on GitHub, merged or not, at the item branch's head. */
    ObjectNode closed(String action,boolean merged)throws Exception {
        ObjectNode root=payload().put("action",action);
        ObjectNode pr=root.putObject("pull_request").put("number",42).put("merged",merged);pr.putObject("head").put("ref","TEST-work").put("sha","b".repeat(40));
        if(merged){pr.put("merge_commit_sha","e".repeat(40));pr.putObject("merged_by").put("id",900456).put("login","TEST-merger");}
        return root;
    }
    // The sender is whoever closed it; the merger is merged_by, which is what the landing records (A2).
    @Test void aMergedCloseIsMergedWithTheMergerId()throws Exception {
        RepositoryActivity activity=(RepositoryActivity)events(closed("closed",true),"pull_request").getFirst();
        assertEquals("merged",activity.kind());assertEquals("900456",activity.mergedBy());assertEquals("900123",activity.actorId());
        assertEquals(42,activity.prId());assertEquals("refs/heads/TEST-work",activity.branch());assertEquals("b".repeat(40),activity.head());
    }
    @Test void anUnmergedCloseIsClosed()throws Exception {
        RepositoryActivity activity=(RepositoryActivity)events(closed("closed",false),"pull_request").getFirst();
        assertEquals("closed",activity.kind());assertNull(activity.mergedBy());assertEquals("900123",activity.actorId());
    }
    @Test void synchronizeIsStillPush()throws Exception {
        RepositoryActivity activity=(RepositoryActivity)events(closed("synchronize",false),"pull_request").getFirst();
        assertEquals("push",activity.kind());assertNull(activity.mergedBy());assertEquals(42,activity.prId());
    }
    @Test void issueCommentOutsideAPullRequestIsNotScmTakeover()throws Exception {
        var root=payload().put("action","created");root.putObject("issue").put("number",42);root.putObject("comment").put("id",18).put("body","TEST hello");
        assertTrue(events(root,"issue_comment").isEmpty());
    }
}
