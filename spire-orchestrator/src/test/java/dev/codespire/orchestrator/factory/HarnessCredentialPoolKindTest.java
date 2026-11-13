package dev.codespire.orchestrator.factory;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * A harness is paid with a key of its own vendor (harness reviews 3b): a Claude Code run handed an OpenAI key, or a
 * Codex run handed an Anthropic one, calls a vendor that refuses it and marks a healthy key dead.
 */
@QuarkusTest
class HarnessCredentialPoolKindTest {

    @Inject
    HarnessCredentialPool pool;

    @Inject
    DataSource dataSource;

    private final List<UUID> added = new ArrayList<>();

    /** Only this suite's members are enabled, so a selection is about what this test put in the pool. */
    @BeforeEach
    void onlyOurMembers() {
        sql("UPDATE harness_credential SET enabled = FALSE");
    }

    /** Every row this suite added is deleted, so no later suite finds an enabled member it did not add. */
    @AfterEach
    void deleteOurMembers() {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM harness_credential WHERE id = ?")) {
            for (UUID id : added) {
                ps.setObject(1, id);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cleanup failed", e);
        }
        added.clear();
    }

    private UUID member(String kind) {
        UUID id = pool.add("TEST-kind-" + kind + "-" + UUID.randomUUID(), kind,
                "https://TEST-" + kind + ".invalid", "TEST-agent-key-" + kind).id();
        added.add(id);
        return id;
    }

    private UUID chosen(String kind) {
        return assertInstanceOf(HarnessCredentialPool.Selection.Chosen.class, pool.select(Optional.of(kind)))
                .member().id();
    }

    @Test
    void aClaudeCodeRunNeverGetsAnOpenAiKey() {
        member("openai");
        UUID anthropic = member("anthropic");

        // Ten picks: rotation alone would alternate between the two, so an unfiltered pick hands out the other.
        for (int pick = 0; pick < 10; pick++) {
            assertEquals(anthropic, chosen("anthropic"), "pick " + pick);
        }
    }

    @Test
    void aCodexRunNeverGetsAnAnthropicKey() {
        UUID openai = member("openai");
        member("anthropic");

        for (int pick = 0; pick < 10; pick++) {
            assertEquals(openai, chosen("openai"), "pick " + pick);
        }
    }

    /** The diagnosis counts the kind asked for: a resting OpenAI key is no reason to wait for an Anthropic one. */
    @Test
    void noKeyOfTheKindIsAnEmptyPool() {
        UUID resting = member("openai");
        member("openai");
        pool.markRateLimited(resting, Instant.now().plus(30, ChronoUnit.MINUTES));

        assertInstanceOf(HarnessCredentialPool.Selection.Empty.class, pool.select(Optional.of("anthropic")));
    }

    private void sql(String statement) {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(statement);
        } catch (SQLException e) {
            throw new IllegalStateException("setup failed: " + statement, e);
        }
    }
}
