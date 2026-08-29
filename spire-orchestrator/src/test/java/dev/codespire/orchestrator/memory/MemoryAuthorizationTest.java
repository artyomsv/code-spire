package dev.codespire.orchestrator.memory;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

/**
 * Who may change what the reviewer says (P4 / FR-10).
 *
 * <p>Approving a preference changes what every future review posts, which is the "is it
 * configuration" limb of ADR-022's three rules — so the whole surface is admin-only, reads included.
 * This project has already paid for the read half of that lesson once: per-token rates were readable
 * by a viewer in the cost-ledger work, because the payload carried no secret. A listing of what the
 * reviewer has been taught to stay quiet about is the same shape.
 */
@QuarkusTest
class MemoryAuthorizationTest {

    @Inject
    DataSource dataSource;

    @BeforeEach
    void clean() {
        exec("DELETE FROM learned_preference WHERE scope_value LIKE 'TEST-%'");
    }

    @Test
    @TestSecurity(user = "TEST-VIEWER", roles = "spire-viewer")
    void aViewerCannotReadWhatTheReviewerHasBeenTaughtToHide() {
        given().when().get("/api/memory/preferences").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "TEST-VIEWER", roles = "spire-viewer")
    void aViewerCannotApproveRejectRevokeOrRescan() {
        given().when().post("/api/memory/preferences/1/approve").then().statusCode(403);
        given().when().post("/api/memory/preferences/1/reject").then().statusCode(403);
        given().when().post("/api/memory/preferences/1/revoke").then().statusCode(403);
        given().when().post("/api/memory/preferences/rescan").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "TEST-ADMIN", roles = {"spire-viewer", "spire-admin"})
    void anAdminSeesTheThresholdsTheProposalsWereJudgedAgainst() {
        given().when().get("/api/memory/preferences")
                .then().statusCode(200)
                // Read through accessors, not fields: this bean is a CDI client proxy, and a proxy
                // delegates methods but not field access — a direct field read returned 0 and the
                // screen showed a bar of zero while the job enforced ten.
                .body("thresholds.minEvidence", is(10))
                .body("thresholds.minDismissedPercent", is(75));
    }

    @Test
    @TestSecurity(user = "TEST-ADMIN", roles = {"spire-viewer", "spire-admin"})
    void decidingSomethingThatDoesNotExistIsNotFound() {
        given().when().post("/api/memory/preferences/999999/approve").then().statusCode(404);
        given().when().post("/api/memory/preferences/999999/revoke").then().statusCode(404);
    }

    /** Rescan spends no money and changes no review, so an admin may safely run it on demand. */
    @Test
    @TestSecurity(user = "TEST-ADMIN", roles = {"spire-viewer", "spire-admin"})
    void anAdminCanRescanOnDemand() {
        given().when().post("/api/memory/preferences/rescan")
                .then().statusCode(200).body("proposed", is(0));
    }

    private void exec(String sql) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("setup failed: " + sql, e);
        }
    }
}
