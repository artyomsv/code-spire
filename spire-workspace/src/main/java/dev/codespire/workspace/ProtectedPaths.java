package dev.codespire.workspace;

import java.util.List;

/**
 * Paths the factory may never push a change to.
 *
 * <p><b>A floor, not a setting (ADR-037).</b> A pushed branch executes its own CI workflow files on
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
            // OpenShift Pipelines-as-Code executes .tekton/*.yaml from the PULL REQUEST HEAD, on
            // a cluster runner holding secrets. Same shape as a workflow file, and it was the
            // one mainstream system this floor had missed.
            ".tekton/**",
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

    /**
     * Every directory the floor protects, derived from the floor itself.
     *
     * <p>Used to judge a SYMLINK, which the globs cannot reach. A tree entry of mode
     * {@code 120000} committed at {@code .github} pointing to {@code payload/} is judged as the
     * path {@code .github}, and no floor glob matches that — the globs all carry the
     * {@code .github/workflows/} prefix. A forge that follows the link then reads its workflows
     * out of agent-authored content without a single protected path appearing in the diff.
     *
     * <p><b>Derived, never a second hand-written list.</b> A floor entry added above is covered
     * here the same day; a parallel list would be covered whenever somebody remembered.
     */
    public static final List<String> CI_DIRECTORIES = CI_FLOOR.stream()
            .filter(glob -> glob.endsWith("/**"))
            .map(glob -> glob.substring(0, glob.length() - "/**".length()))
            // A leading ** would make every path an ancestor of something protected. None exists
            // today; this is here so adding one is a no-op rather than a gate that refuses all.
            .filter(directory -> !directory.contains("*"))
            .toList();

    /**
     * Whether {@code path} IS a protected directory or an ancestor of one.
     *
     * <p>Case-insensitive, matching {@link PathGlob}: the floor would otherwise refuse
     * {@code .github/workflows/ci.yml} and wave through a symlink at {@code .GITHUB}.
     */
    public static boolean isAtOrAboveAProtectedDirectory(String path) {
        for (String directory : CI_DIRECTORIES) {
            if (directory.equalsIgnoreCase(path)
                    || directory.regionMatches(true, 0, path + "/", 0, path.length() + 1)) {
                return true;
            }
        }
        return false;
    }

    private ProtectedPaths() {
    }
}
