package dev.codespire.scm.github;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.contract.port.PullRequestLander.CheckSummary;
import dev.codespire.contract.port.PullRequestLander.Checks;
import dev.codespire.contract.port.PullRequestLander.FailedCheck;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Reduces a head's commit statuses and check runs to one land verdict (spec A4). It reads the
 * entries themselves, never GitHub's combined {@code state}, which is {@code pending} for a commit
 * with no statuses at all: an empty set is PASSED. Anything this code does not recognise is
 * NOT_CODE, so a conclusion GitHub adds later can never pass a merge.
 */
final class GitHubCheckReduction {
    static final int MAX_FAILED = 20;
    static final int MAX_TEXT = 2000;
    static final int MAX_ANNOTATIONS = 5;
    static final int MAX_ANNOTATION = 300;

    private static final Set<String> PASSING_CONCLUSIONS = Set.of("success", "neutral", "skipped");
    private static final Set<String> RUNNING_STATUSES = Set.of("queued", "in_progress", "waiting", "requested", "pending");

    private GitHubCheckReduction() {}

    static CheckSummary reduce(JsonNode statuses, List<JsonNode> checkRuns) {
        return reduce(statuses, checkRuns, run -> List.of());
    }

    /** {@code annotations} is asked only for a failed check run that is kept in the capped summary. */
    static CheckSummary reduce(JsonNode statuses, List<JsonNode> checkRuns,
                               Function<JsonNode, List<String>> annotations) {
        Checks state = Checks.PENDING;
        int done = 0;
        int total = 0;
        List<FailedCheck> failed = new ArrayList<>();
        for (JsonNode status : statuses) {
            Checks entry = statusVerdict(status.path("state").asText(""));
            total++;
            if (entry != Checks.PENDING) done++;
            state = worse(state, entry);
            if (keep(entry, failed)) {
                failed.add(new FailedCheck(status.path("context").asText(""), status.path("state").asText(""),
                        cap(status.path("description").asText(""), MAX_TEXT), "", List.of()));
            }
        }
        for (JsonNode run : checkRuns) {
            Checks entry = runVerdict(run);
            total++;
            if (entry != Checks.PENDING) done++;
            state = worse(state, entry);
            if (keep(entry, failed)) failed.add(failedRun(run, entry, annotations));
        }
        return new CheckSummary(state, done, total, failed);
    }

    static Checks statusVerdict(String state) {
        return switch (state) {
            case "success" -> Checks.PASSED;
            case "failure" -> Checks.FAILED;
            case "pending" -> Checks.PENDING;
            // "error" is infrastructure, not code; an unknown state never passes.
            default -> Checks.NOT_CODE;
        };
    }

    static Checks runVerdict(JsonNode run) {
        String status = run.path("status").asText("");
        if (!"completed".equals(status)) return RUNNING_STATUSES.contains(status) ? Checks.PENDING : Checks.NOT_CODE;
        String conclusion = run.path("conclusion").asText("");
        if (PASSING_CONCLUSIONS.contains(conclusion)) return Checks.PASSED;
        if ("failure".equals(conclusion)) return Checks.FAILED;
        // cancelled, timed_out, action_required, stale and anything unknown.
        return Checks.NOT_CODE;
    }

    /** NOT_CODE > FAILED > PENDING > PASSED: the enum is declared in that ascending order. */
    static Checks worse(Checks current, Checks entry) {
        return entry.ordinal() > current.ordinal() ? entry : current;
    }

    private static boolean keep(Checks entry, List<FailedCheck> failed) {
        return (entry == Checks.FAILED || entry == Checks.NOT_CODE) && failed.size() < MAX_FAILED;
    }

    private static FailedCheck failedRun(JsonNode run, Checks entry, Function<JsonNode, List<String>> annotations) {
        JsonNode output = run.path("output");
        List<String> kept = new ArrayList<>();
        if (entry == Checks.FAILED) {
            for (String annotation : annotations.apply(run)) {
                if (kept.size() == MAX_ANNOTATIONS) break;
                kept.add(cap(annotation, MAX_ANNOTATION));
            }
        }
        String conclusion = run.path("conclusion").isTextual() ? run.path("conclusion").asText() : run.path("status").asText("");
        return new FailedCheck(run.path("name").asText(""), conclusion, cap(output.path("title").asText(""), MAX_TEXT),
                cap(output.path("summary").asText(""), MAX_TEXT), kept);
    }

    static String cap(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
    }
}
