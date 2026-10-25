package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.WorkItemEvent;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.SQLException;

/** Projects a generated item's draft (M4 slice 3). */
@ApplicationScoped
public class WorkDraftProjection {
    void project(Connection c, WorkItemEvent item) throws SQLException {
        // RED
    }
}
