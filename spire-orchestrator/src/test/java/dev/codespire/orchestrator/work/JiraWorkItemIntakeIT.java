package dev.codespire.orchestrator.work;

import dev.codespire.worksource.WorkSourceType;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class JiraWorkItemIntakeIT extends WorkSourceParityCases {
    @Override WorkSourceType type() { return WorkSourceType.JIRA; }
}
