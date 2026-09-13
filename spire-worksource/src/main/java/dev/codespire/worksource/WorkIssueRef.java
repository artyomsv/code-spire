package dev.codespire.worksource;

/** Canonical stable tracker identity. A mutable issue key or display name is not an identity. */
public record WorkIssueRef(WorkSourceType type, String origin, String projectId, String issueId) {}
