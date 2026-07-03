package dev.codespire.contract.llm;

import dev.codespire.contract.review.ModelUsage;

public record Completion(String text, ModelUsage usage) {
}
