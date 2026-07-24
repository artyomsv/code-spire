package dev.codespire.orchestrator.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.port.ThreadSource;
import dev.codespire.scm.bitbucket.BitbucketCloudCommentSink;
import dev.codespire.scm.github.GitHubCommentSink;
import dev.codespire.scm.gitlab.GitLabCommentSink;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Guards the read-side thread-refetch wiring: all three SCM comment sinks implement
 * {@link ThreadSource}, so the review-detail "load full conversation" endpoint must build one for
 * every provider type — not only GitHub. Regression guard for the truncated-Bitbucket/GitLab
 * conversation bug, where the adapters gained ThreadSource but this call-site still returned
 * GitHub-only.
 */
class ProviderClientsTest {

    private final ProviderClients clients = newClients();

    private static ProviderClients newClients() {
        ProviderClients c = new ProviderClients();
        c.mapper = new ObjectMapper();
        return c;
    }

    private static ScmProvider provider(String type, String baseUrl) {
        return new ScmProvider(UUID.randomUUID(), "code-spire-bot", type, baseUrl, "ws", "bearer",
                null, "secret", "123", true, List.of(), "code-spire-bot", "INTERACTIVE");
    }

    @Test
    void threadSourceWiredForAllThreeScms() {
        assertInstanceOf(GitHubCommentSink.class,
                clients.threadSource(provider("github", "https://api.github.com")));
        assertInstanceOf(BitbucketCloudCommentSink.class,
                clients.threadSource(provider("bitbucket-cloud", "https://api.bitbucket.org/2.0")));
        assertInstanceOf(GitLabCommentSink.class,
                clients.threadSource(provider("gitlab", "https://gitlab.com")));
    }

    @Test
    void threadSourceRejectsUnknownType() {
        assertThrows(UnsupportedOperationException.class,
                () -> clients.threadSource(provider("mercurial", "https://example.com")));
    }
}
