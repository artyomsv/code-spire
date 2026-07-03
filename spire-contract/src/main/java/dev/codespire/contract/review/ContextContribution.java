package dev.codespire.contract.review;

import java.util.List;

public record ContextContribution(String source, ContribStatus status, List<ContextItem> items, long latencyMs) {
}
