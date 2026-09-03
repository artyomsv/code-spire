package dev.codespire.orchestrator.factory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.codespire.contract.event.RunResult;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The refused paths of a run, between the wire and {@code factory_run.blocked_changes}.
 *
 * <p><b>One home, because two readers exist and they must agree.</b> {@code FactoryRunProjection}
 * reads the column to answer a run's detail, and {@code RunAttentionRows} reads it to write the
 * sentence an operator sees in the bell. A format decided in one and parsed in the other is the
 * shape this repository keeps paying for.
 *
 * <p>Stored as JSON text, not JSONB and not a second table: it matches {@code posted_findings_json},
 * it is written once and read whole, and nothing queries inside it.
 *
 * <p><b>A path is not encrypted.</b> ADR-011 keeps content that can quote source out of a queryable
 * read model, and a file path is neither source nor a secret — the same split {@code review_finding}
 * already makes between its location columns and its message.
 */
public final class BlockedChanges {

    private static final Logger LOG = Logger.getLogger(BlockedChanges.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private BlockedChanges() {
    }

    /** The JSON written to the column. Never null: a refusal that named nothing is not a refusal. */
    public static String toJson(List<RunResult.BlockedChange> blocked) {
        ArrayNode array = JSON.createArrayNode();
        for (RunResult.BlockedChange change : blocked) {
            ObjectNode entry = array.addObject();
            entry.put("path", change.path());
            // Written even when null, so the shape is the same for every row and a reader never has
            // to tell "no kind" from "an older writer".
            entry.put("kind", change.kind());
        }
        return array.toString();
    }

    /**
     * What the column holds, or an empty list.
     *
     * <p>Unparseable text answers empty rather than throwing. This is read while rendering an
     * operator's attention panel and a run's detail page, and a malformed row must not take either
     * of those down — the row's own status still says the run was refused, which is the fact that
     * matters most. The fault is logged so it is not silent.
     */
    public static List<RunResult.BlockedChange> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode array = JSON.readTree(json);
            if (!array.isArray()) {
                // debugf for the same reason as below: this is on a polled surface.
                LOG.debugf("blocked_changes is not a JSON array, ignoring: %s", json);
                return List.of();
            }
            List<RunResult.BlockedChange> changes = new ArrayList<>();
            for (JsonNode entry : array) {
                String path = entry.path("path").asText(null);
                if (path == null) {
                    continue;
                }
                JsonNode kind = entry.path("kind");
                changes.add(new RunResult.BlockedChange(path,
                        kind.isNull() || kind.isMissingNode() ? null : kind.asText(null)));
            }
            return List.copyOf(changes);
        } catch (JsonProcessingException e) {
            // debugf, not warnf: the attention panel is POLLED, so a single malformed row would
            // otherwise write a WARN on every refresh for as long as it exists. What an operator
            // needs is on the row itself, which now says the list is unreadable rather than
            // trailing off mid-sentence.
            LOG.debugf(e, "blocked_changes could not be parsed, reporting no paths for this run");
            return List.of();
        }
    }

    /**
     * One human-readable line: {@code .github/workflows/ci.yml (deleted), README.md (renamed to)}.
     *
     * <p>The kind is lower-cased and its underscore becomes a space, because it sits inside a
     * sentence an operator reads — {@code (renamed_from)} is a wire value showing through. It is
     * omitted entirely when the producer did not report one: {@code (null)} beside a path would read
     * as a third kind rather than as an absence.
     *
     * <p><b>Never the empty string.</b> An unreadable row used to produce "it changed ." in the
     * attention panel — a sentence with a hole in it, which reads as a rendering bug and tells an
     * operator nothing about what to do. The row's own status still says the run was refused, which
     * is the fact that matters most, so say that instead of trailing off.
     */
    public static String describe(String json) {
        List<RunResult.BlockedChange> changes = fromJson(json);
        if (changes.isEmpty()) {
            return "paths this row can no longer report (its stored list is empty or unreadable)";
        }
        List<String> parts = new ArrayList<>();
        for (RunResult.BlockedChange change : changes) {
            parts.add(change.kind() == null ? change.path() : change.path() + " (" + readable(change.kind()) + ")");
        }
        return String.join(", ", parts);
    }

    /** {@code RENAMED_TO} reads as "renamed to" inside a sentence, not as a constant name. */
    private static String readable(String kind) {
        return kind.toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
