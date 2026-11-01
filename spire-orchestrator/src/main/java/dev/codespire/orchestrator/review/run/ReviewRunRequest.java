package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.orchestrator.factory.RunFunding;

import java.util.UUID;

/**
 * What a caller asks of {@link ReviewRunDispatch}: the repository, the review call, how it is paid and the prompt. The
 * dispatch reads the rest (the pull request's branch and head, the reviewer account) itself.
 */
public record ReviewRunRequest(UUID repositoryId, ReviewRunBinding binding, RunFunding.Choice choice, String prompt) {
}
