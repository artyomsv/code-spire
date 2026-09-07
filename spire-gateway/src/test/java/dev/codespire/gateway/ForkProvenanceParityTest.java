package dev.codespire.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.PullRequestEventReceived;
import dev.codespire.contract.port.RawWebhook;
import dev.codespire.contract.port.ScmIngress;
import dev.codespire.scm.bitbucket.BitbucketCloudConfig;
import dev.codespire.scm.bitbucket.BitbucketCloudIngress;
import dev.codespire.scm.github.GitHubIngress;
import dev.codespire.scm.gitlab.GitLabIngress;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a pull request came from a fork — one fact, read the same way on every provider.
 *
 * <p><b>Why this exists.</b> ADR-040 lets a fix run push to a pull request's own source branch, and
 * puts fork pull requests out of scope for that mode. Nothing recorded fork provenance, so the
 * orchestrator could not tell — and a fork's source branch NAME would have been pushed against the
 * BASE repository, either creating a stray branch attached to no pull request or, worse, landing a
 * machine-authored commit from a different diff on an unrelated branch of the same name.
 *
 * <p><b>Each provider spells it differently, which is exactly the shape that has diverged here
 * before.</b> GitHub compares two repository full names, GitLab two numeric project ids, Bitbucket
 * two repository full names again. One provider getting it backwards would let forks through on that
 * SCM alone, and a per-provider test would pass while doing it — which is why this is a parity test
 * and asserts BOTH answers on all three.
 */
class ForkProvenanceParityTest {

    private static final String SECRET = "test-webhook-secret";

    private record Case(String provider, List<IntegrationEvent> events) {
    }

    @Test
    void everyProviderReportsAPullRequestOpenedFromAForkAsFromAFork() {
        for (Case c : openedOnEveryProvider(true)) {
            assertTrue(prEvent(c).fromFork(), c.provider() + " must report a fork as a fork");
        }
    }

    @Test
    void everyProviderReportsASameRepositoryPullRequestAsNotFromAFork() {
        for (Case c : openedOnEveryProvider(false)) {
            assertFalse(prEvent(c).fromFork(), c.provider() + " must not call a branch PR a fork");
        }
    }

    /**
     * Guards the guard. A case list that silently lost a provider would keep both assertions above
     * green while covering less — the same shape {@code IngressCommandParityTest} already protects.
     */
    @Test
    void theForkCasesCoverEveryProvider() {
        assertEquals(List.of("bitbucket", "github", "gitlab"),
                openedOnEveryProvider(true).stream().map(Case::provider).sorted().toList());
    }

    private static PullRequestEventReceived prEvent(Case c) {
        assertEquals(1, c.events().size(), "provider " + c.provider());
        return assertInstanceOf(PullRequestEventReceived.class, c.events().getFirst(), c.provider());
    }

    private static List<Case> openedOnEveryProvider(boolean fromFork) {
        return List.of(
                new Case("github", githubIngress().translate(webhook(githubOpened(fromFork),
                        Map.of("X-GitHub-Event", "pull_request")))),
                new Case("gitlab", gitlabIngress().translate(webhook(gitlabOpened(fromFork), Map.of()))),
                new Case("bitbucket", bitbucketIngress().translate(webhook(bitbucketOpened(fromFork),
                        Map.of("X-Event-Key", "pullrequest:created")))));
    }

    /** GitHub: the fork signal is head.repo.full_name against base.repo.full_name. */
    private static byte[] githubOpened(boolean fromFork) {
        String headRepo = fromFork ? "contributor/widgets" : "acme/widgets";
        return """
                {
                  "action": "opened",
                  "repository": { "full_name": "acme/widgets" },
                  "pull_request": {
                    "number": 7, "title": "Add login", "body": "why",
                    "head": { "ref": "feature/login", "sha": "cafe1234", "repo": { "full_name": "%s" } },
                    "base": { "ref": "main", "repo": { "full_name": "acme/widgets" } },
                    "html_url": "https://github.com/acme/widgets/pull/7",
                    "user": { "id": 4242, "login": "octocat" }
                  }
                }
                """.formatted(headRepo).getBytes(StandardCharsets.UTF_8);
    }

    /** GitLab: two numeric project ids, not names. */
    private static byte[] gitlabOpened(boolean fromFork) {
        int sourceProject = fromFork ? 99 : 11;
        return """
                {
                  "object_kind": "merge_request",
                  "project": { "id": 11, "path_with_namespace": "acme/widgets" },
                  "user": { "id": 4242, "username": "octocat", "name": "Octo Cat" },
                  "object_attributes": {
                    "iid": 7, "action": "open", "title": "Add login", "description": "why",
                    "source_branch": "feature/login", "target_branch": "main",
                    "last_commit": { "id": "cafe1234" },
                    "url": "https://gitlab.com/acme/widgets/-/merge_requests/7",
                    "source_project_id": %d, "target_project_id": 11
                  }
                }
                """.formatted(sourceProject).getBytes(StandardCharsets.UTF_8);
    }

    /** Bitbucket Cloud: repository full names again, but nested under source/destination. */
    private static byte[] bitbucketOpened(boolean fromFork) {
        String sourceRepo = fromFork ? "contributor/widgets" : "acme/widgets";
        return """
                {
                  "repository": { "full_name": "acme/widgets" },
                  "pullrequest": {
                    "id": 7, "title": "Add login", "description": "why",
                    "source": { "branch": { "name": "feature/login" }, "commit": { "hash": "cafe1234" },
                                "repository": { "full_name": "%s" } },
                    "destination": { "branch": { "name": "main" },
                                     "repository": { "full_name": "acme/widgets" } },
                    "links": { "html": { "href": "https://bitbucket.org/acme/widgets/pull-requests/7" } },
                    "author": { "account_id": "4242", "nickname": "octocat", "display_name": "Octo Cat" }
                  }
                }
                """.formatted(sourceRepo).getBytes(StandardCharsets.UTF_8);
    }

    private static ScmIngress githubIngress() {
        return new GitHubIngress(SECRET, new ObjectMapper(), WebhookCommands.SUPPORTED);
    }

    private static ScmIngress gitlabIngress() {
        return new GitLabIngress(SECRET, new ObjectMapper(), WebhookCommands.SUPPORTED);
    }

    private static ScmIngress bitbucketIngress() {
        return new BitbucketCloudIngress(
                new BitbucketCloudConfig("https://api.example.invalid/2.0", "test-bot", "test-app-password", SECRET),
                new ObjectMapper(), WebhookCommands.SUPPORTED);
    }

    private static RawWebhook webhook(byte[] body, Map<String, String> headers) {
        return new RawWebhook(headers, body);
    }
}
