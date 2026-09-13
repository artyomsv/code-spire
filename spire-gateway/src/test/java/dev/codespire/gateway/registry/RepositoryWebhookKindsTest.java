package dev.codespire.gateway.registry;

import dev.codespire.contract.event.RepositoryEventKind;
import dev.codespire.encryption.EncryptionService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.HexFormat;
import java.util.UUID;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class RepositoryWebhookKindsTest {
    @Inject DataSource dataSource;
    @Inject EncryptionService encryption;
    @Inject WebhookRepoRegistry registry;

    @Test void refusesASecondWebhookForTheSameKind() throws Exception {
        String schema = "test_webhook_kind_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
        try {
            WebhookRepoRegistry isolated = new WebhookRepoRegistry();
            isolated.dataSource = scoped(schema); isolated.encryption = encryption;
            UUID repository = UUID.randomUUID();
            WebhookRepoInput input = input(repository, RepositoryEventKind.REVIEWER, "TEST-kind/repo");
            isolated.create(input);
            isolated.create(input(repository, RepositoryEventKind.FACTORY, "TEST-kind/repo"));
            assertEquals(2, isolated.list().size(), "A different kind must be allowed for the same repository");
            IllegalStateException rejected = assertThrows(IllegalStateException.class, () -> isolated.create(input));
            assertEquals("23505", ((java.sql.SQLException) rejected.getCause()).getSQLState(),
                    "The production UNIQUE constraint must refuse the duplicate");
            assertEquals(2, isolated.list().size());
        } finally {
            try (Connection c = dataSource.getConnection(); var statement = c.createStatement()) {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test void preservesLegacyKeyAndRejectsWrongKind() throws Exception {
        String schema = "test_webhook_kind_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("3").load().migrate();
        DataSource legacyData = scoped(schema);
        WebhookRepoRegistry upgraded = new WebhookRepoRegistry();
        upgraded.dataSource = legacyData; upgraded.encryption = encryption;
        upgraded.attention = new dev.codespire.gateway.attention.WebhookAttentionBroadcaster() {
            @Override public void refresh() { /* This isolated schema has no dashboard subscribers. */ }
        };
        UUID id = UUID.randomUUID();
        String key = "TEST-legacy-" + id;
        String ciphertext = encryption.encryptString("TEST-legacy-secret", "webhook:" + id);
        try {
            try (Connection c = legacyData.getConnection(); var ps = c.prepareStatement("""
                    INSERT INTO webhook_repo(id,provider_type,scope,target,webhook_key,webhook_secret,forge_origin,
                                             rejection_count,last_rejection_reason)
                    VALUES (?,'github','repo','TEST-kind/repo',?,?,'https://api.github.com',3,'bad_signature')
                    """)) {
                ps.setObject(1, id); ps.setString(2, key); ps.setString(3, ciphertext); ps.executeUpdate();
            }
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
            assertEquals(RepositoryEventKind.REVIEWER, upgraded.findByKey(key).orElseThrow().eventKind());
            upgraded.update(id, input(UUID.randomUUID(), RepositoryEventKind.FACTORY, "TEST-kind/repo"));
            WebhookRepoRegistry.Resolved resolved = upgraded.findByKey(key).orElseThrow();
            assertEquals("TEST-legacy-secret", resolved.secret());
            try (Connection c = legacyData.getConnection(); var ps = c.prepareStatement(
                    "SELECT webhook_key,webhook_secret,rejection_count FROM webhook_repo WHERE id=?")) {
                ps.setObject(1, id);
                try (var rows = ps.executeQuery()) {
                    assertTrue(rows.next()); assertEquals(key, rows.getString(1));
                    assertEquals(ciphertext, rows.getString(2)); assertEquals(3, rows.getInt(3));
                }
            }
            byte[] body = comment("TEST-kind/repo");
            io.quarkus.test.junit.QuarkusMock.installMockForType(upgraded, WebhookRepoRegistry.class);
            signed(key, "TEST-legacy-secret", body).then().statusCode(400);
            upgraded.update(id, new WebhookRepoInput("github", "repo", "TEST-kind/repo", true,
                    "https://api.github.com", null, RepositoryEventKind.REVIEWER, null));
            signed(key, "TEST-legacy-secret", body).then().statusCode(202);
        } finally {
            try (Connection c = dataSource.getConnection(); var statement = c.createStatement()) {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test void validSignatureCannotCrossRepositoryScope() throws Exception {
        WebhookRepoSecret created = registry.create(input(UUID.randomUUID(), RepositoryEventKind.REVIEWER, "TEST-kind/repo"));
        try {
            signed(created.repo().webhookKey(), created.secret(), comment("TEST-kind/repo")).then().statusCode(202);
            signed(created.repo().webhookKey(), created.secret(), comment("TEST-kind/other")).then().statusCode(400);
        } finally { registry.delete(UUID.fromString(created.repo().id())); }
    }

    private static WebhookRepoInput input(UUID repository, RepositoryEventKind kind, String target) {
        return new WebhookRepoInput("github", "repo", target, true, "https://api.github.com", repository, kind, null);
    }

    private static byte[] comment(String repository) {
        return ("{\"action\":\"created\",\"repository\":{\"full_name\":\"" + repository + "\"},"
                + "\"issue\":{\"number\":7,\"pull_request\":{}},\"comment\":{\"id\":21,\"body\":\"/review\","
                + "\"user\":{\"id\":42,\"login\":\"TEST-author\"}}}").getBytes(StandardCharsets.UTF_8);
    }

    private static io.restassured.response.Response signed(String key, String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return given().header("X-GitHub-Event", "issue_comment")
                .header("X-Hub-Signature-256", "sha256=" + HexFormat.of().formatHex(mac.doFinal(body)))
                .body(body).post("/webhooks/github/" + key);
    }

    private DataSource scoped(String schema) {
        return (DataSource) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("getConnection")) return method.invoke(dataSource, args);
                    Connection connection = dataSource.getConnection();
                    String previous = connection.getSchema(); connection.setSchema(schema);
                    return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                            (p, operation, parameters) -> {
                                if (operation.getName().equals("close")) connection.setSchema(previous);
                                try { return operation.invoke(connection, parameters); }
                                catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                            });
                });
    }
}
