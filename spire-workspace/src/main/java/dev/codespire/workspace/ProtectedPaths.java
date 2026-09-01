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
 * <p><b>A composite action is a DIRECT route, one indirection out from the workflow.</b> A workflow
 * saying {@code uses: ./tools/build} executes {@code tools/build/action.yml}, and {@code uses: ./}
 * executes {@code action.yml} at the root. An agent that rewrites that file's {@code run:} steps
 * never touches {@code .github/workflows/**} at all — the workflow is unchanged and runs the new
 * code on the runner holding the secrets. That is the GhostAction shape with one extra hop, so both
 * spellings are covered at any depth.
 *
 * <p><b>The floor is still necessarily incomplete, and pretending otherwise would be the real
 * danger.</b> A workflow that runs {@code ./scripts/build.sh} grants the same execution to anything
 * that edits that script, and no list of CI-configuration paths can close it. What this covers is
 * every route where the executed file IS the configuration. The forge-side ruleset described in
 * RUN-TOPOLOGY §6.3 is the second layer, and the container is the third.
 */
public final class ProtectedPaths {

    public static final List<String> CI_FLOOR = List.of(
            // GitHub, and the forges that reimplement its layout.
            ".github/workflows/**",
            ".github/actions/**",
            ".gitea/workflows/**",
            ".forgejo/workflows/**",
            // A composite action, at the root or at any depth. Both spellings: GitHub accepts each.
            "action.yml",
            "action.yaml",
            "**/action.yml",
            "**/action.yaml",
            // GitLab.
            ".gitlab-ci.yml",
            ".gitlab/**",
            // Bitbucket.
            "bitbucket-pipelines.yml",
            // Jenkins. The suffixed form is real — Jenkinsfile.release is a common convention.
            "Jenkinsfile",
            "Jenkinsfile.*",
            // A multibranch or folder job sets a "Script Path", and ci/Jenkinsfile is a common one; a
            // Jenkinsfile IS the configuration wherever it sits, like a composite action.
            "**/Jenkinsfile",
            "**/Jenkinsfile.*",
            // The rest of the field.
            ".circleci/**",
            "azure-pipelines.yml",
            "azure-pipelines.yaml",
            ".drone.yml",
            ".drone.yaml",
            ".woodpecker.yml",
            // Woodpecker reads BOTH spellings at the root; the directory form below covers both.
            ".woodpecker.yaml",
            ".woodpecker/**",
            ".travis.yml",
            "appveyor.yml",
            ".appveyor.yml",
            ".buildkite/**",
            ".teamcity/**",
            "cloudbuild.yaml",
            "cloudbuild.yml",
            "buildspec.yml",
            "buildspec.yaml",
            ".semaphore/**",
            // Not CI configuration itself, but it redirects what `actions/checkout submodules:true`
            // fetches and then builds — so editing it changes what runs without changing a workflow.
            ".gitmodules");

    private ProtectedPaths() {
    }
}
