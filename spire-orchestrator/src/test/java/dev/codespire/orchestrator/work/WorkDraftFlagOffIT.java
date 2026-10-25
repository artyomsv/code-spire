package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.orchestrator.factory.BuildDefaults;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * While generated preparation is dark (M4 slice 3, decision 1), admission ignores a stored {@code generated}.
 *
 * <p>Its own class, outside {@link WorkDraftAdmissionIT}: that one runs under {@link GeneratedPreparationProfile}, and
 * the flag cannot be switched off inside a profile. The setting is written straight to the table here, because the
 * build setup refuses to save it while the flag is off.
 */
@QuarkusTest
@TestSecurity(user = "TEST-draft-admin", roles = "spire-admin")
class WorkDraftFlagOffIT extends WorkPreparedFixture {

    @Inject BuildDefaults defaults;

    @Test
    void theFlagOffAdmitsAsComposed() throws Exception {
        defaults.save(repository, new BuildDefaults.Input(0, "main", "codex", model), "TEST-draft-admin");
        executeWith("UPDATE repository_build_defaults SET preparation='generated' WHERE repository_id=?", repository);

        String id = admit("autonomous", 70);

        WorkItemEvent item = store.load(id);
        assertEquals("specification_required", item.reason());
        assertNull(item.draft());
        assertEquals(1, count("SELECT count(*) FROM work_item WHERE id=? AND preparation_mode='composed'", id));
    }
}
