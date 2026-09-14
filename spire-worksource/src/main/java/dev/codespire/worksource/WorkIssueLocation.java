package dev.codespire.worksource;

import java.net.URI;

/** Mutable lookup coordinates beside the canonical identity, never part of the work-item key. */
public record WorkIssueLocation(WorkIssueRef ref, String issueKey, URI link) {}
