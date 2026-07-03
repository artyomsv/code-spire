package dev.codespire.contract.port;

import dev.codespire.contract.event.IntegrationEvent;

import java.util.List;

/**
 * Boundary adapter: verify + translate an inbound webhook into integration
 * events (CONTRACT §7). Signature scheme is per-provider (SCM-MAPPING §7):
 * HMAC for Bitbucket/GitHub, constant-time static-token compare for GitLab.
 * Implementations MUST drop events authored by the bot's own identity
 * (self-comment loop, ADR-013).
 */
public interface ScmIngress {

    ScmType type();

    boolean verifySignature(RawWebhook raw);

    /** -> PullRequestEventReceived / PullRequestClosed / ManualCommandReceived / AuthorReplied / PushReceived. */
    List<IntegrationEvent> translate(RawWebhook raw);
}
