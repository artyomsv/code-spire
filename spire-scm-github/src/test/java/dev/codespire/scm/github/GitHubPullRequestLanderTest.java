package dev.codespire.scm.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import dev.codespire.contract.port.PullRequestLander.AllowedMethods;
import dev.codespire.contract.port.PullRequestLander.BranchDeletion;
import dev.codespire.contract.port.PullRequestLander.Checks;
import dev.codespire.contract.port.PullRequestLander.FailedCheck;
import dev.codespire.contract.port.PullRequestLander.LandState;
import dev.codespire.contract.port.PullRequestLander.MergeMethod;
import dev.codespire.contract.port.PullRequestLander.MergeOutcome;
import dev.codespire.contract.port.PullRequestLander.MergeRequest;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubPullRequestLanderTest {
    private static final String REPO = "/repos/TEST-owner/TEST-repo";
    private static final String PULL = REPO + "/pulls/42";
    private static final String BRANCH = "factory/TEST-item-36";
    private final ObjectMapper mapper = new ObjectMapper();
    private final RepoRef repo = new RepoRef("TEST-owner", "TEST-repo");
    private final String head = "b".repeat(40);
    private final String other = "c".repeat(40);
    private WireMockServer api;
    private GitHubPullRequestLander lander;

    @BeforeEach
    void start() {
        api = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        api.start();
        lander = new GitHubPullRequestLander(new GitHubClient(
                new GitHubConfig(api.baseUrl(), "TEST-token", "TEST-secret"), mapper));
    }

    @AfterEach
    void stop() {
        api.stop();
    }

    private void pull(String body) {
        api.stubFor(get(urlEqualTo(PULL)).willReturn(okJson(body)));
    }

    private String openPull() {
        return "{\"number\":42,\"state\":\"open\",\"merged\":false,\"merge_commit_sha\":\"" + "d".repeat(40) + "\","
                + "\"merged_by\":null,\"mergeable\":null,\"mergeable_state\":\"unknown\","
                + "\"user\":{\"id\":900111},\"head\":{\"sha\":\"" + head + "\",\"ref\":\"" + BRANCH + "\"},"
                + "\"base\":{\"sha\":\"" + other + "\",\"ref\":\"main\"}}";
    }

    private void statuses(String rows) {
        api.stubFor(get(urlEqualTo(REPO + "/commits/" + head + "/status?per_page=100"))
                .willReturn(okJson("{\"state\":\"pending\",\"total_count\":0,\"statuses\":" + rows + "}")));
    }

    private void runs(int page, ArrayNode rows) {
        api.stubFor(get(urlEqualTo(REPO + "/commits/" + head + "/check-runs?per_page=100&page=" + page))
                .willReturn(okJson("{\"total_count\":" + rows.size() + ",\"check_runs\":" + rows + "}")));
    }

    private ArrayNode rows() {
        return mapper.createArrayNode();
    }

    private void addRun(ArrayNode rows, long id, String name, String conclusion) {
        ObjectNode run = rows.addObject().put("id", id).put("name", name).put("status", "completed")
                .put("conclusion", conclusion);
        run.putObject("output").put("title", "TEST-title").put("summary", "TEST-summary");
    }

    @Test
    void readMapsStateMergeableAndMergedBy() {
        pull("{\"number\":42,\"state\":\"closed\",\"merged\":true,\"merge_commit_sha\":\"" + "e".repeat(40) + "\","
                + "\"merged_by\":{\"id\":900222,\"login\":\"TEST-merger\"},\"sender\":{\"id\":900333},"
                + "\"mergeable\":true,\"mergeable_state\":\"clean\",\"user\":{\"id\":900111},"
                + "\"head\":{\"sha\":\"" + head + "\",\"ref\":\"" + BRANCH + "\"},"
                + "\"base\":{\"sha\":\"" + other + "\",\"ref\":\"main\"}}");
        statuses("[]");
        runs(1, rows());
        LandState state = lander.read(repo, 42);
        assertEquals("closed", state.state());
        assertTrue(state.merged());
        assertEquals("e".repeat(40), state.mergeCommitSha());
        assertEquals("900222", state.mergedBy());
        assertEquals(head, state.headSha());
        assertEquals(BRANCH, state.headBranch());
        assertEquals("main", state.baseBranch());
        assertEquals(other, state.baseSha());
        assertEquals(Boolean.TRUE, state.mergeable());
        assertEquals("clean", state.mergeableState());
        assertEquals(Checks.PASSED, state.checks().state());
    }

    @Test
    void anOpenPullRequestHasNoMergerAndAComputingMergeable() {
        pull(openPull());
        statuses("[]");
        runs(1, rows());
        LandState state = lander.read(repo, 42);
        assertFalse(state.merged());
        assertNull(state.mergedBy());
        assertNull(state.mergeCommitSha());
        assertNull(state.mergeable());
        assertEquals("unknown", state.mergeableState());
    }

    @Test
    void annotationsAreReadOnlyForFailedRuns() {
        pull(openPull());
        statuses("[]");
        ArrayNode page = rows();
        addRun(page, 900501, "TEST-pass", "success");
        addRun(page, 900502, "TEST-fail", "failure");
        runs(1, page);
        ArrayNode annotations = rows();
        for (int index = 0; index < 7; index++) {
            annotations.addObject().put("path", "src/TEST-" + index + ".java").put("start_line", index + 1)
                    .put("annotation_level", "failure").put("message", "TEST-message " + index);
        }
        api.stubFor(get(urlPathMatching(REPO + "/check-runs/900502/annotations.*"))
                .willReturn(okJson(annotations.toString())));
        LandState state = lander.read(repo, 42);
        assertEquals(Checks.FAILED, state.checks().state());
        FailedCheck failed = state.checks().failed().getFirst();
        assertEquals("TEST-fail", failed.name());
        assertEquals(5, failed.annotations().size());
        assertTrue(failed.annotations().getFirst().contains("TEST-message 0"));
        api.verify(1, getRequestedFor(urlPathMatching(REPO + "/check-runs/900502/annotations.*")));
        api.verify(0, getRequestedFor(urlPathMatching(REPO + "/check-runs/900501/annotations.*")));
    }

    @Test
    void aFailureOnTheSecondCheckRunPageFails() {
        pull(openPull());
        statuses("[]");
        ArrayNode first = rows();
        for (int index = 0; index < 100; index++) addRun(first, 900600 + index, "TEST-ok-" + index, "success");
        runs(1, first);
        ArrayNode second = rows();
        addRun(second, 900800, "TEST-late", "failure");
        runs(2, second);
        api.stubFor(get(urlPathMatching(REPO + "/check-runs/900800/annotations.*")).willReturn(okJson("[]")));
        LandState state = lander.read(repo, 42);
        assertEquals(Checks.FAILED, state.checks().state());
        assertEquals(101, state.checks().total());
        api.verify(getRequestedFor(urlEqualTo(REPO + "/commits/" + head + "/check-runs?per_page=100&page=1")));
        api.verify(getRequestedFor(urlEqualTo(REPO + "/commits/" + head + "/check-runs?per_page=100&page=2")));
    }

    @Test
    void aFullLastPageAtTheCapIsNotCode() {
        pull(openPull());
        statuses("[]");
        ArrayNode full = rows();
        for (int index = 0; index < 100; index++) addRun(full, 900600 + index, "TEST-ok-" + index, "success");
        api.stubFor(get(urlPathMatching(REPO + "/commits/" + head + "/check-runs"))
                .willReturn(okJson("{\"total_count\":2000,\"check_runs\":" + full + "}")));
        LandState state = lander.read(repo, 42);
        assertEquals(Checks.NOT_CODE, state.checks().state());
        api.verify(10, getRequestedFor(urlPathMatching(REPO + "/commits/" + head + "/check-runs")));
    }

    @Test
    void moreStatusesThanOnePageIsNotCode() {
        pull(openPull());
        ArrayNode page = rows();
        for (int index = 0; index < 100; index++) {
            page.addObject().put("context", "TEST-status-" + index).put("state", "success");
        }
        // The 101st status is the one a single page never shows; it may be the failing one.
        api.stubFor(get(urlEqualTo(REPO + "/commits/" + head + "/status?per_page=100"))
                .willReturn(okJson("{\"state\":\"failure\",\"total_count\":101,\"statuses\":" + page + "}")));
        runs(1, rows());
        LandState state = lander.read(repo, 42);
        assertEquals(Checks.NOT_CODE, state.checks().state());
        assertEquals("history_incomplete", state.checks().failed().getFirst().conclusion());
    }

    @Test
    void aPullRequestNumberMismatchIsRefused() {
        pull(openPull().replace("\"number\":42", "\"number\":43"));
        statuses("[]");
        runs(1, rows());
        assertThrows(IllegalStateException.class, () -> lander.read(repo, 42));
    }

    @Test
    void allowedMethodsReadsTheRepositoryFlags() {
        api.stubFor(get(urlEqualTo(REPO)).willReturn(okJson(
                "{\"full_name\":\"TEST-owner/TEST-repo\",\"allow_merge_commit\":false,"
                        + "\"allow_squash_merge\":true,\"allow_rebase_merge\":false}")));
        AllowedMethods allowed = lander.allowedMethods(repo);
        assertTrue(allowed.checked());
        assertEquals(Set.of(MergeMethod.SQUASH), allowed.allowed());
    }

    @Test
    void unreportedMergeFlagsAreNotChecked() {
        api.stubFor(get(urlEqualTo(REPO)).willReturn(okJson("{\"full_name\":\"TEST-owner/TEST-repo\"}")));
        assertFalse(lander.allowedMethods(repo).checked());
    }

    @Test
    void mergeSendsTheShaAndMethod() {
        api.stubFor(put(urlEqualTo(PULL + "/merge")).willReturn(okJson(
                "{\"sha\":\"" + "f".repeat(40) + "\",\"merged\":true,\"message\":\"TEST merged\"}")));
        MergeOutcome outcome = lander.merge(repo, new MergeRequest(42, head, MergeMethod.SQUASH));
        assertEquals(new MergeOutcome.Merged("f".repeat(40)), outcome);
        api.verify(putRequestedFor(urlEqualTo(PULL + "/merge"))
                .withRequestBody(equalToJson("{\"sha\":\"" + head + "\",\"merge_method\":\"squash\"}")));
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 404, 405, 422})
    void eachHttpStatusIsDefinite(int status) {
        api.stubFor(put(urlEqualTo(PULL + "/merge")).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"message\":\"TEST refusal " + status + "\"}")));
        MergeOutcome outcome = lander.merge(repo, new MergeRequest(42, head, MergeMethod.MERGE));
        MergeOutcome.Refused refused = assertInstanceOf(MergeOutcome.Refused.class, outcome);
        assertEquals(status, refused.status());
        assertEquals("TEST refusal " + status, refused.message());
    }

    @Test
    void a409IsAHeadConflict() {
        api.stubFor(put(urlEqualTo(PULL + "/merge")).willReturn(aResponse().withStatus(409)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"message\":\"TEST head branch was modified\"}")));
        MergeOutcome outcome = lander.merge(repo, new MergeRequest(42, head, MergeMethod.REBASE));
        assertEquals(new MergeOutcome.HeadConflict("TEST head branch was modified"), outcome);
    }

    /** A gateway answer is a lost answer: GitHub can finish the merge after its edge gives up. */
    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504})
    void aServerErrorIsUncertain(int status) {
        api.stubFor(put(urlEqualTo(PULL + "/merge")).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "text/html").withBody("TEST gateway " + status)));
        MergeOutcome.Uncertain uncertain = assertInstanceOf(MergeOutcome.Uncertain.class,
                lander.merge(repo, new MergeRequest(42, head, MergeMethod.MERGE)));
        assertTrue(uncertain.cause().contains(String.valueOf(status)));
    }

    @Test
    void anIoFailureIsUncertain() {
        api.stubFor(put(urlEqualTo(PULL + "/merge"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        assertInstanceOf(MergeOutcome.Uncertain.class, lander.merge(repo, new MergeRequest(42, head, MergeMethod.MERGE)));
        api.stubFor(put(urlEqualTo(PULL + "/merge")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{not json")));
        assertInstanceOf(MergeOutcome.Uncertain.class, lander.merge(repo, new MergeRequest(42, head, MergeMethod.MERGE)));
    }

    private void ref(String sha) {
        api.stubFor(get(urlEqualTo(REPO + "/git/ref/heads/" + BRANCH)).willReturn(okJson(
                "{\"ref\":\"refs/heads/" + BRANCH + "\",\"object\":{\"type\":\"commit\",\"sha\":\"" + sha + "\"}}")));
    }

    private void deleteAnswers(int status) {
        api.stubFor(delete(urlEqualTo(REPO + "/git/refs/heads/" + BRANCH)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(status == 204 ? "" : "{\"message\":\"TEST\"}")));
    }

    @Test
    void deleteOnlyAtTheExpectedSha() {
        ref(other);
        deleteAnswers(204);
        assertEquals(BranchDeletion.MOVED, lander.deleteBranch(repo, BRANCH, head));
        api.verify(0, deleteRequestedFor(urlPathMatching(".*")));
    }

    @Test
    void anAbsentRefIsAbsentWithoutADelete() {
        api.stubFor(get(urlEqualTo(REPO + "/git/ref/heads/" + BRANCH)).willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "application/json").withBody("{\"message\":\"Not Found\"}")));
        assertEquals(BranchDeletion.ABSENT, lander.deleteBranch(repo, BRANCH, head));
        api.verify(0, deleteRequestedFor(urlPathMatching(".*")));
    }

    @Test
    void a204IsDeleted() {
        ref(head);
        deleteAnswers(204);
        assertEquals(BranchDeletion.DELETED, lander.deleteBranch(repo, BRANCH, head));
        api.verify(1, deleteRequestedFor(urlEqualTo(REPO + "/git/refs/heads/" + BRANCH)));
    }

    @Test
    void a404Or422IsReReadBeforeCallingItGone() {
        api.stubFor(get(urlEqualTo(REPO + "/git/ref/heads/" + BRANCH)).inScenario("gone")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(okJson("{\"ref\":\"refs/heads/" + BRANCH + "\",\"object\":{\"sha\":\"" + head + "\"}}"))
                .willSetStateTo("deleted"));
        api.stubFor(get(urlEqualTo(REPO + "/git/ref/heads/" + BRANCH)).inScenario("gone")
                .whenScenarioStateIs("deleted")
                .willReturn(aResponse().withStatus(404).withBody("{\"message\":\"Not Found\"}")));
        deleteAnswers(422);
        assertEquals(BranchDeletion.ABSENT, lander.deleteBranch(repo, BRANCH, head));
        api.verify(2, getRequestedFor(urlEqualTo(REPO + "/git/ref/heads/" + BRANCH)));
    }

    @Test
    void a422WithTheRefStillPresentIsNotDeleted() {
        ref(head);
        deleteAnswers(422);
        assertEquals(BranchDeletion.STILL_PRESENT, lander.deleteBranch(repo, BRANCH, head));
        api.verify(2, getRequestedFor(urlEqualTo(REPO + "/git/ref/heads/" + BRANCH)));
    }

    @Test
    void aLostDeleteResponseIsReRead() {
        ref(head);
        api.stubFor(delete(urlEqualTo(REPO + "/git/refs/heads/" + BRANCH))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        assertEquals(BranchDeletion.STILL_PRESENT, lander.deleteBranch(repo, BRANCH, head));
        api.verify(2, getRequestedFor(urlEqualTo(REPO + "/git/ref/heads/" + BRANCH)));
    }

    @Test
    void readIsNeverAWrite() {
        pull(openPull());
        statuses("[]");
        runs(1, rows());
        lander.read(repo, 42);
        api.verify(0, putRequestedFor(urlPathMatching(".*")));
        api.verify(0, deleteRequestedFor(urlPathMatching(".*")));
        assertEquals(3, api.findAll(anyRequestedFor(urlPathMatching(".*"))).size());
    }
}
