package dev.codespire.workspace;

import java.util.List;

/**
 * Paths the factory may never push a change to.
 *
 * <p><b>A floor, not a setting (ADR-036).</b> A pushed branch executes its own CI workflow files on
 * an unsandboxed runner holding repository secrets, and the prompt that produced the branch contains
 * untrusted tracker text. The input that would authorise the change is the input under suspicion, so
 * no profile may unprotect these — the same shape as the never-suppressed SECURITY floor in ADR-027.
 *
 * <p><b>The floor is necessarily incomplete, and pretending otherwise would be the real danger.</b>
 * A workflow that runs {@code ./scripts/build.sh} gives the same execution to anything that edits
 * that script, and no list of CI-configuration paths can cover it. What this closes is the direct
 * route — a branch rewriting the workflow that runs it. The forge-side ruleset described in
 * RUN-TOPOLOGY §6.3 is the second layer, and the container is the third.
 */
public final class ProtectedPaths {

    public static final List<String> CI_FLOOR = List.of(
            ".github/workflows/**",
            ".github/actions/**",
            ".gitlab-ci.yml",
            ".gitlab/**",
            "bitbucket-pipelines.yml",
            "Jenkinsfile",
            ".circleci/**",
            "azure-pipelines.yml");

    private ProtectedPaths() {
    }
}
