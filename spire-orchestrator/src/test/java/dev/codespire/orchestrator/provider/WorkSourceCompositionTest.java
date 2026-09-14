package dev.codespire.orchestrator.provider;
import dev.codespire.worksource.WorkSourceType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WorkSourceCompositionTest {
    final ProviderClients clients=new ProviderClients();
    @Test void gitlabCannotUseAnotherAccountPlatform(){assertFalse(ProviderClients.supportsWorkAccount(WorkSourceType.GITLAB,"github","bearer"));}
    @Test void gitlabCannotUseBasicAuthentication(){assertFalse(ProviderClients.supportsWorkAccount(WorkSourceType.GITLAB,"gitlab","basic"));}
    @Test void jiraCannotUseAnScmCredential(){assertFalse(ProviderClients.supportsWorkAccount(WorkSourceType.JIRA,"gitlab","bearer"));}
    @Test void jiraCannotUseUnknownAuthentication(){assertFalse(ProviderClients.supportsWorkAccount(WorkSourceType.JIRA,"atlassian","TEST-unknown"));}
    @Test void jiraSupportsBasicAndBearer(){assertTrue(ProviderClients.supportsWorkAccount(WorkSourceType.JIRA,"atlassian","basic"));assertTrue(ProviderClients.supportsWorkAccount(WorkSourceType.JIRA,"atlassian","bearer"));}
    @Test void gitlabScopeCannotBorrowAnotherScm(){assertFalse(clients.workScopeMatchesRepository(WorkSourceType.GITLAB,"https://test-forge.invalid","TEST-group/TEST-repo","github","https://test-forge.invalid","TEST-group/TEST-repo"));}
    @Test void gitlabScopeCannotBorrowAnotherOrigin(){assertFalse(clients.workScopeMatchesRepository(WorkSourceType.GITLAB,"https://test-forge.invalid","TEST-group/TEST-repo","gitlab","https://TEST-other.invalid","TEST-group/TEST-repo"));}
    @Test void gitlabScopeCannotBorrowAnotherProject(){assertFalse(clients.workScopeMatchesRepository(WorkSourceType.GITLAB,"https://test-forge.invalid","TEST-group/TEST-other","gitlab","https://test-forge.invalid","TEST-group/TEST-repo"));}
    @Test void jiraProjectMayTargetAnotherForge(){assertTrue(clients.workScopeMatchesRepository(WorkSourceType.JIRA,"https://TEST-jira.invalid","TEST","gitlab","https://TEST-gitlab.invalid","TEST-group/TEST-repo"));}
    @Test void jiraScopeMustBeAProjectKey(){assertFalse(clients.workScopeMatchesRepository(WorkSourceType.JIRA,"https://TEST-jira.invalid","TEST/project","gitlab","https://TEST-gitlab.invalid","TEST-group/TEST-repo"));}
}
