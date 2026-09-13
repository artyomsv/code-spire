package dev.codespire.worksource.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.codespire.worksource.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.jupiter.api.Assertions.*;

class GitHubWorkIngressTest {
    final ObjectMapper mapper=new ObjectMapper();
    final String secret="TEST-hook-secret";
    final GitHubWorkIngress ingress=new GitHubWorkIngress(secret,mapper);
    ObjectNode body(){
        ObjectNode root=mapper.createObjectNode().put("action","labeled");
        root.putObject("repository").put("full_name","TEST-owner/TEST-repo").put("id",10001);
        root.putObject("issue").put("id",50001).put("number",42).put("html_url","https://github.com/TEST-owner/TEST-repo/issues/42")
                .put("updated_at","2026-09-13T12:00:00Z").put("title","TEST-private-title").put("body","TEST-private-body");
        root.putObject("sender").put("id",900123);root.putObject("label").put("name","TEST-label");return root;
    }
    Map<String,String> headers(byte[] body,String kind) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return Map.of("X-GitHub-Event",kind,"X-Hub-Signature-256","sha256="+HexFormat.of().formatHex(mac.doFinal(body)));
    }
    List<WorkSourceSignal> translate(ObjectNode root) throws Exception {
        byte[] body=mapper.writeValueAsBytes(root);return ingress.translate(headers(body,"issues"),body,"https://api.github.com");
    }
    @Test void dotPrefixedRepositoriesCanDeliverSignedLabels() {
        ObjectNode root = body();
        ((ObjectNode) root.path("repository")).put("full_name", "TEST-owner/.github");
        ((ObjectNode) root.path("issue")).put("html_url", "https://github.com/TEST-owner/.github/issues/42");
        List<WorkSourceSignal> signals = assertDoesNotThrow(() -> translate(root));
        assertEquals(1, signals.size());
        assertEquals("TEST-owner/.github", signals.getFirst().externalScope());
    }

    @Test void aDotPathSegmentCannotBecomeARepositoryScope() {
        ObjectNode root = body();
        ((ObjectNode) root.path("repository")).put("full_name", "TEST-owner/..");
        assertThrows(WorkSourceException.class, () -> translate(root));
    }

    @Test void verifiesTheProductionSignatureBeforeTranslation() throws Exception {
        byte[] body=mapper.writeValueAsBytes(body());
        assertThrows(WorkSourceException.class,()->ingress.translate(headers(new byte[0],"issues"),body,"https://api.github.com"));
    }
    @Test void emitsAnAttributedStableControlHint() throws Exception {
        WorkSourceSignal signal=translate(body()).getFirst();assertEquals("10001",signal.issue().ref().projectId());
        assertEquals("50001",signal.issue().ref().issueId());assertEquals("900123",signal.hint().trackerActorId());
        assertEquals(LabelEvent.Action.ADD,signal.hint().action());assertEquals(LabelEvent.Origin.WEBHOOK,signal.hint().origin());
    }
    @Test void unrelatedEventKindsDoNotEnterWorkIngress() throws Exception {
        byte[] body=mapper.writeValueAsBytes(body());assertTrue(ingress.translate(headers(body,"pull_request"),body,"https://api.github.com").isEmpty());
    }
    @Test void issuePayloadsContainingPullRequestsAreIgnored() throws Exception {
        ObjectNode root=body();((ObjectNode)root.path("issue")).putObject("pull_request");assertTrue(translate(root).isEmpty());
    }
    @Test void removalIsNotAnAddition() throws Exception {ObjectNode root=body().put("action","unlabeled");assertEquals(LabelEvent.Action.REMOVE,translate(root).getFirst().hint().action());}
    @Test void anEditDoesNotInventALabelApplier() throws Exception {assertNull(translate(body().put("action","edited")).getFirst().hint());}
    @Test void missingSenderDoesNotBorrowTheIssueAuthor() throws Exception {
        ObjectNode root=body();root.remove("sender");((ObjectNode)root.path("issue")).putObject("user").put("id",900123);
        assertNull(translate(root).getFirst().hint().trackerActorId());
    }
    @Test void aBlankLabelIsRejected() {ObjectNode root=body();((ObjectNode)root.path("label")).put("name","");assertThrows(WorkSourceException.class,()->translate(root));}
    @Test void aPathInjectionScopeIsRejected() {ObjectNode root=body();((ObjectNode)root.path("repository")).put("full_name","TEST-owner/../TEST-repo");assertThrows(WorkSourceException.class,()->translate(root));}
    @Test void aScopeWithInvalidCharactersIsRejected() {ObjectNode root=body();((ObjectNode)root.path("repository")).put("full_name","TEST-owner/TEST?repo");assertThrows(WorkSourceException.class,()->translate(root));}
    @Test void aCredentialBearingTrackerLinkIsRejected() {ObjectNode root=body();((ObjectNode)root.path("issue")).put("html_url","https://TEST-person:TEST-secret@github.com/TEST-owner/TEST-repo/issues/42");assertThrows(WorkSourceException.class,()->translate(root));}
    @Test void aScriptTrackerLinkIsRejected() {ObjectNode root=body();((ObjectNode)root.path("issue")).put("html_url","javascript:TEST-script");assertThrows(WorkSourceException.class,()->translate(root));}
    @Test void nonIntegralStableIdsAreRejected() {ObjectNode root=body();((ObjectNode)root.path("issue")).put("id",50001.0);assertThrows(WorkSourceException.class,()->translate(root));}
    @Test void nonPositiveStableIdsAreRejected() {ObjectNode root=body();((ObjectNode)root.path("issue")).put("id",0);assertThrows(WorkSourceException.class,()->translate(root));}
    @Test void overflowingStableIdsAreRejected() {ObjectNode root=body();((ObjectNode)root.path("issue")).put("id",new java.math.BigInteger("18446744073709601617"));assertThrows(WorkSourceException.class,()->translate(root));}
}
