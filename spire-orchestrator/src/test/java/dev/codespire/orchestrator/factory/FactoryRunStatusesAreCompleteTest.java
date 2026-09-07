package dev.codespire.orchestrator.factory;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code STATUSES} must agree with the SCHEMA, which is the only authority on what a row may hold.
 *
 * <p>The set exists so the runs endpoint can refuse an unknown filter value rather than silently
 * returning everything. A status the deployment can actually store but this set omits is
 * unfilterable: an operator asking for it gets a 400 saying it does not exist, about a status their
 * own rows are in.
 *
 * <p><b>An earlier version of this file derived the answer by REFLECTION over the projection's
 * constants, and a review showed it guarded almost nothing.</b> Two faults, both worth recording
 * because the second is the interesting one:
 *
 * <ul>
 *   <li>Its {@code NOT_A_STATUS} allowlist was unreachable — the one entry's VALUE already failed the
 *       shape filter beside it — while the javadoc called that allowlist the thing keeping the
 *       derivation honest.</li>
 *   <li>The shape filter (value must be {@code lower_snake_case}) was a SILENT exclusion. A status
 *       spelled with a digit, a hyphen or a capital would have been dropped from the derived set,
 *       leaving it equal to a {@code STATUSES} that also omitted it: <b>green about a status nobody
 *       can filter for</b>, which is the exact failure the file exists to prevent.</li>
 * </ul>
 *
 * <p>Removing the shape filter made reflection sweep up the class's SQL constants too, which would
 * have needed an allowlist naming every one of them — an allowlist that grows with every query added
 * and that nothing checks for staleness. <b>So the reflection went instead.</b> It was the weaker
 * half all along: it compared Java to Java, and both could agree while a migration added a tenth
 * value to the table. The CHECK constraint below is the authority the projection writes against, so
 * comparing to it catches the reflection's whole failure mode and the one it could not see.
 *
 * <p>The cost is honest and small: a status constant added to Java and used nowhere the schema admits
 * fails at INSERT rather than here. That is a louder failure than this test would have given it.
 */
@QuarkusTest
class FactoryRunStatusesAreCompleteTest {

    /** The CHECK V51 declares over {@code factory_run.status}. */
    private static final String CONSTRAINT = "factory_run_status_closed";

    @Inject
    DataSource dataSource;

    @Test
    void everyStatusTheSchemaAdmitsIsFilterable() {
        String definition = constraintDefinition(CONSTRAINT);
        assertTrue(definition.contains("status"),
                "the constraint was not found, or is not the one about status — so this test measures "
                        + "nothing and would pass whatever STATUSES said: '" + definition + "'");

        Set<String> inSchema = new TreeSet<>();
        Matcher quoted = Pattern.compile("'([^']+)'").matcher(definition);
        while (quoted.find()) {
            inSchema.add(quoted.group(1));
        }

        assertTrue(inSchema.size() >= 9,
                "the parse found almost nothing, so it is reading the wrong thing: " + definition);
        assertEquals(inSchema, new TreeSet<>(FactoryRunProjection.STATUSES),
                "a status the schema admits but STATUSES omits is unfilterable — an operator asking "
                        + "for it is told it does not exist, about rows they can see. One STATUSES "
                        + "names but the schema refuses is a filter that can only ever answer empty");
    }

    /**
     * The constraint is found by NAME, and a missing one is a failure rather than an empty comparison.
     *
     * <p>Without this, renaming or dropping the CHECK would leave the test above comparing an empty
     * set to an empty set — passing, loudly, about nothing. The {@code contains("status")} assertion
     * there is the same guard stated at the point it matters; this one names the cause.
     */
    @Test
    void theConstraintThisTestReliesOnActuallyExists() {
        assertTrue(constraintDefinition(CONSTRAINT).startsWith("CHECK"),
                "no CHECK named " + CONSTRAINT + "; if it was renamed, rename it here too rather than "
                        + "leaving a test that compares nothing to nothing");
        assertTrue(constraintDefinition("factory_run_no_such_constraint").isEmpty(),
                "and the lookup really does answer empty for a name that is not there, or the "
                        + "assertion above would pass for any name at all");
    }

    private String constraintDefinition(String name) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the constraint " + name, e);
        }
    }
}
