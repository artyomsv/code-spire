package dev.codespire.e2e.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.e2e.gitlab.GitLabDriver.FileAction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitLabDriverTest {

    private static final String PROBE_PREFIX = "e2e-driver-probe";

    @Test
    void mintsAKnownTokenForANewUser() {
        GitLabDriver root = GitLabDriver.asRoot();
        root.ensureUser("e2e-driver-probe-user", "e2e-driver-probe-user@example.invalid",
                "TEST-password-12345");
        root.mintToken("e2e-driver-probe-user", "TEST-token-driver-probe-000000000");

        JsonNode me = GitLabDriver.as("TEST-token-driver-probe-000000000").get("/user");
        assertEquals("e2e-driver-probe-user", me.get("username").asText(),
                "the API cannot mint a token whose value we choose, which is why this goes through "
                        + "gitlab-rails runner — the harness needs the token before the call that "
                        + "would create it");
    }

    @Test
    void allowsLocalWebhookTargets() {
        GitLabDriver root = GitLabDriver.asRoot();
        root.allowLocalWebhooks();

        assertTrue(root.get("/application/settings")
                        .get("allow_local_requests_from_web_hooks_and_services").asBoolean(),
                "GitLab blocks private-network webhook targets by default. Without this the gateway "
                        + "never receives a delivery, and the symptom is indistinguishable from a "
                        + "policy decline: nothing arrives, so nothing is logged on our side.");
    }

    @Test
    void commitsAndOpensAMergeRequest() {
        GitLabDriver root = GitLabDriver.asRoot();
        long project = root.createProject(PROBE_PREFIX + "-mr-" + System.currentTimeMillis());
        try {
            root.commit(project, "main", null, "Add a starter file",
                    List.of(FileAction.create("README.md", "E2E probe\n")));
            String head = root.commit(project, "topic", "main", "Add a second file",
                    List.of(FileAction.create("second.txt", "second\n")));
            assertFalse(head.isBlank());

            assertTrue(root.openMergeRequest(project, "topic", "main", "E2E driver probe") > 0);
        } finally {
            root.deleteProjectsNamed(PROBE_PREFIX);
        }
    }

    /**
     * A real move in one commit. Expressed as delete-plus-create it would be a 0%-similarity change,
     * and the rename scenario is specifically about a 100%-similarity rename — a different input that
     * would quietly answer a different question.
     */
    @Test
    void movesAFileInOneCommit() {
        GitLabDriver root = GitLabDriver.asRoot();
        long project = root.createProject(PROBE_PREFIX + "-move-" + System.currentTimeMillis());
        try {
            root.commit(project, "main", null, "Add",
                    List.of(FileAction.create("old/Name.java", "class Name {}\n")));
            root.commit(project, "main", null, "Move",
                    List.of(FileAction.move("new/Name.java", "old/Name.java", "class Name {}\n")));

            JsonNode tree = root.get("/projects/" + project + "/repository/tree?path=new");
            assertEquals("Name.java", tree.get(0).get("name").asText());
        } finally {
            root.deleteProjectsNamed(PROBE_PREFIX);
        }
    }
}
