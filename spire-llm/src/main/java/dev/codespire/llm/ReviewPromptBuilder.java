package dev.codespire.llm;

import dev.codespire.contract.llm.Prompt;
import dev.codespire.contract.llm.PromptCatalog;
import dev.codespire.contract.llm.PromptKind;
import dev.codespire.contract.llm.PromptTemplate;
import dev.codespire.contract.review.ContextItem;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.diff.DiffRenderer;
import dev.codespire.contract.scm.FilePatch;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the review prompt. Prompt-injection posture (SECURITY.md):
 * ALL PR text, diff content, and retrieved context is fenced as UNTRUSTED
 * DATA; the system message is never assembled from untrusted content and
 * explicitly instructs the model to ignore instructions inside the data.
 */
public final class ReviewPromptBuilder {

    private ReviewPromptBuilder() {
    }

    /** The built prompt plus whether the diff/context had to be clipped to fit the budget. */
    public record Built(Prompt prompt, boolean truncated) {
    }

    public static Built build(PullRequest pr, List<FilePatch> patches, List<ContextItem> context) {
        return build(pr, patches, context, List.of(), null);
    }

    public static Built build(PullRequest pr, List<FilePatch> patches, List<ContextItem> context,
                              List<PriorFinding> alreadyReported) {
        return build(pr, patches, context, alreadyReported, null);
    }

    public static Built build(PullRequest pr, List<FilePatch> patches, List<ContextItem> context,
                              List<PriorFinding> alreadyReported, PromptTemplate template) {
        PromptTemplate effective = template != null ? template : PromptCatalog.defaultTemplate(PromptKind.REVIEW);
        Map<String, String> values = new HashMap<>();
        values.put("pr_title", pr.title() == null ? "" : pr.title());
        values.put("pr_description", pr.description() == null ? "" : pr.description());
        values.put("context", renderContext(context));
        values.put("prior_findings", renderAlreadyReported(alreadyReported));
        values.put("diff", DiffRenderer.render(patches));
        PromptRenderer.Rendered rendered = PromptRenderer.render(effective, values);
        return new Built(rendered.prompt(), rendered.truncated());
    }

    private static String renderContext(List<ContextItem> context) {
        if (context.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (ContextItem item : context) {
            out.append("- [").append(item.kind()).append("] ").append(item.title())
                    .append(": ").append(item.body()).append('\n');
        }
        return out.toString();
    }

    private static String renderAlreadyReported(List<PriorFinding> alreadyReported) {
        if (alreadyReported.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (PriorFinding finding : alreadyReported) {
            out.append("- ").append(finding.path()).append(':').append(finding.line())
                    .append(" — ").append(finding.message()).append('\n');
        }
        return out.toString();
    }

    // TEMPORARY: retained so ReconcilePrompt/FollowUpPrompt still compile until Tasks 3-4 refactor
    // them off it; deleted in Task 4. The renderer is the real owner of sentinel neutralization.
    static String neutralizeSentinels(String untrusted) {
        return untrusted == null ? "" : untrusted.replace("UNTRUSTED_DATA", "UNTRUSTED-DATA");
    }
}
