package dev.codespire.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.*;
import dev.codespire.gateway.registry.WebhookRepoRegistry;
import dev.codespire.scm.github.GitHubIngress;
import dev.codespire.worksource.WorkSourceSignal;
import dev.codespire.worksource.github.GitHubWorkIngress;
import jakarta.ws.rs.core.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.jupiter.api.Assertions.*;

class WorkWebhookEdgeTest {
    final ObjectMapper mapper=new ObjectMapper();
    final UUID repository=UUID.randomUUID(),source=UUID.randomUUID(),registration=UUID.randomUUID();
    final String secret="TEST-work-secret";
    WebhookRepoRegistry.Resolved configured=new WebhookRepoRegistry.Resolved("github","repo","TEST-owner/TEST-repo",secret,
            registration,1,repository,"https://api.github.com",RepositoryEventKind.ISSUE,source);
    String rejection;int work,review;boolean ack=true;
    final byte[] body=("""
            {"action":"labeled","repository":{"id":10001,"full_name":"TEST-owner/TEST-repo"},
             "issue":{"id":50001,"number":42,"html_url":"https://github.com/TEST-owner/TEST-repo/issues/42",
                      "title":"TEST-private-title","body":"TEST-private-body","updated_at":"2026-09-13T12:00:00Z"},
             "label":{"name":"TEST-autonomous"},"sender":{"id":900123}}
            """).getBytes(StandardCharsets.UTF_8);
    RegistryWebhookEdge edge(){
        RegistryWebhookEdge edge=new RegistryWebhookEdge();
        edge.registry=new WebhookRepoRegistry(){
            @Override public Optional<Resolved> findByKey(String key){return Optional.ofNullable(configured);}
            @Override public void recordRejection(String key,String reason){rejection=reason;}
            @Override public void clearRejections(String key){rejection=null;}
        };
        edge.publisher=new IntegrationPublisher(){@Override public boolean publishAllAwait(WebhookRepoRegistry.Resolved row,List<IntegrationEvent> events,String delivery){review++;return true;}};
        edge.workPublisher=new WorkIngressPublisher(){@Override public boolean publishAwait(WebhookRepoRegistry.Resolved row,List<WorkSourceSignal> signals,String delivery){work+=signals.size();return ack;}};
        return edge;
    }
    HttpHeaders headers(byte[] signed) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        MultivaluedMap<String,String> headers=new MultivaluedHashMap<>();headers.putSingle("X-GitHub-Event","issues");
        headers.putSingle("X-Hub-Signature-256","sha256="+HexFormat.of().formatHex(mac.doFinal(signed)));
        return (HttpHeaders)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{HttpHeaders.class},
                (proxy,method,args)->{if(method.getName().equals("getRequestHeaders"))return headers;throw new UnsupportedOperationException(method.getName());});
    }
    int send() throws Exception{return edge().handle("github","TEST-key",s->new GitHubIngress(s,mapper,Set.of()),s->new GitHubWorkIngress(s,mapper),headers(body),body).getStatus();}
    @Test void aSignedBoundIssueUsesOnlyWorkIngress() throws Exception{assertEquals(202,send());assertEquals(1,work);assertEquals(0,review);assertNull(rejection);}
    @Test void aBadSignatureCannotPublishWork() throws Exception{assertEquals(401,edge().handle("github","TEST-key",s->new GitHubIngress(s,mapper,Set.of()),s->new GitHubWorkIngress(s,mapper),headers(new byte[0]),body).getStatus());assertEquals(0,work);}
    @Test void aForeignScopeCannotPublishWork() throws Exception{configured=new WebhookRepoRegistry.Resolved("github","repo","TEST-other/TEST-repo",secret,registration,1,repository,"https://api.github.com",RepositoryEventKind.ISSUE,source);assertEquals(400,send());assertEquals("out_of_scope",rejection);assertEquals(0,work);}
    @Test void anUnboundSourceCannotPublishWork() throws Exception{configured=new WebhookRepoRegistry.Resolved("github","repo","TEST-owner/TEST-repo",secret,registration,1,repository,"https://api.github.com",RepositoryEventKind.ISSUE,null);assertEquals(400,send());assertEquals(0,work);}
    @Test void anUnboundRepositoryCannotPublishWork() throws Exception{configured=new WebhookRepoRegistry.Resolved("github","repo","TEST-owner/TEST-repo",secret,registration,1,null,"https://api.github.com",RepositoryEventKind.ISSUE,source);assertEquals(400,send());assertEquals(0,work);}
    @Test void unknownOriginCannotPublishWork() throws Exception{configured=new WebhookRepoRegistry.Resolved("github","repo","TEST-owner/TEST-repo",secret,registration,1,repository,null,RepositoryEventKind.ISSUE,source);assertEquals(400,send());assertEquals(0,work);}
    @Test void anOrganisationScopeCannotAdmitAnIssue() throws Exception{configured=new WebhookRepoRegistry.Resolved("github","org","TEST-owner/TEST-repo",secret,registration,1,repository,"https://api.github.com",RepositoryEventKind.ISSUE,source);assertEquals(400,send());assertEquals(0,work);}
    @Test void missingWorkAdapterCannotAcknowledgeAnIssue() throws Exception{assertEquals(400,edge().handle("github","TEST-key",s->new GitHubIngress(s,mapper,Set.of()),headers(body),body).getStatus());assertEquals(0,work);}
    @Test void missingBrokerAcknowledgementReturnsFailure() throws Exception{ack=false;assertEquals(500,send());}
}
