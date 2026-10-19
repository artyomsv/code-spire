package dev.codespire.contract.review;

/** What a review says about a pull request, as a forge-native review states it (ADR-047). */
public enum ReviewVerdict {
    APPROVE,
    REQUEST_CHANGES,
    COMMENT
}
