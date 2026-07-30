package dev.codespire.contract.review;

/** kind: JIRA_TICKET | CONFLUENCE_PAGE | ISSUE | PULL_REQUEST | EPIC | RULE | CODE_SNIPPET | MEMORY_NOTE. */
public record ContextItem(String kind, String title, String body, String uri) {
}
