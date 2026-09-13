package dev.codespire.worksource.gitlab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.codespire.worksource.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GitLabWorkIngressTest {
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    final GitLabWorkIngress ingress=new GitLabWorkIngress("TEST-secret",mapper);
    final Map<String,String> headers=Map.of("X-Gitlab-Event","Issue Hook","X-Gitlab-Token","TEST-secret");
    ObjectNode payload() {
        ObjectNode root=mapper.createObjectNode().put("object_kind","issue");
        root.putObject("project").put("id",10001).put("path_with_namespace","TEST-group/TEST-repo");
        root.putObject("object_attributes").put("project_id",10001).put("id",50001).put("iid",42)
                .put("updated_at","2026-09-13T12:00:00Z").put("title","TEST-private-title").put("description","TEST-private-body");
        root.putObject("user").put("id",900123);
        ObjectNode labels=root.putObject("changes").putObject("labels");
        labels.putArray("previous").addObject().put("title","TEST-retained");
        labels.putArray("current").addObject().put("title","TEST-retained");
        labels.withArray("current").addObject().put("title","TEST-auto");return root;
    }
    @Test void verifiedDeltaCarriesOnlyTheAddedLabelsActor() throws Exception {
        var signals=ingress.translate(headers,mapper.writeValueAsBytes(payload()),"https://gitlab.example.test");
        assertEquals(1,signals.size());var signal=signals.getFirst();
        assertEquals("TEST-group/TEST-repo",signal.externalScope());assertEquals(WorkSourceType.GITLAB,signal.issue().ref().type());
        assertEquals("10001",signal.issue().ref().projectId());assertEquals("50001",signal.issue().ref().issueId());
        assertEquals("TEST-auto",signal.hint().label());assertEquals("900123",signal.hint().trackerActorId());
        assertEquals(LabelEvent.Action.ADD,signal.hint().action());assertEquals(LabelEvent.Origin.WEBHOOK,signal.hint().origin());
        String serialized=mapper.writeValueAsString(signal);
        assertFalse(serialized.contains("TEST-private-title"));assertFalse(serialized.contains("TEST-private-body"));
    }
    @Test void removalsRemainRemovals() throws Exception {
        ObjectNode root=payload();((ObjectNode)root.path("changes").path("labels")).putArray("current");
        var signals=ingress.translate(headers,mapper.writeValueAsBytes(root),"https://gitlab.example.test");
        assertEquals(LabelEvent.Action.REMOVE,signals.getFirst().hint().action());
    }
    @Test void invalidTokenCannotPublishControlFacts() throws Exception {
        byte[] body=mapper.writeValueAsBytes(payload());
        assertThrows(WorkSourceException.class,()->ingress.translate(Map.of("X-Gitlab-Event","Issue Hook","X-Gitlab-Token","TEST-wrong"),body,"https://gitlab.example.test"));
    }
    @Test void anotherHookKindIsIgnoredAfterVerification() throws Exception {
        assertTrue(ingress.translate(Map.of("X-Gitlab-Event","Merge Request Hook","X-Gitlab-Token","TEST-secret"),mapper.writeValueAsBytes(payload()),"https://gitlab.example.test").isEmpty());
    }
    @Test void bodyKindMustAgreeWithIssueHeader() throws Exception {
        byte[] body=mapper.writeValueAsBytes(payload().put("object_kind","merge_request"));
        assertThrows(WorkSourceException.class,()->ingress.translate(headers,body,"https://gitlab.example.test"));
    }
    @Test void projectEnvelopeCannotHideAnIssueFromAnotherProject() throws Exception {
        ObjectNode root=payload();((ObjectNode)root.path("object_attributes")).put("project_id",10002);
        byte[] body=mapper.writeValueAsBytes(root);assertThrows(WorkSourceException.class,()->ingress.translate(headers,body,"https://gitlab.example.test"));
    }
    @Test void dotSegmentsCannotBecomeAProjectScope() throws Exception {
        ObjectNode root=payload();((ObjectNode)root.path("project")).put("path_with_namespace","TEST-group/../TEST-repo");
        byte[] body=mapper.writeValueAsBytes(root);assertThrows(WorkSourceException.class,()->ingress.translate(headers,body,"https://gitlab.example.test"));
    }
    @Test void missingUserCarriesNoInventedActor() throws Exception {
        var signal=ingress.translate(headers,mapper.writeValueAsBytes(payload().putNull("user")),"https://gitlab.example.test").getFirst();
        assertNull(signal.hint().trackerActorId());
    }
    @Test void missingLabelDeltaIsOnlyARescanSignal() throws Exception {
        ObjectNode root=payload();root.remove("changes");
        assertNull(ingress.translate(headers,mapper.writeValueAsBytes(root),"https://gitlab.example.test").getFirst().hint());
    }
    @Test void aMalformedLabelDeltaCannotBeTreatedAsAnEmptySet() throws Exception {
        ObjectNode root=payload();((ObjectNode)root.path("changes").path("labels")).putNull("previous");
        byte[] body=mapper.writeValueAsBytes(root);assertThrows(WorkSourceException.class,()->ingress.translate(headers,body,"https://gitlab.example.test"));
    }
    @Test void redeliveryKeepsTheSameControlIdentity() throws Exception {
        byte[] body=mapper.writeValueAsBytes(payload());
        assertEquals(ingress.translate(headers,body,"https://gitlab.example.test"),ingress.translate(headers,body,"https://gitlab.example.test"));
    }
}
