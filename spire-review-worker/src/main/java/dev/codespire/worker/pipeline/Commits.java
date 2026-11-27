package dev.codespire.worker.pipeline;

import java.util.regex.Pattern;

/** Commit-identifier comparison across short (webhook, 12-char) and full (REST) hashes. */
final class Commits {

    /** A full SHA-1 or SHA-256 commit id, as a forge's REST API reports a head. */
    private static final Pattern FULL_SHA = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");
    /** The same in either case: a head that names some commit, though not in a forge's canonical form. */
    private static final Pattern ANY_CASE_FULL_SHA = Pattern.compile(FULL_SHA.pattern(), Pattern.CASE_INSENSITIVE);

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

    /**
     * True only when {@code head} is a full commit id equal to {@code commit}. Fail closed, for a write that must never
     * land on a newer head: unlike {@link #matches}, a missing, blank or short head is no match.
     */
    static boolean isSameFullSha(String head, String commit) {
        return head != null && FULL_SHA.matcher(head).matches() && head.equals(commit);
    }

    /** True when {@code head} is a full commit id in either case; a missing, blank or short head is not one. */
    static boolean isFullSha(String head) {
        return head != null && ANY_CASE_FULL_SHA.matcher(head).matches();
    }
}
