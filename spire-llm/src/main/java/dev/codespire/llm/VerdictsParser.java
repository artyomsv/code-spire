package dev.codespire.llm;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.PriorFinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lenient parser for the reconcile call's verdict JSON (mirrors FindingsParser's
 * degraded philosophy): anything unusable is dropped, a fully unusable response
 * yields an empty list — the caller then posts no thread actions, which is the
 * safe degraded behavior (the exclusion list still prevents duplicates).
 */
public final class VerdictsParser {

    private VerdictsParser() {
    }

    public static List<FindingVerdict> parse(String modelOutput, List<PriorFinding> findings) {
        if (modelOutput == null || modelOutput.isBlank()) {
            return List.of();
        }
        JsonNode root = FindingsParser.readLenient(modelOutput);
        if (root == null) {
            return List.of();
        }
        List<FindingVerdict> verdicts = new ArrayList<>();
        for (JsonNode node : root.path("verdicts")) {
            int id = node.path("id").asInt(0);
            FindingVerdict.Status status = status(node.path("status").asText(""));
            if (id < 1 || id > findings.size() || status == null) {
                continue;
            }
            PriorFinding finding = findings.get(id - 1);
            verdicts.add(new FindingVerdict(finding.threadRef(), finding.path(), finding.line(),
                    status, node.path("note").asText("")));
        }
        return List.copyOf(verdicts);
    }

    private static FindingVerdict.Status status(String raw) {
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "RESOLVED", "FIXED" -> FindingVerdict.Status.RESOLVED;
            case "STILL_OPEN", "OPEN", "UNRESOLVED" -> FindingVerdict.Status.STILL_OPEN;
            case "ACKNOWLEDGED", "WONT_FIX", "CONCEDED" -> FindingVerdict.Status.ACKNOWLEDGED;
            case "SUPERSEDED", "OBSOLETE" -> FindingVerdict.Status.SUPERSEDED;
            default -> null;
        };
    }
}
