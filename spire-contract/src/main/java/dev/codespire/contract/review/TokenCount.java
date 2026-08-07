package dev.codespire.contract.review;

/** One token-billing dimension's count for a single LLM call. */
public record TokenCount(TokenType type, int tokens) {
}
