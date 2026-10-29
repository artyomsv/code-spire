package dev.codespire.orchestrator.work;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every table a work-item read can reach has a change-feed decision (spec §6, "coverage check"). The files are derived
 * from the {@code @Inject} graph of the four resources, not listed, because a read's SQL often sits one class away.
 *
 * <p>The graph also walks the action (POST) helpers, so it over-approximates; the tables only actions or background
 * jobs reach are EXEMPT with that reason, traced on 2026-10-29. Limit: a GET that starts reading an already-exempt
 * table is not caught here; a table new to the graph is.
 */
@QuarkusTest
class WorkItemChangeCoverageTest {

    private static final Path SOURCES = Path.of("src/main/java/dev/codespire/orchestrator");
    private static final List<String> RESOURCES = List.of("WorkItemResource", "WorkDraftResource", "WorkStepsResource", "WorkGateResource");
    private static final Pattern INJECTED = Pattern.compile(
            "@Inject\\s+(?:@\\w+(?:\\([^)]*\\))?\\s+)*(?:(?:public|protected|private|final)\\s+)*(?:[\\w.]+\\.)?([A-Z]\\w*)(?:<[^>]*>)?\\s+\\w+\\s*;");
    private static final Pattern READ = Pattern.compile("(?i)\\b(?:from|join)\\s+([a-z_]+)");

    private static final Set<String> ITEM = Set.of("work_item", "work_item_gate", "work_run_effect", "work_land_effect",
            "work_item_preparation_attempt", "work_tracker_outbox", "work_step_summary", "work_item_artifact");
    private static final Set<String> SHARED = Set.of("repository_land_settings", "repository", "work_source", "work_source_actor",
            "scm_provider", "repository_account");
    private static final String ACTION_ONLY = "reached only by an action or a background job; no work-item GET reads it";
    private static final Map<String, String> EXEMPT = exempt();

    @Inject DataSource dataSource;

    @Test
    void everyTableAWorkItemReadSelectsHasADecision() throws Exception {
        Map<String, String> sources = sources();
        Set<String> tables = tables();
        Set<String> triggered = triggeredTables();
        List<String> missing = new ArrayList<>();
        for (String resource : RESOURCES) {
            Set<String> reached = reached(sources, resource, tables);
            assertFalse(reached.isEmpty(), resource + " reaches no table: the derivation is broken, not the map");
            for (String table : reached) {
                if (ITEM.contains(table) || SHARED.contains(table)) {
                    if (!triggered.contains(table)) missing.add(table + " (mapped, but V104 gives it no work_item_changed_ trigger)");
                } else if (!EXEMPT.containsKey(table)) {
                    missing.add(table + " (read via " + resource + "): add an item trigger, a shared trigger, or an EXEMPT reason");
                }
            }
        }
        assertEquals(List.of(), missing.stream().distinct().sorted().toList());
    }

    @Test
    void everyTriggeredTableIsInTheMap() throws Exception {
        Set<String> unmapped = new TreeSet<>(triggeredTables());
        unmapped.removeAll(ITEM);
        unmapped.removeAll(SHARED);
        assertEquals(Set.of(), unmapped, "a table with a work_item_changed_ trigger must be ITEM or SHARED here");
    }

    private static Set<String> reached(Map<String, String> sources, String resource, Set<String> tables) {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(declaring(sources, resource));
        while (!queue.isEmpty()) {
            String file = queue.pop();
            if (!seen.add(file)) continue;
            Matcher injected = INJECTED.matcher(sources.get(file));
            while (injected.find()) queue.addAll(declaring(sources, injected.group(1)));
        }
        Set<String> reached = new TreeSet<>();
        for (String file : seen) {
            Matcher read = READ.matcher(sources.get(file));
            while (read.find()) if (tables.contains(read.group(1).toLowerCase())) reached.add(read.group(1).toLowerCase());
        }
        return reached;
    }

    /** The files that declare {@code type}: its class, or a class implementing it (an interface's beans). */
    private static List<String> declaring(Map<String, String> sources, String type) {
        Pattern declares = Pattern.compile("\\bclass\\s+" + type + "\\b|\\bimplements\\s+[\\w\\s,.<>]*\\b" + type + "\\b");
        return sources.entrySet().stream().filter(entry -> declares.matcher(entry.getValue()).find()).map(Map.Entry::getKey).toList();
    }

    private static Map<String, String> sources() throws IOException {
        Map<String, String> sources = new HashMap<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) sources.put(file.toString(), Files.readString(file));
        }
        assertTrue(sources.size() > 100, "read " + sources.size() + " sources from " + SOURCES.toAbsolutePath());
        return sources;
    }

    private Set<String> tables() throws SQLException {
        return names("SELECT table_name FROM information_schema.tables WHERE table_schema = 'orchestrator'");
    }

    private Set<String> triggeredTables() throws SQLException {
        return names("""
                SELECT DISTINCT c.relname FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'orchestrator' AND NOT t.tgisinternal AND t.tgname LIKE 'work\\_item\\_changed\\_%'""");
    }

    private Set<String> names(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            Set<String> names = new TreeSet<>();
            while (rs.next()) names.add(rs.getString(1));
            return names;
        }
    }

    private static Map<String, String> exempt() {
        Map<String, String> exempt = new HashMap<>();
        exempt.put("event_log", "covered: WorkItemStore.append upserts work_item in the same transaction as every work-item event");
        exempt.put("factory_run", "step_index is set once when a run is dispatched and never updated");
        exempt.put("operator_seen", "accepted staleness: only the display name beside a past decision");
        for (String table : List.of("autonomy_profile", "autonomy_profile_version", "work_label_mapping", "work_repository_policy",
                "harness_catalogue", "harness_credential", "llm_model", "llm_model_rate", "llm_provider", "context_provider",
                "provider_author", "repository_fix_actor", "repository_build_defaults", "work_activity_receipt", "work_land_observation",
                "work_phase_attempt", "work_prepare_effect", "work_verify_effect",
                // The attention feed's reads, reached through AttentionBroadcaster from writers.
                "app_setting", "dlq_entry", "llm_charge", "review_status")) {
            exempt.put(table, ACTION_ONLY);
        }
        return Map.copyOf(exempt);
    }
}
