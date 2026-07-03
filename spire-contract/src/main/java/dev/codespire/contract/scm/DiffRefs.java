package dev.codespire.contract.scm;

/**
 * The diff anchor SHAs (SCM-MAPPING.md §2). GitLab requires all three to post
 * an inline comment; GitHub uses {@code headSha} as {@code commit_id};
 * Bitbucket needs none. Adapters populate what their provider gives.
 */
public record DiffRefs(String baseSha, String startSha, String headSha) {

    public static DiffRefs headOnly(String headSha) {
        return new DiffRefs(null, null, headSha);
    }
}
