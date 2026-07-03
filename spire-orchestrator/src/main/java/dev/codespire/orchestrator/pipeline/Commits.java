package dev.codespire.orchestrator.pipeline;

/** Commit-identifier comparison across short (webhook, 12-char) and full (REST) hashes. */
final class Commits {

    private Commits() {
    }

    /**
     * True when the two identifiers denote the same commit, tolerating
     * prefix-length differences (SCM-MAPPING: Bitbucket webhooks deliver
     * 12-char hashes, REST returns full ones). A null/blank head means the
     * adapter cannot report one (stub) — treated as matching.
     */
    static boolean matches(String head, String commit) {
        if (head == null || head.isBlank() || commit == null || commit.isBlank()) {
            return true;
        }
        return head.startsWith(commit) || commit.startsWith(head);
    }
}
