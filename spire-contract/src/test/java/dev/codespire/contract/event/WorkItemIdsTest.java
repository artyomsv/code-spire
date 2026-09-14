package dev.codespire.contract.event;

import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.worksource.WorkIssueRef;
import dev.codespire.worksource.WorkSourceType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkItemIdsTest {
    private static final RepoRef REPO = new RepoRef("TEST-owner", "TEST-repo");
    private static final WorkIssueRef ISSUE = new WorkIssueRef(WorkSourceType.GITHUB, "https://tracker.example.test", "TEST-project", "TEST-issue");
    private String id(ScmType scm, String forge, RepoRef repo, WorkIssueRef issue) { return WorkItemIds.of(scm, forge, repo, issue); }
    private String baseline() { return id(ScmType.GITHUB,"https://forge.example.test",REPO,ISSUE); }
    @Test void scmTypeSeparatesIdentities() { assertNotEquals(baseline(),id(ScmType.GITLAB,"https://forge.example.test",REPO,ISSUE)); }
    @Test void forgeOriginSeparatesIdentities() { assertNotEquals(baseline(),id(ScmType.GITHUB,"https://other.example.test",REPO,ISSUE)); }
    @Test void workspaceSeparatesIdentities() { assertNotEquals(baseline(),id(ScmType.GITHUB,"https://forge.example.test",new RepoRef("TEST-other",REPO.slug()),ISSUE)); }
    @Test void slugSeparatesIdentities() { assertNotEquals(baseline(),id(ScmType.GITHUB,"https://forge.example.test",new RepoRef(REPO.workspace(),"TEST-other"),ISSUE)); }
    @Test void sourceTypeSeparatesIdentities() { assertNotEquals(baseline(),id(ScmType.GITHUB,"https://forge.example.test",REPO,new WorkIssueRef(WorkSourceType.JIRA,ISSUE.origin(),ISSUE.projectId(),ISSUE.issueId()))); }
    @Test void trackerOriginSeparatesIdentities() { assertNotEquals(baseline(),id(ScmType.GITHUB,"https://forge.example.test",REPO,new WorkIssueRef(ISSUE.type(),"https://other.example.test",ISSUE.projectId(),ISSUE.issueId()))); }
    @Test void projectSeparatesIdentities() { assertNotEquals(baseline(),id(ScmType.GITHUB,"https://forge.example.test",REPO,new WorkIssueRef(ISSUE.type(),ISSUE.origin(),"TEST-other",ISSUE.issueId()))); }
    @Test void stableIssueSeparatesIdentities() { assertNotEquals(baseline(),id(ScmType.GITHUB,"https://forge.example.test",REPO,new WorkIssueRef(ISSUE.type(),ISSUE.origin(),ISSUE.projectId(),"TEST-other"))); }
    @Test void adjacentCoordinatesCannotCollideByConcatenation() {
        assertNotEquals(id(ScmType.GITHUB,"https://forge.example.test",REPO,new WorkIssueRef(ISSUE.type(),ISSUE.origin(),"TEST-a","bc")),
                id(ScmType.GITHUB,"https://forge.example.test",REPO,new WorkIssueRef(ISSUE.type(),ISSUE.origin(),"TEST-ab","c")));
    }
    @Test void canonicalOriginsDoNotCreateDuplicateItems() {
        assertEquals(baseline(),id(ScmType.GITHUB,"https://FORGE.example.test:443/api/v3",REPO,
                new WorkIssueRef(ISSUE.type(),"https://TRACKER.example.test:443/api/v3",ISSUE.projectId(),ISSUE.issueId())));
    }
    @Test void blankIdentityCannotProduceAWorkItem() {
        assertThrows(IllegalArgumentException.class,()->id(ScmType.GITHUB,"https://forge.example.test",REPO,new WorkIssueRef(ISSUE.type(),ISSUE.origin()," ",ISSUE.issueId())));
    }
    @Test void nullIdentityCannotProduceAWorkItem() {
        assertThrows(IllegalArgumentException.class,()->id(ScmType.GITHUB,"https://forge.example.test",REPO,new WorkIssueRef(ISSUE.type(),ISSUE.origin(),null,ISSUE.issueId())));
    }
    @Test void theBoundedSubjectFitsTheExistingRunIdContract() {
        assertTrue(baseline().matches("work-v1-[0-9a-f]{64}"));
        assertEquals(72,baseline().length());
        String run = RunIds.of(ScmType.GITHUB, REPO.workspace(), REPO.slug(), baseline(), 1);
        assertEquals(baseline(), RunIds.parse(run).subject());
    }
}
