package dev.codespire.scm.github;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.contract.port.PullRequestLander.CheckSummary;

import java.util.List;
import java.util.function.Function;

/** Reduces a head's commit statuses and check runs to one land verdict. */
final class GitHubCheckReduction {
    private GitHubCheckReduction() {}

    static CheckSummary reduce(JsonNode statuses, List<JsonNode> checkRuns) {
        return reduce(statuses, checkRuns, run -> List.of());
    }

    static CheckSummary reduce(JsonNode statuses, List<JsonNode> checkRuns,
                               Function<JsonNode, List<String>> annotations) {
        throw new UnsupportedOperationException("RED");
    }
}
