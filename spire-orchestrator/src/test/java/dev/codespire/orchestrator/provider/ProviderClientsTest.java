package dev.codespire.orchestrator.provider;

import dev.codespire.contract.port.ScmType;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.port.ThreadSource;
import dev.codespire.scm.bitbucket.BitbucketCloudCommentSink;
import dev.codespire.scm.github.GitHubCommentSink;
import dev.codespire.scm.gitlab.GitLabCommentSink;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /** The reviewer account, which is what every read-path client here is built from. */
    private static ScmProvider provider(String type, String baseUrl) {
        return provider(type, baseUrl, ProviderRole.REVIEWER);
    }

    private static ScmProvider provider(String type, String baseUrl, ProviderRole role) {
        return new ScmProvider(UUID.randomUUID(), "code-spire-bot", type, baseUrl, "ws", "bearer",
                null, "secret", "123", true, List.of(), "code-spire-bot", "INTERACTIVE", role);
    }

    /**
     * <b>Every forge is wired, and each to its OWN adapter.</b>
     *
     * <p>This method had no test at all. Swapping two case labels in that switch compiles, passes,
     * and opens the run's pull request through the wrong forge's client — against the wrong host,
     * with the wrong request shape. Asserting {@code type()} rather than the concrete class is what
     * {@code type()} was added to the port FOR, and nothing was using it.
     */
    @Test
    void pullRequestSinkWiredForAllThreeScmsAndEachToItsOwn() {
        assertEquals(ScmType.GITHUB, clients.pullRequestSink(
                provider("github", "https://api.github.com", ProviderRole.FACTORY)).type());
        assertEquals(ScmType.BITBUCKET_CLOUD, clients.pullRequestSink(
                provider("bitbucket-cloud", "https://api.bitbucket.org/2.0", ProviderRole.FACTORY)).type());
        assertEquals(ScmType.GITLAB, clients.pullRequestSink(
                provider("gitlab", "https://gitlab.com", ProviderRole.FACTORY)).type());
    }

    /**
     * <b>The REVIEWER account is refused, and this is the guard a review said was impossible.</b>
     *
     * <p>It was, until the same review noticed that {@code ProviderRegistry.resolve} already
     * filters {@code WHERE role = ?} and the mapper simply dropped the column. The row knew all
     * along.
     *
     * <p>What it prevents: a branch pushed as the factory account with a pull request opened as the
     * reviewer belongs to neither, and the reviewer's token is not provisioned for that write — its
     * 403 would read as the FACTORY account failing and send an operator to the wrong account.
     */
    @Test
    void pullRequestSinkRefusesAnyAccountButTheFactorys() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> clients.pullRequestSink(provider("github", "https://api.github.com",
                        ProviderRole.REVIEWER)));

        assertTrue(refused.getMessage().contains("FACTORY"), refused.getMessage());
        assertTrue(refused.getMessage().contains("REVIEWER"),
                "and it names what it WAS handed, or an operator cannot tell which account to fix: "
                        + refused.getMessage());
    }

    /** A fourth forge cannot open a pull request; pretending it can records a delivery with nothing behind it. */
    @Test
    void pullRequestSinkRefusesAnUnknownProviderType() {
        assertThrows(IllegalStateException.class, () -> clients.pullRequestSink(
                provider("bitbucket-dc", "https://bb.example", ProviderRole.FACTORY)));
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
