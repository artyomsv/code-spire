package dev.codespire.scm.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.codespire.contract.port.PullRequestLander.CheckSummary;
import dev.codespire.contract.port.PullRequestLander.Checks;
import dev.codespire.contract.port.PullRequestLander.FailedCheck;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The land check reduction reads the entries themselves, never GitHub's combined state (A4). */
class GitHubCheckReductionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private ArrayNode statuses(String... contextAndState) {
        ArrayNode rows = mapper.createArrayNode();
        for (int index = 0; index < contextAndState.length; index += 2) {
            rows.addObject().put("context", contextAndState[index]).put("state", contextAndState[index + 1])
                    .put("description", "TEST-description");
        }
        return rows;
    }

    private ObjectNode run(String name, String status, String conclusion) {
        ObjectNode run = mapper.createObjectNode().put("id", 900100).put("name", name).put("status", status);
        if (conclusion == null) run.putNull("conclusion"); else run.put("conclusion", conclusion);
        run.putObject("output").put("title", "TEST-title " + name).put("summary", "TEST-summary " + name);
        return run;
    }

    private ObjectNode completed(String name, String conclusion) {
        return run(name, "completed", conclusion);
    }

    @Test
    void noChecksAndNoStatusesIsPassed() throws Exception {
        // The combined state is "pending" when a commit has no statuses at all; it must not be read.
        JsonNode combined = mapper.readTree("{\"state\":\"pending\",\"total_count\":0,\"statuses\":[]}");
        CheckSummary summary = GitHubCheckReduction.reduce(combined.path("statuses"), List.of());
        assertEquals(Checks.PASSED, summary.state());
        assertEquals(0, summary.done());
        assertEquals(0, summary.total());
        assertTrue(summary.failed().isEmpty());
    }

    @Test
    void aFailedCheckRunIsFailedWithItsSummary() {
        CheckSummary summary = GitHubCheckReduction.reduce(statuses(), List.of(completed("TEST-build", "failure")));
        assertEquals(Checks.FAILED, summary.state());
        assertEquals(1, summary.failed().size());
        FailedCheck failed = summary.failed().getFirst();
        assertEquals("TEST-build", failed.name());
        assertEquals("failure", failed.conclusion());
        assertEquals("TEST-title TEST-build", failed.title());
        assertEquals("TEST-summary TEST-build", failed.summary());
    }

    @Test
    void aPendingStatusIsPending() {
        CheckSummary summary = GitHubCheckReduction.reduce(statuses("TEST-ci", "pending"),
                List.of(completed("TEST-build", "success")));
        assertEquals(Checks.PENDING, summary.state());
        assertEquals(1, summary.done());
        assertEquals(2, summary.total());
    }

    @Test
    void aQueuedCheckRunIsPending() {
        CheckSummary summary = GitHubCheckReduction.reduce(statuses("TEST-ci", "success"),
                List.of(run("TEST-build", "queued", null), run("TEST-lint", "in_progress", null)));
        assertEquals(Checks.PENDING, summary.state());
        assertEquals(1, summary.done());
        assertEquals(3, summary.total());
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancelled", "timed_out", "action_required", "stale"})
    void cancelledTimedOutActionRequiredAndStaleAreNotCode(String conclusion) {
        CheckSummary summary = GitHubCheckReduction.reduce(statuses(),
                List.of(completed("TEST-build", "success"), completed("TEST-" + conclusion, conclusion)));
        assertEquals(Checks.NOT_CODE, summary.state());
        assertEquals(List.of("TEST-" + conclusion), summary.failed().stream().map(FailedCheck::name).toList());
    }

    @Test
    void anErrorStatusIsNotCode() {
        CheckSummary summary = GitHubCheckReduction.reduce(statuses("TEST-ci", "error"), List.of());
        assertEquals(Checks.NOT_CODE, summary.state());
        assertEquals("TEST-ci", summary.failed().getFirst().name());
    }

    @Test
    void aFailedStatusIsFailed() {
        CheckSummary summary = GitHubCheckReduction.reduce(statuses("TEST-ci", "failure"),
                List.of(completed("TEST-build", "success")));
        assertEquals(Checks.FAILED, summary.state());
        assertEquals("TEST-ci", summary.failed().getFirst().name());
    }

    @Test
    void neutralAndSkippedPass() {
        CheckSummary summary = GitHubCheckReduction.reduce(statuses("TEST-ci", "success"),
                List.of(completed("TEST-a", "success"), completed("TEST-b", "neutral"), completed("TEST-c", "skipped")));
        assertEquals(Checks.PASSED, summary.state());
        assertEquals(4, summary.done());
        assertEquals(4, summary.total());
    }

    @Test
    void anUnknownConclusionNeverPasses() {
        CheckSummary summary = GitHubCheckReduction.reduce(statuses(), List.of(completed("TEST-build", "TEST-new")));
        assertEquals(Checks.NOT_CODE, summary.state());
    }

    @Test
    void anUnknownStatusStateNeverPasses() {
        CheckSummary summary = GitHubCheckReduction.reduce(statuses("TEST-ci", "TEST-new"), List.of());
        assertEquals(Checks.NOT_CODE, summary.state());
    }

    @Test
    void notCodeOutranksFailedOutranksPending() {
        assertEquals(Checks.NOT_CODE, GitHubCheckReduction.reduce(statuses("TEST-ci", "pending"),
                List.of(completed("TEST-a", "failure"), completed("TEST-b", "cancelled"))).state());
        assertEquals(Checks.NOT_CODE, GitHubCheckReduction.reduce(statuses("TEST-ci", "failure"),
                List.of(completed("TEST-b", "cancelled"))).state());
        assertEquals(Checks.FAILED, GitHubCheckReduction.reduce(statuses("TEST-ci", "pending"),
                List.of(completed("TEST-a", "failure"))).state());
        assertEquals(Checks.FAILED, GitHubCheckReduction.reduce(statuses("TEST-ci", "failure"),
                List.of(run("TEST-a", "queued", null))).state());
        assertEquals(Checks.PENDING, GitHubCheckReduction.reduce(statuses("TEST-ci", "success"),
                List.of(run("TEST-a", "queued", null))).state());
    }

    @Test
    void theSummaryIsCapped() {
        String longText = "x".repeat(5000);
        List<JsonNode> runs = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            ObjectNode run = completed("TEST-check-" + index, "failure");
            ((ObjectNode) run.path("output")).put("summary", longText);
            runs.add(run);
        }
        List<String> annotations = new ArrayList<>();
        for (int index = 0; index < 8; index++) annotations.add("y".repeat(1000));
        CheckSummary summary = GitHubCheckReduction.reduce(statuses(), runs, failed -> annotations);
        assertEquals(Checks.FAILED, summary.state());
        assertEquals(20, summary.failed().size());
        assertEquals(30, summary.total());
        for (FailedCheck failed : summary.failed()) {
            assertTrue(failed.summary().length() <= 2000, "summary length " + failed.summary().length());
            assertTrue(failed.annotations().size() <= 5, "annotations " + failed.annotations().size());
            for (String annotation : failed.annotations()) assertTrue(annotation.length() <= 300);
        }
    }

    @Test
    void annotationsAreAskedForFailedRunsOnly() {
        List<String> asked = new ArrayList<>();
        GitHubCheckReduction.reduce(statuses("TEST-ci", "failure"),
                List.of(completed("TEST-pass", "success"), completed("TEST-fail", "failure"),
                        completed("TEST-gone", "cancelled")),
                failed -> {
                    asked.add(failed.path("name").asText());
                    return List.of();
                });
        assertEquals(List.of("TEST-fail"), asked);
    }
}
