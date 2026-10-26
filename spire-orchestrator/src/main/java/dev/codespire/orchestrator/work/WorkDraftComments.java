package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.worksource.WorkSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Function;

/**
 * The ticket comment that carries a written specification or plan (M4 slice 3, §6.3, §6.4). It is an effect, not a
 * write here: the outbox owns the send and the uncertain-write recovery. It goes through the {@code draft} effect class,
 * bound to the generation and the attempt (Review Focus 24): under {@code auto} the approval follows the written
 * artifact at once, and a {@code phase} row would be refused {@code work_item_changed} before it was sent.
 *
 * <p>The specification is quoted per tracker markup ({@link UntrustedText#quoteBlock}): a blockquote that renders on
 * GitHub and GitLab, a noformat block on Jira. A plan is posted as its numbered step titles, each on one line; on GitHub
 * and GitLab the list is quoted the same way, on Jira each title is neutralised ({@link UntrustedText#neutralise}). Both
 * are model output from a ticket anyone could write, and neither may mention, link, load or run anything on the ticket.
 */
@ApplicationScoped
public class WorkDraftComments {
    static final String SPECIFICATION_HEAD = "Specification written by the factory";
    static final String PLAN_HEAD = "Plan written by the factory";
    private static final String CLOSING = "\n\nApprove or revise it on the dashboard.";

    @Inject WorkSourceEffects effects;
    @Inject WorkSourceRegistry sources;

    static String specification(String text, String sha256, UntrustedText.Markup markup) {
        return SPECIFICATION_HEAD + "\n\nDigest: " + sha256 + "\n\n" + UntrustedText.quoteBlock(text, markup) + CLOSING;
    }

    static String plan(List<WorkArtifacts.Step> steps, String sha256, UntrustedText.Markup markup) {
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < steps.size(); i++)
            list.append(i == 0 ? "" : "\n").append(i + 1).append(". ").append(title(steps.get(i).title(), markup));
        String quoted = markup == UntrustedText.Markup.WIKI ? list.toString() : UntrustedText.quoteBlock(list.toString(), markup);
        return PLAN_HEAD + "\n\nDigest: " + sha256 + "\n\n" + quoted + CLOSING;
    }

    /** A title on one line; in Markdown it is made inert by the blockquote around the whole list, not here. */
    private static String title(String title, UntrustedText.Markup markup) {
        if (markup == UntrustedText.Markup.WIKI) return UntrustedText.neutralise(title, markup);
        return UntrustedText.stripControls(title).replaceAll("\\s+", " ").strip();
    }

    /**
     * Enqueues the comment on the decision's own connection, so a decision that rolls back leaves no comment behind,
     * and with an id derived from the attempt, so a redelivered result enqueues nothing new. A tracker that cannot
     * comment gets none.
     *
     * @param written the item as the written artifact left it: its attempt names the run that wrote the artifact
     * @param body the comment, in the markup of the item's tracker
     */
    void enqueue(Connection c, WorkItemEvent written, Function<UntrustedText.Markup, String> body) throws SQLException {
        boolean comments = sources.get(written.sourceId()).map(source -> sources.client(source).capabilities()
                .contains(WorkSource.Capability.COMMENT)).orElse(false);
        if (!comments) return;
        effects.enqueueDraft(c, new WorkSourceEffects.DraftComment(written.workItemId(), written.progress().attemptId(),
                body.apply(UntrustedText.Markup.of(written.issue().ref().type()))));
    }
}
