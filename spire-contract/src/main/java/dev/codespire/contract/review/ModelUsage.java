package dev.codespire.contract.review;

/** LLM usage accounting. Money in MILLICENTS (1/100,000 dollar). */
public record ModelUsage(String model, int tokensIn, int tokensOut, long costMillicents) {
}
