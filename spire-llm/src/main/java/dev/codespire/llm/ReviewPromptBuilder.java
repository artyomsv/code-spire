package dev.codespire.llm;

import dev.codespire.contract.llm.Prompt;
import dev.codespire.contract.llm.PromptCatalog;
import dev.codespire.contract.llm.PromptKind;
import dev.codespire.contract.llm.PromptTemplate;
import dev.codespire.contract.review.ContextItem;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.FilePatch;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the review prompt. Prompt-injection posture (SECURITY.md):
 * ALL PR text, diff content, and retrieved context is fenced as UNTRUSTED
 * DATA; the system message is never assembled from untrusted content and
 * explicitly instructs the model to ignore instructions inside the data.
 */
public final class ReviewPromptBuilder {

    private ReviewPromptBuilder() {
    }

    /**
     * What a review is built from.
     *
     * @param alreadyReported the prior findings still open, which the model is told not to raise again
     */
    public record ReviewInput(PullRequest pr, List<FilePatch> patches, List<ContextItem> context,
                              List<PriorFinding> alreadyReported) {
    }

    /**
     * The built prompt and what was cut to fit it.
     *
     * @param omittedFiles  the changed files the model will not fully see; empty when the whole diff went in
     * @param clippedInputs the fenced inputs cut to their budget ({@code diff} whenever a file was omitted)
     * @param size          whether more diff was sent than today's fixed budget allowed, so a timeout must not re-send it
     */
    public record Built(Prompt prompt, List<String> omittedFiles, Set<String> clippedInputs, PromptSize size) {

        public Built {
            omittedFiles = List.copyOf(omittedFiles);
            clippedInputs = Collections.unmodifiableSet(new LinkedHashSet<>(clippedInputs));
        }

        /** Whether any fenced input was clipped (the diff, the context, the prior findings). */
        public boolean truncated() {
            return !clippedInputs.isEmpty();
        }
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
        return build(new ReviewInput(pr, patches, context, alreadyReported), template, DiffBudget.Limits.UNKNOWN);
    }

    /**
     * The review prompt with the diff sized to the model ({@link DiffBudget}) and cut at file boundaries
     * ({@link DiffSelection}).
     *
     * @param template the operator's REVIEW template, or null for the built-in default
     * @throws DiffBudget.NoRoomForDiff when the model's window cannot hold a diff beside the rest of the prompt
     */
    public static Built build(ReviewInput input, PromptTemplate template, DiffBudget.Limits limits) {
        PromptTemplate effective = template != null ? template : PromptCatalog.defaultTemplate(PromptKind.REVIEW);
        PullRequest pr = input.pr();
        Map<String, String> values = new HashMap<>();
        values.put("pr_title", pr.title() == null ? "" : pr.title());
        values.put("pr_description", pr.description() == null ? "" : pr.description());
        values.put("context", renderContext(input.context(), false));
        values.put("code_context", renderContext(input.context(), true));
        values.put("prior_findings", renderAlreadyReported(input.alreadyReported()));
        Map<String, Integer> background = InputBudgets.forWindow(PromptKind.REVIEW, limits);
        int budget = DiffBudget.forTemplate(effective, values, limits);
        DiffSelection selection = DiffSelection.byFile(input.patches(), budget);
        values.put(DiffBudget.DIFF, selection.text());
        PromptRenderer.Rendered rendered = PromptRenderer.render(effective, values,
                InputBudgets.withDiff(background, budget));
        Set<String> clipped = new LinkedHashSet<>(rendered.clippedVariables());
        if (!selection.omittedFiles().isEmpty()) {
            clipped.add(DiffBudget.DIFF);
        }
        return new Built(rendered.prompt(), selection.omittedFiles(), clipped,
                DiffBudget.sizeOf(selection.text(), budget, PromptKind.REVIEW));
    }

    /**
     * Partitions retrieved context by kind: {@code CODE_SNIPPET} items render into their own
     * {@code {{code_context}}} slot (its own token budget) so a chatty linked ticket in
     * {@code {{context}}} can never squeeze retrieved code definitions out by sharing one budget.
     */
    static String renderContext(List<ContextItem> context, boolean codeSnippets) {
        StringBuilder out = new StringBuilder();
        for (ContextItem item : context) {
            boolean isCodeSnippet = ContextItem.CODE_SNIPPET.equals(item.kind());
            if (isCodeSnippet != codeSnippets) {
                continue;
            }
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
}
