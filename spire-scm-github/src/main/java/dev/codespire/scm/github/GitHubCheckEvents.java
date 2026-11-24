package dev.codespire.scm.github;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.ChecksChanged;
import dev.codespire.contract.scm.RepoRef;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The two GitHub webhooks that say a commit's checks changed (review waits for CI,
 * docs/superpowers/specs/2026-11-24-review-waits-for-ci-design.md): {@code check_suite} with action
 * {@code completed}, and {@code status} in a final state. Each becomes a {@link ChecksChanged} with the repository and
 * the head commit only; nothing else in the payload is trusted, because the event only wakes a waiting review and the
 * orchestrator's own read of the commit decides.
 *
 * <p>A {@code pending} status and any other check-suite action wake nothing: they cannot end a wait. A payload without
 * a full commit sha is dropped rather than published.
 */
final class GitHubCheckEvents {

    static final String CHECK_SUITE = "check_suite";
    static final String STATUS = "status";
    private static final String COMPLETED = "completed";
    private static final String PENDING = "pending";
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");

    private GitHubCheckEvents() {
    }

    /** The wake-up {@code event} carries, or none. {@code repo} is already read and checked by the ingress. */
    static List<IntegrationEvent> translate(String event, JsonNode payload, RepoRef repo) {
        String head = switch (event) {
            case CHECK_SUITE -> COMPLETED.equals(payload.path("action").asText(""))
                    ? payload.path("check_suite").path("head_sha").asText("") : "";
            case STATUS -> PENDING.equals(payload.path("state").asText(PENDING)) ? "" : payload.path("sha").asText("");
            default -> "";
        };
        return SHA.matcher(head).matches() ? List.of(new ChecksChanged(repo, head)) : List.of();
    }
}
