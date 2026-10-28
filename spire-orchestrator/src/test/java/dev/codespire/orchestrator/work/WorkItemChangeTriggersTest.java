package dev.codespire.orchestrator.work;

import dev.codespire.orchestrator.factory.LandSettings;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The database says which work item changed (spec §4.2): a committed write to a table a work-item screen reads sends
 * that item's id, a table that affects many items sends {@code '*'}, and bookkeeping that no screen shows sends nothing.
 */
@QuarkusTest
@TestSecurity(user = "TEST-operator", roles = "spire-admin")
class WorkItemChangeTriggersTest extends WorkFixture {

    private static final Duration QUIET = Duration.ofMillis(300);
    private static final String HEAD = "0".repeat(40);
    private static final String SHA = "a".repeat(64);
    private static final long GENERATION = 999;

    @Inject LandSettings landSettings;

    @BeforeEach
    void admitTheItem() throws Exception {
        intake.accept(signed("900123"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"work_item", "work_item_gate", "work_run_effect", "work_land_effect", "work_item_preparation_attempt",
            "work_tracker_outbox", "work_step_summary", "work_item_artifact"})
    void aCommittedWriteToEachItemTableNotifiesItsId(String table) throws Exception {
        ItemRow row = itemRow(table);
        try (NoticeTap tap = NoticeTap.listen()) {
            tap.drain(QUIET);
            executeWith(row.insert(), row.insertValues());
            assertEquals(List.of(row.itemId()), tap.drain(QUIET), table + ": insert");
            executeWith(row.update(), row.key());
            assertEquals(List.of(row.itemId()), tap.drain(QUIET), table + ": update of a shown column");
            executeWith(row.delete(), row.key());
            assertEquals(List.of(row.itemId()), tap.drain(QUIET), table + ": delete");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"repository_land_settings", "repository", "work_source", "work_source_actor", "scm_provider", "repository_account"})
    void aWriteToEachSharedTableNotifiesEveryItem(String table) throws Exception {
        try (NoticeTap tap = NoticeTap.listen()) {
            tap.drain(QUIET);
            switch (table) {
                case "repository_land_settings" -> {
                    executeWith("INSERT INTO repository_land_settings(repository_id,merge_method,ticket_rule,fix_attempts,updated_by) VALUES (?,'merge','comment',0,'TEST-operator')", repository);
                    assertTrue(tap.drain(QUIET).contains("*"), "insert");
                    executeWith("UPDATE repository_land_settings SET fix_attempts=1 WHERE repository_id=?", repository);
                }
                case "repository" -> executeWith("UPDATE repository SET reviewer_may_approve = NOT reviewer_may_approve WHERE id=?", repository);
                case "work_source" -> executeWith("UPDATE work_source SET name=? WHERE id=?", "TEST-renamed-" + UUID.randomUUID(), source);
                case "work_source_actor" -> executeWith("UPDATE work_source_actor SET display_name=? WHERE source_id=?", "TEST-renamed-" + UUID.randomUUID(), source);
                case "scm_provider" -> executeWith("UPDATE scm_provider SET name=? WHERE id=?", "TEST-renamed-" + UUID.randomUUID(), account);
                case "repository_account" -> {
                    executeWith("DELETE FROM repository_account WHERE repository_id=? AND role='REVIEWER'", repository);
                    tap.drain(QUIET);
                    executeWith("INSERT INTO repository_account(repository_id,account_id,role) VALUES (?,?,'REVIEWER')", repository, account);
                }
                default -> fail("no write for " + table);
            }
            assertTrue(tap.drain(QUIET).contains("*"), table + ": a shared table must refresh every item");
        }
    }

    @Test
    void aRolledBackWriteNotifiesNothing() throws Exception {
        try (NoticeTap tap = NoticeTap.listen()) {
            tap.drain(QUIET);
            try (Connection c = dataSource.getConnection()) {
                c.setAutoCommit(false);
                try (PreparedStatement ps = c.prepareStatement("UPDATE work_item SET reason='TEST-rolled-back' WHERE id=?")) {
                    ps.setString(1, itemId);
                    assertEquals(1, ps.executeUpdate());
                }
                c.rollback();
                c.setAutoCommit(true);
            }
            assertEquals(List.of(), tap.drain(QUIET));
        }
    }

    @Test
    void manyWritesInOneTransactionNotifyOnce() throws Exception {
        try (NoticeTap tap = NoticeTap.listen()) {
            tap.drain(QUIET);
            try (Connection c = dataSource.getConnection()) {
                c.setAutoCommit(false);
                for (String reason : List.of("TEST-one", "TEST-two", "TEST-three")) {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE work_item SET reason=? WHERE id=?")) {
                        ps.setString(1, reason); ps.setString(2, itemId); ps.executeUpdate();
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO work_item_artifact(id,work_item_id,kind,body,sha256) VALUES (?,?,'SPEC',?,?)")) {
                    ps.setObject(1, UUID.randomUUID()); ps.setString(2, itemId); ps.setBytes(3, new byte[]{1}); ps.setString(4, SHA);
                    ps.executeUpdate();
                }
                c.commit();
                c.setAutoCommit(true);
            }
            assertEquals(List.of(itemId), tap.drain(QUIET));
        }
    }

    @Test
    void writesWithoutAnItemEventNotifyOnTheirOwn() throws Exception {
        UUID tracker = trackerRow();
        UUID land = landRow("TEST-reason", null);
        try (NoticeTap tap = NoticeTap.listen()) {
            tap.drain(QUIET);
            // WorkSourceEffects.mark: a tracker comment marked sent, written to work_tracker_outbox only.
            executeWith("UPDATE work_tracker_outbox SET state=?,remote_id=?,reason=?,checked_at=now() WHERE effect_id=? AND state=?",
                    "sent", "TEST-remote", null, tracker, "pending");
            assertEquals(List.of(itemId), tap.drain(QUIET), "tracker comment marked sent");

            landSettings.save(repository, new LandSettings.Input(landSettings.get(repository).revision(), "squash", "comment", 1), "TEST-operator");
            assertTrue(tap.drain(QUIET).contains("*"), "land settings saved");

            executeWith("INSERT INTO work_step_summary(work_item_id,generation,step_index,run_id,summary,state) VALUES (?,?,0,'TEST-run',NULL,'summary_missing')",
                    itemId, GENERATION);
            assertEquals(List.of(itemId), tap.drain(QUIET), "step summary written");

            executeWith("UPDATE work_land_effect SET state='checking',updated_at=now() WHERE effect_id=?", land);
            assertEquals(List.of(itemId), tap.drain(QUIET), "land effect moved state");
        }
    }

    @Test
    void bookkeepingAloneNotifiesNothing() throws Exception {
        UUID tracker = trackerRow();
        Timestamp since = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        UUID land = landRow("TEST-failing", since);
        try (NoticeTap tap = NoticeTap.listen()) {
            tap.drain(QUIET);
            // WorkActivityPoller, every 2 minutes per item.
            executeWith("UPDATE work_item SET activity_polled_at=now() WHERE id=?", itemId);
            assertEquals(List.of(), tap.drain(QUIET), "activity poll stamp");
            // WorkItemStore.reserve.
            executeWith("UPDATE work_item SET slot_reserved=NOT slot_reserved WHERE id=?", itemId);
            assertEquals(List.of(), tap.drain(QUIET), "slot reservation");
            // WorkSourceScanner, every 30 seconds per source.
            executeWith("UPDATE work_source SET scan_cursor=?,scan_requested=?,scan_batch=NULL,scan_next_cursor=NULL,health='healthy',checked_at=now() WHERE id=?",
                    "TEST-cursor-" + UUID.randomUUID(), false, source);
            assertEquals(List.of(), tap.drain(QUIET), "scanner cursor");
            // WorkSourceRegistry health.
            executeWith("UPDATE work_source SET health=?,checked_at=now() WHERE id=?", "TEST-unhealthy", source);
            assertEquals(List.of(), tap.drain(QUIET), "source health");
            // WorkSourceEffects.touch: a failed tracker row deferred.
            executeWith("UPDATE work_tracker_outbox SET checked_at=now() WHERE effect_id=?", tracker);
            assertEquals(List.of(), tap.drain(QUIET), "tracker retry stamp");
            // WorkLandEffects.backOff with the reason and failing-since it already has.
            executeWith("UPDATE work_land_effect SET reason=?,failing_since=?,next_check_at=?,updated_at=now() WHERE effect_id=? AND state=?",
                    "TEST-failing", since, Timestamp.from(Instant.now().plusSeconds(120)), land, "pending");
            assertEquals(List.of(), tap.drain(QUIET), "land re-check deferred");
            // ProviderRegistry health check.
            executeWith("UPDATE scm_provider SET last_check_at = now(), last_check_ok = ?, last_check_error = ? WHERE id = ?", true, null, account);
            assertEquals(List.of(), tap.drain(QUIET), "provider health check");
            // A rewrite that changes no value.
            executeWith("UPDATE work_item SET reason=reason WHERE id=?", itemId);
            assertEquals(List.of(), tap.drain(QUIET), "an unchanged row");
        }
    }

    /** The three writes of one item-table row, and the id its notices must carry. */
    private record ItemRow(String itemId, String insert, Object[] insertValues, String update, String delete, Object key) {}

    private ItemRow itemRow(String table) throws SQLException {
        UUID id = UUID.randomUUID();
        return switch (table) {
            case "work_item" -> {
                // The row exists only to be deleted; its insert is the one under test, so it is replayed below.
                String extra = copyItem(itemId);
                executeWith("DELETE FROM work_item WHERE id=?", extra);
                yield new ItemRow(extra, """
                        INSERT INTO work_item(id,source_id,repository_id,issue_id,issue_key,tracker_url,generation,policy_revision,phase,workflow_status,reason,revision,updated_at)
                        SELECT ?,source_id,repository_id,?,'TEST-1',tracker_url,1,policy_revision,phase,workflow_status,reason,1,now() FROM work_item WHERE id=?""",
                        new Object[]{extra, "TEST-issue-" + extra, itemId}, "UPDATE work_item SET reason='TEST-changed' WHERE id=?",
                        "DELETE FROM work_item WHERE id=?", extra);
            }
            case "work_item_gate" -> new ItemRow(itemId, "INSERT INTO work_item_gate(id,work_item_id,generation,phase,state) VALUES (?,?,?,'TEST-phase','OPEN')",
                    new Object[]{id, itemId, GENERATION}, "UPDATE work_item_gate SET artifact='TEST-artifact' WHERE id=?", "DELETE FROM work_item_gate WHERE id=?", id);
            case "work_run_effect" -> {
                executeWith("INSERT INTO work_phase_attempt(id,work_item_id,generation,phase,state,started_at) VALUES (?,?,?,'TEST-phase','started',now())",
                        id, itemId, GENERATION);
                yield new ItemRow(itemId, "INSERT INTO work_run_effect(attempt_id,work_item_id,generation,state) VALUES (?,?,?,'pending')",
                        new Object[]{id, itemId, GENERATION}, "UPDATE work_run_effect SET reason='TEST-reason' WHERE attempt_id=?",
                        "DELETE FROM work_run_effect WHERE attempt_id=?", id);
            }
            case "work_land_effect" -> new ItemRow(itemId, """
                    INSERT INTO work_land_effect(effect_id,work_item_id,generation,item_revision,pr_number,approved_head,state) VALUES (?,?,?,1,1,?,'pending')""",
                    new Object[]{id, itemId, GENERATION, HEAD}, "UPDATE work_land_effect SET reason='TEST-reason' WHERE effect_id=?",
                    "DELETE FROM work_land_effect WHERE effect_id=?", id);
            case "work_item_preparation_attempt" -> new ItemRow(itemId, """
                    INSERT INTO work_item_preparation_attempt(work_item_id,generation,reason,retry_after) VALUES (?,?,'TEST-reason',now())""",
                    new Object[]{itemId, GENERATION}, "UPDATE work_item_preparation_attempt SET reason='TEST-changed' WHERE work_item_id=? AND generation=" + GENERATION,
                    "DELETE FROM work_item_preparation_attempt WHERE work_item_id=? AND generation=" + GENERATION, itemId);
            case "work_tracker_outbox" -> new ItemRow(itemId, """
                    INSERT INTO work_tracker_outbox(effect_id,work_item_id,generation,item_revision,phase,payload) VALUES (?,?,?,1,'TEST-phase',?)""",
                    new Object[]{id, itemId, GENERATION, new byte[]{1}}, "UPDATE work_tracker_outbox SET reason='TEST-reason' WHERE effect_id=?",
                    "DELETE FROM work_tracker_outbox WHERE effect_id=?", id);
            case "work_step_summary" -> new ItemRow(itemId, """
                    INSERT INTO work_step_summary(work_item_id,generation,step_index,run_id,summary,state) VALUES (?,?,0,'TEST-run',NULL,'summary_missing')""",
                    new Object[]{itemId, GENERATION}, "UPDATE work_step_summary SET created_at=created_at - interval '1 second' WHERE work_item_id=? AND run_id='TEST-run'",
                    "DELETE FROM work_step_summary WHERE work_item_id=? AND run_id='TEST-run'", itemId);
            case "work_item_artifact" -> new ItemRow(itemId, "INSERT INTO work_item_artifact(id,work_item_id,kind,body,sha256) VALUES (?,?,'SPEC',?,?)",
                    new Object[]{id, itemId, new byte[]{1}, SHA}, "UPDATE work_item_artifact SET kind='PLAN' WHERE id=?",
                    "DELETE FROM work_item_artifact WHERE id=?", id);
            default -> throw new IllegalArgumentException("no row for " + table);
        };
    }

    private UUID trackerRow() throws SQLException {
        UUID id = UUID.randomUUID();
        executeWith("INSERT INTO work_tracker_outbox(effect_id,work_item_id,generation,item_revision,phase,payload) VALUES (?,?,?,1,'TEST-phase',?)",
                id, itemId, GENERATION, new byte[]{1});
        return id;
    }

    private UUID landRow(String reason, Timestamp failingSince) throws SQLException {
        UUID id = UUID.randomUUID();
        executeWith("INSERT INTO work_land_effect(effect_id,work_item_id,generation,item_revision,pr_number,approved_head,state,reason,failing_since) VALUES (?,?,?,1,1,?,'pending',?,?)",
                id, itemId, GENERATION, HEAD, reason, failingSince);
        return id;
    }
}
