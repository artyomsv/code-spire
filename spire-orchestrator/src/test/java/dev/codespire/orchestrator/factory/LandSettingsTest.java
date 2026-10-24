package dev.codespire.orchestrator.factory;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.contract.port.PullRequestLander;
import dev.codespire.orchestrator.provider.ProviderInput;
import dev.codespire.orchestrator.provider.ProviderRegistry;
import dev.codespire.orchestrator.repository.RepositoryInput;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;

/**
 * How a repository lands: merge method, ticket rule and fix attempts (M4 land, spec §7.1).
 *
 * <p>Nothing reads these yet. What is proved here is the contract the land effect will rely on: a
 * repository with no row lands with the operator's defaults, a save never silently overwrites another
 * operator's, and every value the effect will act on is one it can act on. The options read what GitHub
 * allows through the FACTORY account, and an unreachable forge must not lock the setting.
 */
@QuarkusTest
@TestSecurity(user = "TEST-land-admin", roles = "spire-admin")
class LandSettingsTest {

    @Inject LandSettings settings;
    @Inject RepositoryRegistry repositories;
    @Inject ProviderRegistry providers;
    @Inject DataSource dataSource;

    WireMockServer forge;
    UUID account, repository;
    String slug;

    @BeforeEach
    void setup() {
        forge = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        forge.start();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        slug = "TEST-repo-" + suffix;
        account = UUID.fromString(providers.create(new ProviderInput("TEST-land-" + suffix, "github", forge.baseUrl(),
                "bearer", null, "TEST-token", "TEST-bot", true, List.of(), "TEST-bot", null, "FACTORY")).id());
        repository = repositories.create(new RepositoryInput("github", forge.baseUrl(), "TEST-land", slug, true,
                null, account)).id();
    }

    @AfterEach
    void cleanUp() throws SQLException {
        try {
            execute("DELETE FROM repository_land_settings WHERE repository_id=?", repository);
            execute("DELETE FROM repository_account WHERE repository_id=?", repository);
            execute("DELETE FROM repository WHERE id=?", repository);
            execute("DELETE FROM scm_provider WHERE id=?", account);
        } finally {
            forge.stop();
        }
    }

