package dev.codespire.publisher;

import dev.codespire.workspace.GitCredential;
import org.eclipse.jgit.lib.Repository;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Everything the publisher is told, read from the environment once and refused up front.
 *
 * <p>No defaults for anything an operator must decide: a publisher that silently starts with the
 * wrong branch, or with no credential, discovers it in the middle of a run — after the agent's work
 * is already done. Two refusals here are security controls rather than hygiene:
 *
 * <ul>
 *   <li><b>The target branch must live under {@value #BRANCH_NAMESPACE}, differ from the base, and
 *       be a name git accepts.</b> This process holds the only write credential in the run unit,
 *       and the gate judges paths, not refs — so a command naming {@code main} as the branch would
 *       have fast-forwarded the default branch with whatever the agent produced. The namespace is
 *       the floor the orchestrator cannot lower, in the same spirit as
 *       {@code ProtectedPaths.CI_FLOOR}.</li>
 *   <li><b>The remote URI may carry no userinfo.</b> A credential embedded in the URI reaches every
 *       place the URI is printed — a JGit transport exception's message, and from there the outcome
 *       line on stdout that the worker records as the run's failure detail. The credential has its
 *       own two variables.</li>
 * </ul>
 *
 * <p>Every refusal names the variable and never its value, because the value may be the secret.
 */
record PublisherConfig(String remoteUri, String baseBranch, String baseCommit, String branch,
                       List<String> protectedPaths, long bundleMaxBytes, GitCredential credential,
                       Path handoffDir) {

    /** Every branch the factory pushes lives here. */
    static final String BRANCH_NAMESPACE = "spire/";

    /** Where the run unit mounts the handoff volume — topology, not an operator's decision. */
    private static final String DEFAULT_HANDOFF_DIR = "/handoff";

    private static final Set<String> REMOTE_SCHEMES = Set.of("https", "http");

    static PublisherConfig fromEnv(Map<String, String> env) {
        String baseBranch = required(env, "SPIRE_BRANCH_BASE");
        return new PublisherConfig(
                remoteUri(required(env, "SPIRE_REMOTE_URI")),
                baseBranch,
                required(env, "SPIRE_BASE_COMMIT"),
                branch(required(env, "SPIRE_BRANCH"), baseBranch),
                globs(env.get("SPIRE_PROTECTED_PATHS")),
                bundleMaxBytes(required(env, "SPIRE_BUNDLE_MAX_BYTES")),
                new GitCredential(required(env, "SPIRE_GIT_USERNAME"), required(env, "SPIRE_GIT_SECRET")),
                Path.of(env.getOrDefault("SPIRE_HANDOFF_DIR", DEFAULT_HANDOFF_DIR)));
    }

    private static String remoteUri(String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            // Not chained: URISyntaxException's message quotes the input, which may hold a secret.
            throw new IllegalStateException("SPIRE_REMOTE_URI is not a valid URI");
        }
        if (uri.getScheme() == null || !REMOTE_SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("SPIRE_REMOTE_URI must be an http(s) URL");
        }
        boolean authorityHasUserinfo = uri.getRawAuthority() != null && uri.getRawAuthority().contains("@");
        if (uri.getRawUserInfo() != null || authorityHasUserinfo) {
            throw new IllegalStateException("SPIRE_REMOTE_URI carries a credential in its userinfo; "
                    + "the credential goes in SPIRE_GIT_USERNAME and SPIRE_GIT_SECRET, never in the URL");
        }
        return value;
    }

    private static String branch(String branch, String baseBranch) {
        if (!Repository.isValidRefName("refs/heads/" + branch)) {
            throw new IllegalStateException("SPIRE_BRANCH is not a valid git branch name");
        }
        if (!branch.startsWith(BRANCH_NAMESPACE) || branch.length() == BRANCH_NAMESPACE.length()) {
            throw new IllegalStateException("SPIRE_BRANCH must name a branch under " + BRANCH_NAMESPACE
                    + "; the publisher never pushes outside the factory's namespace");
        }
        if (branch.equals(baseBranch)) {
            throw new IllegalStateException("SPIRE_BRANCH equals SPIRE_BRANCH_BASE; the publisher never "
                    + "pushes to the branch it forked from");
        }
        return branch;
    }

    private static long bundleMaxBytes(String value) {
        long bytes;
        try {
            bytes = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("SPIRE_BUNDLE_MAX_BYTES is not a number");
        }
        if (bytes <= 0) {
            throw new IllegalStateException("SPIRE_BUNDLE_MAX_BYTES must be positive; an unbounded "
                    + "bundle is what the cap exists to refuse");
        }
        return bytes;
    }

    private static List<String> globs(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required and was not set");
        }
        return value;
    }
}
