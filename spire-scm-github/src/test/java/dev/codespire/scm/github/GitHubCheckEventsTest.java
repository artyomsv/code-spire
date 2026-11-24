package dev.codespire.scm.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.ChecksChanged;
import dev.codespire.contract.port.RawWebhook;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Review waits for CI: check_suite and status deliveries become a wake-up with the repository and head sha only. */
class GitHubCheckEventsTest {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final RepoRef REPO = new RepoRef("TEST-owner", "TEST-repo");
    private final GitHubIngress ingress = new GitHubIngress("TEST-secret", new ObjectMapper(), Set.of("review"));

    @Test
    void aCompletedCheckSuiteWakesItsHead() {
        assertEquals(List.of(new ChecksChanged(REPO, SHA)), translate("check_suite", """
                {"action":"completed","check_suite":{"head_sha":"%s","conclusion":"failure"},
                 "repository":{"full_name":"TEST-owner/TEST-repo"}}""".formatted(SHA)));
    }

    @Test
    void aRequestedOrRerequestedCheckSuiteWakesNothing() {
        assertTrue(translate("check_suite", """
                {"action":"requested","check_suite":{"head_sha":"%s"},
                 "repository":{"full_name":"TEST-owner/TEST-repo"}}""".formatted(SHA)).isEmpty());
    }

    @Test
    void aFinalStatusWakesItsCommit() {
        for (String state : List.of("success", "failure", "error")) {
            assertEquals(List.of(new ChecksChanged(REPO, SHA)), translate("status", """
                    {"sha":"%s","state":"%s","context":"TEST-ci","repository":{"full_name":"TEST-owner/TEST-repo"}}"""
                    .formatted(SHA, state)), state);
        }
    }

    /** A pending status cannot end a wait, so it costs no read. */
    @Test
    void aPendingStatusWakesNothing() {
        assertTrue(translate("status", """
                {"sha":"%s","state":"pending","repository":{"full_name":"TEST-owner/TEST-repo"}}""".formatted(SHA))
                .isEmpty());
    }

    @Test
    void aPayloadWithoutAFullShaIsDropped() {
        assertTrue(translate("status", """
                {"sha":"abc123","state":"success","repository":{"full_name":"TEST-owner/TEST-repo"}}""").isEmpty());
        assertTrue(translate("check_suite", """
                {"action":"completed","check_suite":{},"repository":{"full_name":"TEST-owner/TEST-repo"}}""").isEmpty());
    }

    private List<IntegrationEvent> translate(String event, String body) {
        return ingress.translate(new RawWebhook(Map.of("X-GitHub-Event", event), body.getBytes(StandardCharsets.UTF_8)));
    }
}
