package dev.codespire.scm.gitlab;

import dev.codespire.contract.scm.RepoRef;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Shared parsing of a GitLab project path ({@code group/subgroup.../project}) into a
 * {@link RepoRef} ({@code workspace} = top group, {@code slug} = the rest of the
 * namespace + project, which can be nested). Every segment is validated so a
 * path-traversal shape ({@code ..}) or an empty segment is rejected. Used by both the
 * MR-URL parser and the webhook ingress so the two agree on what a valid project path
 * is (and the traversal guard lives in one place).
 */
final class GitLabProjectPath {

    private static final String SEG = "[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?";
    private static final Pattern SEGMENT = Pattern.compile(SEG);

    private GitLabProjectPath() {
    }

    /** {@code group/subgroup/project} -> RepoRef("group", "subgroup/project"); empty if malformed. */
    static Optional<RepoRef> parse(String pathWithNamespace) {
        if (pathWithNamespace == null) {
            return Optional.empty();
        }
        int slash = pathWithNamespace.indexOf('/');
        if (slash <= 0 || slash == pathWithNamespace.length() - 1) {
            return Optional.empty(); // must have a group AND a project
        }
        String workspace = pathWithNamespace.substring(0, slash);
        String slug = pathWithNamespace.substring(slash + 1);
        if (!SEGMENT.matcher(workspace).matches() || !validSlug(slug)) {
            return Optional.empty();
        }
        return Optional.of(new RepoRef(workspace, slug));
    }

    /** A slug is one or more path segments (nested groups); each must be a valid segment. */
    private static boolean validSlug(String slug) {
        for (String segment : slug.split("/", -1)) {
            if (!SEGMENT.matcher(segment).matches()) {
                return false;
            }
        }
        return true;
    }
}
