package dev.codespire.orchestrator.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.scm.github.GitHubRepositoryPermissionSource;
import dev.codespire.scm.gitlab.GitLabRepositoryPermissionSource;
import dev.codespire.scm.bitbucket.BitbucketRepositoryPermissionSource;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryPermissionClientsTest {
    private final ProviderClients clients=new ProviderClients();
    RepositoryPermissionClientsTest() { clients.mapper=new ObjectMapper(); }
    ScmProvider provider(String type) {
        return new ScmProvider(UUID.randomUUID(),"TEST-account",type,"https://TEST-forge.invalid","bearer",null,
                "TEST-token","900888",true,List.of(),"TEST-bot",null,ProviderRole.REVIEWER);
    }
    @Test void githubUsesItsEffectivePermissionAdapter() { assertInstanceOf(GitHubRepositoryPermissionSource.class,clients.repositoryPermissionSource(provider("github"))); }
    @Test void gitlabUsesItsEffectivePermissionAdapter() { assertInstanceOf(GitLabRepositoryPermissionSource.class,clients.repositoryPermissionSource(provider("gitlab"))); }
    @Test void bitbucketUsesItsEffectivePermissionAdapter() { assertInstanceOf(BitbucketRepositoryPermissionSource.class,clients.repositoryPermissionSource(provider("bitbucket-cloud"))); }
    @Test void unsupportedForgeCannotBorrowAnAdapter() { assertThrows(IllegalStateException.class,()->clients.repositoryPermissionSource(provider("TEST-unknown"))); }
}
