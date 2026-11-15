package dev.codespire.orchestrator.security;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The session probe writes the operator directory through the real bean and the real table, and the
 * admin picker reads the row back. The claim handling is in {@link AuthResourceRecordsOperatorTest};
 * {@code @TestSecurity} yields no JWT, so the subject and the login are both the principal name here.
 */
@QuarkusTest
class AuthResourceOperatorSeenTest {

    private static final String OPERATOR = "TEST-SUBJECT-SEEN-OPERATOR";

    @Inject
    DataSource dataSource;

    @BeforeEach
    void clean() {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM operator_seen WHERE oidc_subject LIKE 'TEST-SUBJECT-SEEN-%'")) {
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not clean operator_seen", e);
        }
    }

    @Test
    @TestSecurity(user = OPERATOR, roles = "spire-viewer")
    void aSignedInSessionProbeRecordsTheOperator() {
        given().when().get("/api/me").then().statusCode(200).body("subject", is(OPERATOR));

        assertEquals(List.of(List.of(OPERATOR, OPERATOR, OPERATOR)), seenRows());
    }

    @Test
    @TestSecurity(user = "TEST-SUBJECT-SEEN-ADMIN", roles = {"spire-viewer", "spire-admin"})
    void theAdminPickerListsAnOperatorOnceTheyHaveSignedIn() {
        given().when().get("/api/me").then().statusCode(200);

        given().when().get("/api/operator-identities/operators")
                .then().statusCode(200)
                .body("find { it.subject == 'TEST-SUBJECT-SEEN-ADMIN' }.username", is("TEST-SUBJECT-SEEN-ADMIN"));
    }

    private List<List<String>> seenRows() {
        List<List<String>> rows = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT oidc_subject, username, display_name FROM operator_seen WHERE oidc_subject LIKE 'TEST-SUBJECT-SEEN-%'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(List.of(rs.getString(1), rs.getString(2), rs.getString(3)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read operator_seen", e);
        }
        return rows;
    }
}
