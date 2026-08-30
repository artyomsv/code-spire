package dev.codespire.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.e2e.gitlab.GitLabDriver.FileAction;
import dev.codespire.e2e.spire.LlmMock;
import dev.codespire.e2e.support.Await;
import dev.codespire.e2e.support.Fixtures;
import dev.codespire.e2e.support.ReadModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MR 4: a 100%-similarity rename.
 *
 * <p>Its own merge request rather than a step in the chain, because the repository does not agree
 * with itself about what should happen. {@code CLAUDE.md} records a 2026-07-26 pass where a
 * 100%-similarity rename did <em>not</em> churn finding identity, while {@code docs/SMOKE-TEST.md}
 * calls the churn a known limitation and cites a {@code techdebt/} entry that does not exist. Nobody
 * currently knows which is true, which is the strongest possible argument for a test.
 *
 * <p><b>If this goes red, the red is the deliverable.</b> It is a reproduction of a defect nobody has
 * pinned down — not a broken test, and specifically not something to disable: a suppressed assertion
 * restores exactly the state of not knowing that made it worth writing.
 */
class RenameTest {

    private static final String ORIGINAL = "src/main/java/e2e/Defects.java";

    private static final String RENAMED = "src/main/java/e2e/Validation.java";

    private static final String BRANCH = "e2e-rename-topic";

    @Test
    void findingsFollowARenamedFileAndDoNotComeBackAsNew() {
        LlmMock.reset();
        Environment env = Environment.provision("e2e-rename");

        String source = Fixtures.read("fixtures/chain/" + ORIGINAL);
        env.human().commit(env.projectId(), "main", null, "Add a starter file",
                List.of(FileAction.create("README.md", "E2E rename fixture.\n")));
        env.human().commit(env.projectId(), BRANCH, "main", "Introduce the marked defects",
                List.of(FileAction.create(ORIGINAL, source)));

        long mrIid = env.human().openMergeRequest(env.projectId(), BRANCH, "main", "E2E rename");
        String reviewId = env.reviewId(mrIid);

        Await.until("rename: the first review completed", () ->
                "completed".equals(ReadModel.status(reviewId)) ? Optional.of(true) : Optional.empty());

        long findingsBefore = ReadModel.findingsCount(reviewId);
        assertTrue(findingsBefore > 0, "rename: the first review must produce findings to follow");

        // A real move, in ONE commit, with byte-identical content. Delete-plus-create would be a
        // 0%-similarity change and would answer a different question entirely.
        long runsBefore = ReadModel.events(reviewId, "ReviewRequested");
        env.human().commit(env.projectId(), BRANCH, null, "Rename the file",
                List.of(FileAction.move(RENAMED, ORIGINAL, source)));

        Await.until("rename: the review after the rename completed", () -> {
            boolean rerun = ReadModel.events(reviewId, "ReviewRequested") > runsBefore;
            return rerun && "completed".equals(ReadModel.status(reviewId))
                    ? Optional.of(true) : Optional.empty();
        });

        List<String> locations = verdictLocations(env, mrIid);
        List<String> statuses = verdictStatuses(env, mrIid);

        assertTrue(locations.stream().anyMatch(loc -> loc.contains("Validation.java")),
                "the findings must FOLLOW the file to its new path. Locations: " + locations
                        + ", statuses: " + statuses);

        assertTrue(statuses.stream().noneMatch("SUPERSEDED"::equals),
                "SUPERSEDED means the finding's code disappeared. The code moved; it did not "
                        + "disappear. Statuses: " + statuses + ", locations: " + locations);

        assertTrue(ReadModel.findingsCount(reviewId) <= findingsBefore,
                "a rename must not churn finding identity — the same defects must not come back as "
                        + "NEW findings at the new path. Found " + ReadModel.findingsCount(reviewId)
                        + " after the rename, up from " + findingsBefore
                        + ". Statuses: " + statuses + ", locations: " + locations);
    }

    private static List<String> verdictStatuses(Environment env, long mrIid) {
        List<String> statuses = new ArrayList<>();
        for (JsonNode row : reconciliation(env, mrIid)) {
            statuses.add(row.get("status").asText().toUpperCase(Locale.ROOT).replace(' ', '_'));
        }
        return statuses;
    }

    private static List<String> verdictLocations(Environment env, long mrIid) {
        List<String> locations = new ArrayList<>();
        for (JsonNode row : reconciliation(env, mrIid)) {
            locations.add(row.path("loc").asText(""));
        }
        return locations;
    }

    private static JsonNode reconciliation(Environment env, long mrIid) {
        return env.spire().reviewSummary(env.workspace(), env.slug(), mrIid)
                .withArray("reconciliation");
    }
}
