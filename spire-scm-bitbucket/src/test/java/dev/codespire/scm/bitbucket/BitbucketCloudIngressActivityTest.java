package dev.codespire.scm.bitbucket;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.codespire.contract.event.IntegrationEvent.RepositoryActivity;
import dev.codespire.contract.port.RawWebhook;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class BitbucketCloudIngressActivityTest {
    final ObjectMapper mapper=new ObjectMapper();
    final BitbucketCloudIngress ingress=new BitbucketCloudIngress(new BitbucketCloudConfig("https://TEST-forge.invalid/2.0","TEST-bot","TEST-token","TEST-secret"),mapper,Set.of("fix"));
    ObjectNode payload()throws Exception {return (ObjectNode)mapper.readTree("""
{"repository":{"uuid":"TEST-repository-id","full_name":"TEST-owner/TEST-repo"},"actor":{"uuid":"900123","display_name":"TEST-bot"},"push":{"changes":[{"new":{"type":"branch","name":"TEST-work","target":{"hash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}}}]}}
        """);}
    List<dev.codespire.contract.event.IntegrationEvent> events(ObjectNode root,String event)throws Exception {
        return ingress.activity(new RawWebhook(Map.of("X-Event-Key",event),mapper.writeValueAsBytes(root)));
    }
    @Test void pushUsesTransportStableIdentityInsteadOfCommitAuthor()throws Exception {
        var activity=(RepositoryActivity)events(payload(),"repo:push").getFirst();
        assertEquals("900123",activity.actorId());assertEquals("refs/heads/TEST-work",activity.branch());
        assertEquals("TEST-owner/TEST-repo",activity.repo().full());assertEquals("b".repeat(40),activity.head());
    }

    ObjectNode comment()throws Exception {
        var root=payload();var source=root.putObject("pullrequest").put("id",42).putObject("source");
        source.putObject("repository").put("uuid","TEST-repository-id");source.putObject("branch").put("name","TEST-work");
        source.putObject("commit").put("hash","b".repeat(40));root.putObject("comment").put("id",17).putObject("content").put("raw"," /FIX TEST-finding ");
        return root;
    }
    @Test void humanCommentRetainsRegisteredFixPrecedence()throws Exception {
        var activity=(RepositoryActivity)events(comment(),"pullrequest:comment_created").getFirst();assertEquals("comment",activity.kind());assertTrue(activity.fixCommand());assertEquals(42,activity.prId());
    }
    @Test void forkCommentCannotClaimTheLocalBranch()throws Exception {
        var root=comment();((ObjectNode)root.path("pullrequest").path("source").path("repository")).put("uuid","TEST-other-repository");
        assertTrue(events(root,"pullrequest:comment_created").isEmpty());
    }
    @Test void aTagIsNotTheLinkedBranch()throws Exception {
        var root=payload();((ObjectNode)root.path("push").path("changes").get(0).path("new")).put("type","tag");
        assertTrue(events(root,"repo:push").isEmpty());
    }
    @Test void deletedBranchRetainsItsLinkWithNoInventedHead()throws Exception {
        var root=payload();var change=(ObjectNode)root.path("push").path("changes").get(0);change.set("old",change.path("new"));change.putNull("new");
        var activity=(RepositoryActivity)events(root,"repo:push").getFirst();assertEquals("refs/heads/TEST-work",activity.branch());assertNull(activity.head());
    }
}
