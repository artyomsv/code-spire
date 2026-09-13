package dev.codespire.orchestrator.work;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@io.quarkus.test.security.TestSecurity(user="TEST-operator",roles="spire-admin")
class WorkItemProjectionTest extends WorkFixture {
    @Test void containsNoTrackerContentColumns() throws Exception {
        intake.accept(signed("900123"));
        Set<String> columns=new HashSet<>();
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement(
                "SELECT column_name FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='work_item'");ResultSet rs=ps.executeQuery()) {
            while(rs.next())columns.add(rs.getString(1));
        }
        assertTrue(columns.containsAll(Set.of("workflow_status","profile_version","issue_id","generation")));
        assertTrue(Collections.disjoint(columns,Set.of("title","body","status","tracker_status","ticket","payload")));
        assertEquals(1,count("SELECT count(*) FROM work_item WHERE id=?",itemId));
    }
}