    private void execute(String sql, UUID id) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            ps.executeUpdate();
        }
    }

    private String refusal(LandSettings.Input input, String subject) {
        return assertThrows(LandSettings.Refused.class, () -> settings.save(repository, input, subject)).reason();
    }

    private String refusal(LandSettings.Input input) {
        return refusal(input, "TEST-operator");
    }

    /** The operator's decisions (§6): merge, keep the ticket open with a comment, three attempts. */
    @Test
    void noRowReadsTheDefaults() {
        LandSettings.Settings read = settings.get(repository);

        assertEquals(0, read.revision());
        assertEquals(PullRequestLander.MergeMethod.MERGE, read.mergeMethod());
        assertEquals(LandSettings.TicketRule.COMMENT, read.ticketRule());
        assertEquals(3, read.fixAttempts());
        assertNull(read.updatedBy());
    }

    @Test
    void aSaveNeedsTheExpectedRevision() {
        LandSettings.Settings saved = settings.save(repository, new LandSettings.Input(0, "squash", "close", 5), "TEST-operator");
        assertEquals(1, saved.revision());

        assertEquals("land_settings_changed", refusal(new LandSettings.Input(0, "rebase", "comment", 1)));

        LandSettings.Settings unchanged = settings.get(repository);
        assertEquals(1, unchanged.revision());
        assertEquals(PullRequestLander.MergeMethod.SQUASH, unchanged.mergeMethod());
        assertEquals(LandSettings.TicketRule.CLOSE, unchanged.ticketRule());
        assertEquals(5, unchanged.fixAttempts());
        assertEquals(2, settings.save(repository, new LandSettings.Input(1, "rebase", "comment", 1), "TEST-operator").revision());
    }

    /**
     * Two operators saving at once. Without the transaction the two FOR UPDATE locks release as soon as
     * they are taken, both reads see the same revision and the second write silently overwrites the first.
     * A latch alone leaves which read comes first to the scheduler, so the test holds the repository row
     * itself until both saves wait on it: released together, they would both read before either writes.
     * Three rounds, the first inserting the row and the next two updating it; each must leave one winner.
     */
    @Test
    void twoSavesAtOnceLeaveOneWinnerAndOneRefusal() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (long round = 0; round < 3; round++) {
                long expected = round;
                Callable<String> attempt = () -> {
                    try { return "saved:" + settings.save(repository, new LandSettings.Input(expected, "merge", "comment", 3), "TEST-operator").revision(); }
                    catch (LandSettings.Refused refused) { return refused.reason(); }
                };
                Future<String> first, second;
                try (Connection holder = dataSource.getConnection()) {
                    holder.setAutoCommit(false);
                    try (PreparedStatement ps = holder.prepareStatement("SELECT id FROM repository WHERE id=? FOR UPDATE")) {
                        ps.setObject(1, repository);
                        ps.executeQuery().close();
                    }
                    first = pool.submit(attempt);
                    second = pool.submit(attempt);
                    await().atMost(Duration.ofSeconds(15)).until(() -> waitingOnLocks() >= 2);
                    holder.commit();
                }
                List<String> answers = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
                assertEquals(1, answers.stream().filter(answer -> answer.equals("saved:" + (expected + 1))).count(), "round " + round + " answers: " + answers);
                assertEquals(1, answers.stream().filter(answer -> answer.equals("land_settings_changed")).count(), "round " + round + " answers: " + answers);
                assertEquals(expected + 1, settings.get(repository).revision());
            }
        } finally { pool.shutdownNow(); }
    }

    private long waitingOnLocks() throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'"
                        + " AND pid<>pg_backend_pid()");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Checked in code, not only by the table: a 400 that names the rule, not a constraint violation. */
    @Test
    void attemptsOutsideZeroToTenAreRefused() {
        assertEquals("fix_attempts_invalid", refusal(new LandSettings.Input(0, "merge", "comment", -1)));
        assertEquals("fix_attempts_invalid", refusal(new LandSettings.Input(0, "merge", "comment", 11)));
        assertEquals(0, settings.get(repository).revision());

        assertEquals(0, settings.save(repository, new LandSettings.Input(0, "merge", "comment", 0), "TEST-operator").fixAttempts());
        assertEquals(10, settings.save(repository, new LandSettings.Input(1, "merge", "comment", 10), "TEST-operator").fixAttempts());
    }

    @Test
    void anUnknownMethodOrRuleIsRefused() {
        assertEquals("merge_method_invalid", refusal(new LandSettings.Input(0, "TEST-fast", "comment", 3)));
        assertEquals("ticket_rule_invalid", refusal(new LandSettings.Input(0, "merge", "TEST-move", 3)));
        assertEquals(0, settings.get(repository).revision());
    }

    @Test
    void theSaverIsTheOidcSubject() {
        assertEquals("TEST-subject-42", settings.save(repository, new LandSettings.Input(0, "merge", "comment", 3), "TEST-subject-42").updatedBy());
        assertEquals("operator_identity_required", refusal(new LandSettings.Input(1, "merge", "comment", 3), "  "));

        given().get("/api/repositories/" + repository + "/factory/land").then().statusCode(200)
                .body("revision", is(1), "mergeMethod", is("merge"), "ticketRule", is("comment"), "fixAttempts", is(3),
                        "updatedBy", is("TEST-subject-42"));
        given().contentType(ContentType.JSON)
                .body(Map.of("expectedRevision", 1, "mergeMethod", "squash", "ticketRule", "close", "fixAttempts", 2))
                .put("/api/repositories/" + repository + "/factory/land").then().statusCode(200)
                .body("revision", is(2), "mergeMethod", is("squash"), "ticketRule", is("close"), "updatedBy", is("TEST-land-admin"));
        given().contentType(ContentType.JSON)
                .body(Map.of("expectedRevision", 1, "mergeMethod", "merge", "ticketRule", "comment", "fixAttempts", 2))
                .put("/api/repositories/" + repository + "/factory/land").then().statusCode(409)
                .body("reason", is("land_settings_changed"));
        given().contentType(ContentType.JSON)
                .body(Map.of("expectedRevision", 2, "mergeMethod", "merge", "ticketRule", "comment", "fixAttempts", 11))
                .put("/api/repositories/" + repository + "/factory/land").then().statusCode(400)
                .body("reason", is("fix_attempts_invalid"));
    }

    @Test
    void optionsListOnlyWhatGitHubAllows() {
        forge.stubFor(get(urlEqualTo("/repos/TEST-land/" + slug)).willReturn(okJson(
                "{\"id\":900101,\"full_name\":\"TEST-land/" + slug + "\",\"allow_merge_commit\":false,"
                        + "\"allow_squash_merge\":true,\"allow_rebase_merge\":false}")));

        given().get("/api/repositories/" + repository + "/factory/land/options").then().statusCode(200)
                .body("checked", is(true), "allowed", contains("squash"));
    }

    @Test
    void anUnreachableForgeOffersAllThreeUnchecked() {
        forge.stubFor(get(urlEqualTo("/repos/TEST-land/" + slug)).willReturn(serverError()));

        given().get("/api/repositories/" + repository + "/factory/land/options").then().statusCode(200)
                .body("checked", is(false), "allowed", contains("merge", "squash", "rebase"));
        assertEquals(1, settings.save(repository, new LandSettings.Input(0, "rebase", "comment", 3), "TEST-operator").revision());
    }
}
