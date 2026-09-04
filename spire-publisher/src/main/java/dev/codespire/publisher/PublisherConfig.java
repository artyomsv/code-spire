package dev.codespire.publisher;

import dev.codespire.workspace.GitCredential;
import dev.codespire.workspace.PathGlob;
import org.eclipse.jgit.lib.Repository;

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
 *   <li><b>The target branch is checked against the branch MODE (ADR-040).</b> By default it must
 *       live under {@value #BRANCH_NAMESPACE}, differ from the base, and be a name git accepts. This
 *       process holds the only write credential in the run unit, and the gate judges paths, not refs
 *       — so a command naming {@code main} as the branch would have fast-forwarded the default
 *       branch with whatever the agent produced.
 *
 *       <p>{@code SPIRE_BRANCH_MODE=existing} lifts the namespace rule and the branch≠base rule
 *       together, so a fix can land on the pull request's own source branch — which M2 needs,
 *       because reconciliation is keyed per review and a second pull request is a second review
 *       whose prior finding could never resolve. **What does not lift is the floor**: the trunk names
 *       and the pull request's destination branch (passed as {@code SPIRE_PROTECTED_BRANCH}, because
 *       a deployment's trunk may be called {@code develop} and this process must not be able to make
 *       an API call to find out) are refused in EVERY mode.
 *
 *       <p>Whether the branch really is an open pull request's source branch is the orchestrator's
 *       proof, from {@code review_status}. These checks are the floor that survives a bug there.</li>
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

    static PublisherConfig fromEnv(Map<String, String> env) {
        String baseBranch = Env.required(env, "SPIRE_BRANCH_BASE");
        return new PublisherConfig(
                RemoteUri.validated(Env.required(env, "SPIRE_REMOTE_URI")),
                baseBranch,
                Env.required(env, "SPIRE_BASE_COMMIT"),
                branch(Env.required(env, "SPIRE_BRANCH"), baseBranch, env),
                globs(env.get("SPIRE_PROTECTED_PATHS")),
                bundleMaxBytes(Env.required(env, "SPIRE_BUNDLE_MAX_BYTES")),
                new GitCredential(Env.required(env, "SPIRE_GIT_USERNAME"), Env.required(env, "SPIRE_GIT_SECRET")),
                Path.of(env.getOrDefault("SPIRE_HANDOFF_DIR", DEFAULT_HANDOFF_DIR)));
    }

    /** The two branch shapes this process will push (ADR-040). Absent means {@link #NAMESPACE}. */
    private static final String MODE_NAMESPACE = "namespace";

    /** A pull request's own source branch, so a fix lands where the finding lives. */
    private static final String MODE_EXISTING = "existing";

    /**
     * Branches this process refuses to push in ANY mode, whatever the orchestrator asks for.
     *
     * <p>A convention list, not a truth: a deployment whose trunk is {@code develop} or
     * {@code release/2026.1} is not covered by it. That is why the destination branch arrives as its
     * own variable — the orchestrator READ the pull request and knows the real answer, while this
     * process must not be able to make an API call to find out. The list is the floor that survives
     * an orchestrator that forgets to pass one.
     */
    private static final Set<String> NEVER_PUSHED = Set.of("main", "master");

    /**
     * The target branch, validated against the mode.
     *
     * <p><b>ADR-040 lifts two rules together and only in {@code existing} mode</b>: a human's branch
     * is not under {@code spire/}, and pushing to the branch we cloned is the entire point. What does
     * NOT lift is the reason the floor exists — this process holds the only write credential in the
     * run unit and the gate judges paths, not refs, so a command naming a trunk would fast-forward it
     * with whatever the agent produced.
     *
     * <p><b>The mode is explicit and unknown values are refused rather than guessed.</b> Inferring it
     * from {@code branch.equals(baseBranch)} was the tempting shape and is wrong: an inference is a
     * default, and a default is what an orchestrator bug reaches by accident. Falling back to the
     * permissive mode on an unrecognised spelling has the same defect with an extra step.
     *
     * <p>Whether the branch really is an open pull request's source branch is the ORCHESTRATOR's
     * proof, resolved from {@code review_status}. These checks are the floor that survives a bug
     * there; they are not the identification.
     */
    private static String branch(String branch, String baseBranch, Map<String, String> env) {
        String mode = mode(env);
        if (!Repository.isValidRefName("refs/heads/" + branch)) {
            throw new IllegalStateException("SPIRE_BRANCH is not a valid git branch name");
        }
        if (NEVER_PUSHED.contains(branch)) {
            throw new IllegalStateException("SPIRE_BRANCH names " + branch + ", which this process "
                    + "never pushes to in any mode — the push gate judges paths, not refs, so a trunk "
                    + "would be fast-forwarded with whatever the agent produced");
        }
        String destination = env.get("SPIRE_PROTECTED_BRANCH");
        if (destination != null && !destination.isBlank() && branch.equals(destination.strip())) {
            throw new IllegalStateException("SPIRE_BRANCH names " + branch + ", which is the pull "
                    + "request's destination branch; a fix is pushed to its SOURCE branch");
        }
        if (MODE_EXISTING.equals(mode)) {
            return branch;
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

    private static String mode(Map<String, String> env) {
        String raw = env.get("SPIRE_BRANCH_MODE");
        if (raw == null || raw.isBlank()) {
            return MODE_NAMESPACE;
        }
        // Case and surrounding space are operator typing, not a different mode.
        String mode = raw.strip().toLowerCase(Locale.ROOT);
        if (!MODE_NAMESPACE.equals(mode) && !MODE_EXISTING.equals(mode)) {
            throw new IllegalStateException("SPIRE_BRANCH_MODE must be " + MODE_NAMESPACE + " or "
                    + MODE_EXISTING + "; an unrecognised value is refused rather than treated as the "
                    + "permissive one");
        }
        return mode;
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

    /**
     * Compiled here as well as in the gate, so a rule that could never match is a startup refusal
     * rather than a crash at the first bundle — after the agent has already run and been paid for.
     */
    private static List<String> globs(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> globs = Arrays.stream(raw.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        try {
            PathGlob.compileAll(globs);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("SPIRE_PROTECTED_PATHS holds a glob the gate cannot apply: "
                    + e.getMessage());
        }
        return globs;
    }
}
