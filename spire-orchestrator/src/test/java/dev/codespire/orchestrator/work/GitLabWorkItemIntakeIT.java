package dev.codespire.orchestrator.work;

import dev.codespire.worksource.WorkSourceType;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class GitLabWorkItemIntakeIT extends WorkSourceParityCases {
    @Override WorkSourceType type() { return WorkSourceType.GITLAB; }
}
