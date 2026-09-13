package dev.codespire.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.*;
import dev.codespire.contract.port.ScmType;
import dev.codespire.gateway.registry.*;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kafka.*;
import io.smallrye.reactive.messaging.kafka.companion.KafkaCompanion;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest @QuarkusTestResource(KafkaCompanionResource.class)
class GitHubWorkWebhookTest {
    @Inject WebhookRepoRegistry registry;
    @Inject ObjectMapper mapper;
    @Inject DataSource dataSource;
    @InjectKafkaCompanion KafkaCompanion companion;
    WebhookRepoSecret hook;
    final UUID repository=UUID.randomUUID(),source=UUID.randomUUID();
    @BeforeEach void seed(){hook=registry.create(new WebhookRepoInput("github","repo","TEST-work/TEST-repo-"+repository,true,
            "https://api.github.com",repository,RepositoryEventKind.ISSUE,source));}
    @AfterEach void clean() throws Exception {
        try(Connection c=dataSource.getConnection()) {
            // Exact TEST registration cleanup; the DELETE trigger's tombstone is removed second.
            try(PreparedStatement ps=c.prepareStatement("DELETE FROM webhook_repo WHERE id=?")){ps.setObject(1,UUID.fromString(hook.repo().id()));ps.executeUpdate();}
            try(PreparedStatement ps=c.prepareStatement("DELETE FROM repository_snapshot_outbox WHERE registration_id=?")){ps.setObject(1,UUID.fromString(hook.repo().id()));ps.executeUpdate();}
        }
    }
    @Test void signedIssueReachesTheDedicatedTopicWithItsStableKey() throws Exception {
        var from=TopicWatermark.of(companion,"cs.work-integration");
        var root=mapper.createObjectNode().put("action","labeled");
        root.putObject("repository").put("id",10001).put("full_name",hook.repo().target());
        root.putObject("issue").put("id",50001).put("number",42).put("html_url","https://github.com/"+hook.repo().target()+"/issues/42")
                .put("title","TEST-private-title").put("body","TEST-private-body").put("updated_at","2026-09-13T12:00:00Z");
        root.putObject("label").put("name","TEST-autonomous");root.putObject("sender").put("id",900123);
        byte[] body=mapper.writeValueAsBytes(root);Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(hook.secret().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        given().header("X-GitHub-Event","issues").header("X-Hub-Signature-256","sha256="+HexFormat.of().formatHex(mac.doFinal(body)))
                .body(body).post("/webhooks/github/"+hook.repo().webhookKey()).then().statusCode(202);
        var task=companion.consumeStrings().fromOffsets(from,1);task.awaitCompletion(Duration.ofSeconds(15));
        var record=task.getFirstRecord();WorkSourceDelivery delivery=mapper.readValue(record.value(),WorkSourceDelivery.class);
        assertEquals(source,delivery.sourceId());assertEquals(repository,delivery.repositoryId());
        assertEquals(WorkItemIds.of(ScmType.GITHUB,hook.repo().forgeOrigin(),delivery.repo(),delivery.signal().issue().ref()),record.key());
        assertFalse(record.value().contains("TEST-private-title"));assertFalse(record.value().contains("TEST-private-body"));
        assertEquals("900123",delivery.signal().hint().trackerActorId());
    }
}
