package dev.codespire.orchestrator.factory;

import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FactoryCloneUrlsTest {

    private static final RepoRef REPO = new RepoRef("acme", "app");

    @Test
    void gitHubCloudClonesFromGithubComNotTheApiHost() {
        // The API base is api.github.com; a clone URL there is a 404. This is the one case where
        // the host a provider was verified against is not the host its repositories live on.
        assertEquals("https://github.com/acme/app.git",
                FactoryCloneUrls.cloneUrl(ScmType.GITHUB, "https://api.github.com", REPO));
    }

    @Test
    void gitHubEnterpriseKeepsItsOwnHost() {
        assertEquals("https://ghe.example.com/acme/app.git",
                FactoryCloneUrls.cloneUrl(ScmType.GITHUB, "https://ghe.example.com/api/v3", REPO));
    }

    @Test
    void gitLabUsesTheRegisteredHostIncludingAPort() {
        assertEquals("https://gitlab.example.com:8443/acme/app.git",
                FactoryCloneUrls.cloneUrl(ScmType.GITLAB, "https://gitlab.example.com:8443/api/v4", REPO));
    }

    @Test
    void aNestedGitLabNamespaceIsPreserved() {
        assertEquals("https://gitlab.com/group/subgroup/app.git",
                FactoryCloneUrls.cloneUrl(ScmType.GITLAB, "https://gitlab.com/api/v4",
                        new RepoRef("group/subgroup", "app")));
    }

    @Test
    void bitbucketCloudAlwaysClonesFromBitbucketOrg() {
        assertEquals("https://bitbucket.org/acme/app.git",
                FactoryCloneUrls.cloneUrl(ScmType.BITBUCKET_CLOUD, "https://api.bitbucket.org/2.0", REPO));
    }

    @Test
    void aProviderWithNoUsableBaseUrlIsRefusedRatherThanGuessed() {
        // A clone URL derived from nothing would be a clone URL pointing somewhere the registration
        // was never verified against.
        assertThrows(IllegalArgumentException.class,
                () -> FactoryCloneUrls.cloneUrl(ScmType.GITLAB, " ", REPO));
        assertThrows(IllegalArgumentException.class,
                () -> FactoryCloneUrls.cloneUrl(ScmType.GITLAB, "not a url", REPO));
    }
}
