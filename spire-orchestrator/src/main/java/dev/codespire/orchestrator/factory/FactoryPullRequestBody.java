package dev.codespire.orchestrator.factory;

import java.util.List;

/**
 * The title and description of a pull request the factory opened.
 *
 * <p><b>Written entirely by the orchestrator, and that is what makes the mark trustworthy.</b> The
 * agent never authors this text; the only agent-influenced values are the paths it changed, and they
 * arrive fenced.
 *
 * <p>Static and framework-free, like {@link FixPrompt}: a pure function of a finished run, testable
 * without a bean.
 */
final class FactoryPullRequestBody {

    /**
     * The machine-readable mark that this pull request came from a factory run.
     *
     * <p><b>A marker in the body rather than a label, and the reason is cross-provider.</b> GitHub
     * and GitLab have label APIs; Bitbucket Cloud has none for pull requests. A label would therefore
     * be a mark that exists on two forges out of three, which is worse than no mark at all — a
     * consumer would learn to trust it and then be silently wrong on the third. This project's
     * recorded trap is exactly that shape: a ref carried by all three that does not MEAN the same
     * thing on all three.
     *
     * <p>An HTML comment, so it is invisible in every forge's rendered Markdown while surviving the
     * round trip verbatim. It exists for two readers. A human triaging the queue sees the visible
     * first line; the reviewer's own author-allowlist path needs to know that a pull request opened
     * by the machine account is one it SHOULD review — AUTONOMY.md names the opposite as a silent
     * failure, where the factory produces work nobody looks at.
     */
    static final String MARK = "<!-- codespire-factory-run -->";

    /** Fences the one part of this body the agent influenced. See {@link FixPrompt} for the mirror. */
    private static final String FENCE = "```text";

    /** A body longer than this is truncated; every forge caps a description and they disagree on where. */
    private static final int MAX_PATHS_SHOWN = 50;

    private FactoryPullRequestBody() {
    }

    /**
     * @param runId the address the run answers on. The ONLY path from this pull request back to its
     *     transcript, its cost and the work item that caused it — so it is in the body, not merely in
     *     a database somewhere
     * @param task one line saying what the run was asked to do, orchestrator-authored
     * @param changedPaths what the agent wrote. Agent-influenced, therefore fenced
     */
    static String of(String runId, String task, List<String> changedPaths) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("a factory pull request must name its run, or nothing "
                    + "leads back to the transcript that explains it");
        }
        StringBuilder body = new StringBuilder();
        body.append(MARK).append('\n');
        body.append("An automated run produced this branch. **Review it as you would any other pull "
                + "request** — it has not been reviewed by a person.\n\n");
        body.append("**Task:** ").append(task == null || task.isBlank() ? "not recorded" : task)
                .append('\n');
        body.append("**Run:** `").append(runId).append("`\n\n");
        body.append(paths(changedPaths));
        return body.toString();
    }

    /**
     * The changed paths, fenced.
     *
     * <p>They are the agent's output, so they are the mirror image of the finding text {@code
     * FixPrompt} fences on the way IN: this body is read by humans and, on the next round, by the
     * reviewer's own model as pull-request context. A path is a poor place to hide an instruction and
     * a fence is a poor defence, which is why neither is claimed as one — the fence keeps an
     * accidental line from reading as prose, and nothing here is load-bearing security.
     */
    private static String paths(List<String> changedPaths) {
        if (changedPaths == null || changedPaths.isEmpty()) {
            // Not an error: a run that changed nothing still pushed a branch in some flows, and
            // saying so plainly beats an empty heading a reader has to interpret.
            return "**Changed:** nothing was reported as changed.\n";
        }
        StringBuilder out = new StringBuilder();
        out.append("**Changed ").append(changedPaths.size()).append(" file(s):**\n\n")
                .append(FENCE).append('\n');
        changedPaths.stream().limit(MAX_PATHS_SHOWN).forEach(path -> out.append(path).append('\n'));
        if (changedPaths.size() > MAX_PATHS_SHOWN) {
            out.append("… and ").append(changedPaths.size() - MAX_PATHS_SHOWN).append(" more\n");
        }
        out.append("```\n");
        return out.toString();
    }

    /**
     * One line for the forge's list view.
     *
     * <p>Prefixed, so a person scanning a list of pull requests can see which are machine-authored
     * without opening one. The prefix is redundant with {@link #MARK} on purpose: that one is for
     * code and invisible, this one is for people and cannot be.
     */
    static String title(String task) {
        String subject = task == null || task.isBlank() ? "automated change" : task.strip();
        String oneLine = subject.lines().findFirst().orElse(subject);
        String trimmed = oneLine.length() <= 60 ? oneLine : oneLine.substring(0, 57) + "…";
        return "[factory] " + trimmed;
    }
}
