package dev.codespire.orchestrator.factory;

import java.util.List;

/**
 * The title and description of a pull request the factory opened.
 *
 * <p><b>The STRUCTURE is the orchestrator's; two of the values are not.</b> An earlier version of
 * this javadoc claimed the whole body was orchestrator-authored and that the changed paths were the
 * only agent-influenced part. A review falsified it: the only run-task text this codebase produces
 * is {@code ExecuteRun.prompt}, and for a fix run that is {@link FixPrompt}'s output — model-derived,
 * contributor-steerable, and multi-line. So {@code task} is untrusted in exactly the way the paths
 * are, and it was being interpolated raw while the title beside it was already bounded to one line.
 *
 * <p>Both are normalised structurally now rather than by asking callers to behave: the task is cut
 * to one bounded line wherever it appears, and the paths are fenced.
 *
 * <p><b>Neither is claimed as a security control.</b> A fence does not bound a model that reads
 * inside it, and this body is read by the reviewer's own model as pull-request context on the next
 * round — the same way it already reads arbitrary contributor-written descriptions. What bounds the
 * damage is elsewhere: the agent holds no write credential, the publisher holds the only one, the
 * push gate judges paths, and ADR-040 bounds the branch. The normalisation is here so the body keeps
 * its SHAPE, which is what the machine-readable mark depends on.
 *
 * <p>Static and framework-free, like {@link FixPrompt}: a pure function of a finished run.
 *
 * <p><b>Nothing calls this in production yet, and that is stated rather than hidden.</b> It is
 * the orchestrator half of T7: the {@code PullRequestSink} port and its three adapters can open
 * a pull request, and this builds what one would say. The step that runs after a fix run pushes
 * — read the result, choose a sink, open the request — is M3 work and is not in this branch. The
 * class is covered by its own tests and by nothing downstream, so a change here is not currently
 * proved end to end.
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
     * first line; the reviewer's own author gate needs to know that a pull request opened by the
     * machine account is one it SHOULD review.
     *
     * <p><b>Nothing reads it yet, and that is a gap rather than a design.</b> A security review
     * established that pull-request authorship is not gated at all today — the bot-authored check
     * covers comments and commands only, and an empty allowlist means everyone — so by default the
     * reviewer does review these. The silent failure AUTONOMY.md names arrives for any operator who
     * HAS set an allowlist that omits the factory account. Closing it means the reviewer's gate
     * consulting either this mark or that account's id, which is the consumer's slice.
     */
    static final String MARK = "<!-- codespire-factory-run -->";

    /** The default fence. Widened when a path would close it — see {@link #fenceFor}. */
    private static final String BACKTICKS = "```";

    /**
     * How many changed paths the description lists before it says "and N more".
     *
     * <p>A count of PATHS, not a length in characters — an earlier version of this line said the
     * latter, which described a different field entirely. Bounded for readability: a large refactor
     * touches hundreds of files and nobody reads that list in a pull request.
     */
    private static final int MAX_PATHS_SHOWN = 50;

    /** What a forge list view shows before truncating; the ellipsis is counted INSIDE the bound. */
    private static final int MAX_TITLE_CHARS = 60;

    /**
     * And the bound on the task line in the body, which had none at all.
     *
     * <p>Longer than the title because a description has room, short enough that a model-derived
     * paragraph cannot become the body. The task is the one genuinely large input here.
     */
    private static final int MAX_TASK_CHARS = 200;

    private static final String ELLIPSIS = "…";

    private FactoryPullRequestBody() {
    }

    /**
     * @param runId the address the run answers on. The ONLY path from this pull request back to its
     *     transcript, its cost and the work item that caused it — so it is in the body, not merely in
     *     a database somewhere
     * @param task what the run was asked to do. Model-derived and multi-line in practice, so it is
     *     cut to one bounded line here rather than trusted to arrive as one
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
        body.append("**Task:** ").append(oneLine(task, MAX_TASK_CHARS, "not recorded")).append('\n');
        body.append("**Run:** `").append(runId).append("`\n\n");
        body.append(paths(changedPaths));
        return body.toString();
    }

    /**
     * One line for the forge's list view.
     *
     * <p>Prefixed, so a person scanning a list of pull requests can see which are machine-authored
     * without opening one. The prefix is redundant with {@link #MARK} on purpose: that one is for
     * code and invisible, this one is for people and cannot be.
     */
    static String title(String task) {
        return "[factory] " + oneLine(task, MAX_TITLE_CHARS, "automated change");
    }

    /**
     * The first line of a model-derived value, bounded, with the cut made visible.
     *
     * <p>Shared by the title and the body because they had drifted: the title took
     * {@code lines().findFirst()} and the body took the whole thing, so a multi-line task — which is
     * every fix prompt — rewrote the body's structure while leaving the title intact.
     *
     * <p>Cut on a code-point boundary, so a task starting with an emoji cannot leave a lone surrogate
     * in a forge's list view.
     *
     * @param whenAbsent what to say instead. The two callers differ on purpose: a TITLE says what the
     *     pull request is ("automated change"), a BODY line says what was recorded about it ("not
     *     recorded"). Unifying them made the title read "[factory] not recorded", which describes
     *     the record rather than the change
     */
    private static String oneLine(String task, int max, String whenAbsent) {
        if (task == null || task.isBlank()) {
            return whenAbsent;
        }
        String line = task.strip().lines().findFirst().orElse("").strip();
        if (line.isEmpty()) {
            return whenAbsent;
        }
        if (line.codePointCount(0, line.length()) <= max) {
            return line;
        }
        int cut = line.offsetByCodePoints(0, max - ELLIPSIS.length());
        return line.substring(0, cut) + ELLIPSIS;
    }

    /**
     * The changed paths, fenced.
     *
     * <p>They are the agent's output, so they are the mirror image of the finding text
     * {@link FixPrompt} fences on the way IN. See the class javadoc for what the fence is and is not.
     */
    private static String paths(List<String> changedPaths) {
        if (changedPaths == null || changedPaths.isEmpty()) {
            // Not an error: a run that changed nothing still pushed a branch in some flows, and
            // saying so plainly beats an empty heading a reader has to interpret.
            return "**Changed:** nothing was reported as changed.\n";
        }
        List<String> shown = changedPaths.stream().limit(MAX_PATHS_SHOWN).toList();
        String fence = fenceFor(shown);
        StringBuilder out = new StringBuilder();
        out.append("**Changed ").append(changedPaths.size()).append(" file(s):**\n\n")
                .append(fence).append("text\n");
        shown.forEach(path -> out.append(path).append('\n'));
        if (changedPaths.size() > MAX_PATHS_SHOWN) {
            out.append("… and ").append(changedPaths.size() - MAX_PATHS_SHOWN).append(" more\n");
        }
        out.append(fence).append('\n');
        return out.toString();
    }

    /**
     * A fence longer than anything inside it can close.
     *
     * <p>CommonMark closes a fence on a line that is SOLELY backticks, so a path containing them
     * mid-string is harmless — but a file named exactly {@code ```} at the repository root is one
     * line of exactly three backticks, and it would close the fence and render every path after it as
     * prose. A path cannot contain a newline ({@code PublishRepo.safe} refuses one), so counting the
     * longest run in the listed paths and going one longer is sufficient and exact.
     */
    private static String fenceFor(List<String> shown) {
        int longest = shown.stream().mapToInt(FactoryPullRequestBody::longestBacktickRun).max().orElse(0);
        return "`".repeat(Math.max(BACKTICKS.length(), longest + 1));
    }

    private static int longestBacktickRun(String path) {
        int longest = 0;
        int run = 0;
        for (int i = 0; i < path.length(); i++) {
            run = path.charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        return longest;
    }
}
