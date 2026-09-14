package dev.codespire.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.codespire.contract.event.*;
import dev.codespire.contract.port.ScmType;
import dev.codespire.gateway.registry.*;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kafka.*;
import io.smallrye.reactive.messaging.kafka.companion.KafkaCompanion;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import javax.sql.DataSource;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest @QuarkusTestResource(KafkaCompanionResource.class)
class GitLabWorkWebhookTest {
    @Inject WebhookRepoRegistry registry;
    @Inject ObjectMapper mapper;
    @Inject DataSource dataSource;
    @InjectKafkaCompanion KafkaCompanion companion;
    WebhookRepoSecret hook;
    final UUID repository=UUID.randomUUID(),source=UUID.randomUUID();
    @BeforeEach void seed(){hook=registry.create(new WebhookRepoInput("gitlab","repo","TEST-work/TEST-repo-"+repository,true,
            "https://gitlab.example.test",repository,RepositoryEventKind.ISSUE,source));}
    @AfterEach void clean() throws Exception {
        try(Connection c=dataSource.getConnection()) {
            // Exact TEST registration cleanup; remove the DELETE trigger's tombstone second.
            try(PreparedStatement ps=c.prepareStatement("DELETE FROM webhook_repo WHERE id=?")){ps.setObject(1,UUID.fromString(hook.repo().id()));ps.executeUpdate();}
            try(PreparedStatement ps=c.prepareStatement("DELETE FROM repository_snapshot_outbox WHERE registration_id=?")){ps.setObject(1,UUID.fromString(hook.repo().id()));ps.executeUpdate();}
        }
    }
    ObjectNode payload() {
        var root=mapper.createObjectNode().put("object_kind","issue");
        root.putObject("project").put("id",10001).put("path_with_namespace",hook.repo().target());
        root.putObject("object_attributes").put("project_id",10001).put("id",50001).put("iid",42)
                .put("title","TEST-private-title").put("description","TEST-private-body").put("updated_at","2026-09-13T12:00:00Z");
        var labels=root.putObject("changes").putObject("labels");labels.putArray("previous");labels.putArray("current").addObject().put("title","TEST-auto");
        root.putObject("user").put("id",900123);return root;
    }
    @Test void authenticatedIssueReachesTheDedicatedTopicWithItsStableKey() throws Exception {
        var from=TopicWatermark.of(companion,"cs.work-integration");
        given().header("X-Gitlab-Event","Issue Hook").header("X-Gitlab-Token",hook.secret())
                .body(mapper.writeValueAsBytes(payload())).post("/webhooks/gitlab/"+hook.repo().webhookKey()).then().statusCode(202);
        var task=companion.consumeStrings().fromOffsets(from,1);task.awaitCompletion(Duration.ofSeconds(15));
        var record=task.getFirstRecord();WorkSourceDelivery delivery=mapper.readValue(record.value(),WorkSourceDelivery.class);
        assertEquals(source,delivery.sourceId());assertEquals(repository,delivery.repositoryId());
        assertEquals(WorkItemIds.of(ScmType.GITLAB,hook.repo().forgeOrigin(),delivery.repo(),delivery.signal().issue().ref()),record.key());
        assertFalse(record.value().contains("TEST-private-title"));assertFalse(record.value().contains("TEST-private-body"));
        assertEquals("900123",delivery.signal().hint().trackerActorId());assertEquals("TEST-auto",delivery.signal().hint().label());
    }
    @Test void wrongTokenIsRejectedBeforeIssueTranslation() throws Exception {
        given().header("X-Gitlab-Event","Issue Hook").header("X-Gitlab-Token","TEST-wrong")
                .body(mapper.writeValueAsBytes(payload())).post("/webhooks/gitlab/"+hook.repo().webhookKey()).then().statusCode(401);
    }
    @Test void aSignedIssueFromAnotherProjectIsRejected() throws Exception {
        var root=payload();((ObjectNode)root.path("project")).put("path_with_namespace","TEST-other/TEST-repo");
        given().header("X-Gitlab-Event","Issue Hook").header("X-Gitlab-Token",hook.secret())
                .body(mapper.writeValueAsBytes(root)).post("/webhooks/gitlab/"+hook.repo().webhookKey()).then().statusCode(400);
    }
}
