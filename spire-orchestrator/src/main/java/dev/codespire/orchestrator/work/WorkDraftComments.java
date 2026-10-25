package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.worksource.WorkSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * The ticket comment that carries a written specification (M4 slice 3, §6.3). It is an effect, not a write here: the
 * outbox owns the send and the uncertain-write recovery. It goes through the {@code draft} effect class, bound to the
 * generation and the attempt (Review Focus 24): under spec {@code auto} the approval follows the written artifact at
 * once, and a {@code phase} row would be refused {@code work_item_changed} before it was sent.
 *
 * <p>The specification is quoted per tracker markup ({@link UntrustedText#quoteBlock}): it is model output from a
 * ticket anyone could write, and it must not mention anyone or render on the ticket.
 */
@ApplicationScoped
public class WorkDraftComments {
    static final String SPECIFICATION_HEAD = "Specification written by the factory";

    @Inject WorkSourceEffects effects;
    @Inject WorkSourceRegistry sources;

    static String specification(String text, String sha256, UntrustedText.Markup markup) {
        return SPECIFICATION_HEAD + "\n\nDigest: " + sha256 + "\n\n" + UntrustedText.quoteBlock(text, markup)
                + "\n\nApprove or revise it on the dashboard.";
    }

    /**
     * Enqueues the comment on the decision's own connection, so a decision that rolls back leaves no comment behind,
     * and with an id derived from the attempt, so a redelivered result enqueues nothing new. A tracker that cannot
     * comment gets none.
     *
     * @param written the item as the written artifact left it: its attempt names the run that wrote {@code text}
     */
    void enqueue(Connection c, WorkItemEvent written, String text) throws SQLException {
        boolean comments = sources.get(written.sourceId()).map(source -> sources.client(source).capabilities()
                .contains(WorkSource.Capability.COMMENT)).orElse(false);
        if (!comments) return;
        String sha256 = written.draft().artifactFor(written.phase()).sha256();
        effects.enqueueDraft(c, new WorkSourceEffects.DraftComment(written.workItemId(), written.progress().attemptId(),
                specification(text, sha256, UntrustedText.Markup.of(written.issue().ref().type()))));
    }
}
