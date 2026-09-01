package dev.codespire.e2e.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.e2e.gitlab.GitLabDriver.FileAction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GitLabDriverTest {

    private static final String PROBE_PREFIX = "e2e-driver-probe";

    private static final String PROBE_USER = "e2e-driver-probe-user";

    private static final String PROBE_TOKEN = "TEST-e2e-probe-token-00000000000";

    private static GitLabDriver admin;

    /** One Rails call for the whole class: each one boots a Rails environment and is not cheap. */
    @BeforeAll
    static void bootstrap() {
        GitLabDriver.bootstrap(
                List.of(GitLabDriver.ADMIN_USERNAME),
                List.of(PROBE_USER),
                Map.of(GitLabDriver.ADMIN_USERNAME, GitLabDriver.ADMIN_TOKEN,
                        PROBE_USER, PROBE_TOKEN));
        admin = GitLabDriver.asAdmin();
    }

    @Test
    void mintsATokenWhoseValueWeChose() {
        assertEquals(PROBE_USER, GitLabDriver.as(PROBE_TOKEN).get("/user").get("username").asText(),
                "the REST API can only return a token it generated, and the harness needs the value "
                        + "BEFORE the call that would create it — which is why this goes through "
                        + "gitlab-rails runner and set_token");
    }

    @Test
    void createsItsOwnAdministrator() {
        assertTrue(admin.get("/user").get("is_admin").asBoolean(),
                "GitLab's own root seeding did not run on a first boot here and left the instance with "
                        + "zero users, so the harness creates its own admin rather than assuming one. "
                        + "Users::CreateService also ignores an admin: key, so this asserts the flag "
                        + "was set afterwards rather than merely requested.");
    }

    @Test
    void allowsLocalWebhookTargets() {
        admin.allowLocalWebhooks();

        assertTrue(admin.get("/application/settings")
                        .get("allow_local_requests_from_web_hooks_and_services").asBoolean(),
                "GitLab blocks private-network webhook targets by default. Without this the gateway "
                        + "never receives a delivery, and the symptom is indistinguishable from a "
                        + "policy decline: nothing arrives, so nothing is logged on our side.");
    }

    @Test
    void commitsAndOpensAMergeRequest() {
        long project = admin.createProject(PROBE_PREFIX + "-mr-" + System.currentTimeMillis());
        try {
            admin.commit(project, "main", null, "Add a starter file",
                    List.of(FileAction.create("README.md", "E2E probe\n")));
            String head = admin.commit(project, "topic", "main", "Add a second file",
                    List.of(FileAction.create("second.txt", "second\n")));
            assertFalse(head.isBlank());

            assertTrue(admin.openMergeRequest(project, "topic", "main", "E2E driver probe") > 0);
        } finally {
            admin.deleteProjectsNamed(PROBE_PREFIX);
        }
    }

    /**
     * A real move in one commit. Expressed as delete-plus-create it would be a 0%-similarity change,
     * and the rename scenario is specifically about a 100%-similarity rename — a different input that
     * would quietly answer a different question.
     */
    @Test
    void movesAFileInOneCommit() {
        long project = admin.createProject(PROBE_PREFIX + "-move-" + System.currentTimeMillis());
        try {
            admin.commit(project, "main", null, "Add",
                    List.of(FileAction.create("old/Name.java", "class Name {}\n")));
            admin.commit(project, "main", null, "Move",
                    List.of(FileAction.move("new/Name.java", "old/Name.java", "class Name {}\n")));

            JsonNode tree = admin.get("/projects/" + project + "/repository/tree?path=new");
            assertEquals("Name.java", tree.get(0).get("name").asText());
        } finally {
            admin.deleteProjectsNamed(PROBE_PREFIX);
        }
    }
}
