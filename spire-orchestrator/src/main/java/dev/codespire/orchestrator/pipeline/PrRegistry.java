package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.scm.RepoRef;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-process convenience: worker result events don't carry RepoRef, so
 * sagas look the repo up here. Goes away at the P1+ service split when
 * commands own their full payloads across process boundaries.
 */
@ApplicationScoped
public class PrRegistry {

    private final Map<String, RepoRef> byReviewId = new ConcurrentHashMap<>();

    public void remember(String reviewId, RepoRef repo) {
        byReviewId.put(reviewId, repo);
    }

    public RepoRef repo(String reviewId) {
        return byReviewId.getOrDefault(reviewId, new RepoRef("unknown", "unknown"));
    }
}
