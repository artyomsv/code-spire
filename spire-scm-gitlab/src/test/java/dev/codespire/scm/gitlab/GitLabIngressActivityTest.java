package dev.codespire.scm.gitlab;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.codespire.contract.event.IntegrationEvent.RepositoryActivity;
import dev.codespire.contract.port.RawWebhook;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class GitLabIngressActivityTest {
    final ObjectMapper mapper=new ObjectMapper();
    final GitLabIngress ingress=new GitLabIngress("TEST-secret",mapper,Set.of("fix"));
    ObjectNode payload()throws Exception {return (ObjectNode)mapper.readTree("""
{"object_kind":"push","project":{"path_with_namespace":"TEST-owner/TEST-repo"},"ref":"refs/heads/TEST-work","after":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","user_id":900123,"user_name":"TEST-bot","commits":[{"author":{"name":"TEST-bot"}}]}
        """);}
    List<dev.codespire.contract.event.IntegrationEvent> events(ObjectNode root,String event)throws Exception {
        return ingress.activity(new RawWebhook(Map.of("X-Gitlab-Event",event),mapper.writeValueAsBytes(root)));
    }
    @Test void pushUsesTransportStableIdentityInsteadOfCommitAuthor()throws Exception {
        var activity=(RepositoryActivity)events(payload(),"Push Hook").getFirst();
        assertEquals("900123",activity.actorId());assertEquals("refs/heads/TEST-work",activity.branch());
        assertEquals("TEST-owner/TEST-repo",activity.repo().full());assertEquals("b".repeat(40),activity.head());
    }

    ObjectNode note()throws Exception {
        var root=payload().put("object_kind","note");root.putObject("user").put("id",900123);
        root.putObject("object_attributes").put("noteable_type","MergeRequest").put("action","create").put("system",false).put("id",17).put("note"," /FIX TEST-finding ");
        root.putObject("merge_request").put("iid",42).put("source_branch","TEST-work").put("source_project_id",7).put("target_project_id",7);
        return root;
    }
    @Test void humanNoteRetainsRegisteredFixPrecedence()throws Exception {
        var activity=(RepositoryActivity)events(note(),"Note Hook").getFirst();assertEquals("comment",activity.kind());assertTrue(activity.fixCommand());assertEquals(42,activity.prId());
    }
    @Test void systemNoteIsNotHumanTakeover()throws Exception {
        var root=note();((ObjectNode)root.path("object_attributes")).put("system",true);assertTrue(events(root,"Note Hook").isEmpty());
    }
    @Test void forkNoteCannotClaimTheLocalBranch()throws Exception {
        var root=note();((ObjectNode)root.path("merge_request")).put("source_project_id",8);assertTrue(events(root,"Note Hook").isEmpty());
    }
    @Test void editedNoteIsNotNewHumanActivity()throws Exception {
        var root=note();((ObjectNode)root.path("object_attributes")).put("action","update");assertTrue(events(root,"Note Hook").isEmpty());
    }
}
