package dev.codespire.orchestrator.work;

import dev.codespire.worksource.WorkTicket;
import java.util.stream.Collectors;

/**
 * The prompt of a specification run (M4 slice 3, §6.3, A13). The instructions are ours; every text a person or an
 * earlier run wrote, the ticket, a validation error naming what the model wrote, a revision note, is quoted as data in
 * a fence it cannot close ({@link UntrustedText#fenced}). The repository's rules are not fetched here: the run has the
 * clone, so the agent is told to read {@code .codespire} itself (decision 5).
 *
 * <p>An empty ticket body is allowed. A vague ticket is the case this exists for; the composed preparation's
 * {@code ticket_body_empty} refusal is not copied here.
 *
 * <p>Every quoted section has its own cap, so the longest prompt stays under the 64 Ki characters a dispatch accepts:
 * a ticket longer than that is quoted cut, with the cut named, rather than refused at assembly as an unnamed build
 * configuration fault. The caps add to 43 KiB beside about 1 KiB of instructions.
 */
final class WorkDraftPrompts {
    static final int MAX_TITLE_CHARS = 1024;
    static final int MAX_BODY_CHARS = 32 * 1024;
    static final int MAX_LABELS_CHARS = 4 * 1024;
    static final int MAX_ERROR_CHARS = 2 * 1024;
    static final int MAX_NOTE_CHARS = 4 * 1024;

    private static final String SPECIFICATION_INSTRUCTIONS = """
            Write a specification for the work the ticket below asks for. Read the repository as you need to understand
            it, and read the file .codespire at the repository root if it exists: it holds this repository's rules.

            The specification is Markdown with exactly these headings, in this order:

            ## Outcome
            ## Context
            ## Acceptance criteria
            ## Out of scope
            ## Open questions

            Under Acceptance criteria, write a list of at least one testable item. Open questions may say None. Keep the
            whole specification under 16 KiB.

            Write the specification to the file named by the SPIRE_ARTIFACT environment variable, and change nothing in
            the repository.

            """;

    private WorkDraftPrompts() {}

    /**
     * @param retryError why the previous artifact was refused, for the one automatic retry; null on a first run
     * @param note what the person who asked for a revision wrote; null when nobody did
     */
    static String specification(WorkTicket ticket, String retryError, String note) {
        String body = ticket.body() == null || ticket.body().isBlank() ? "(empty)" : ticket.body();
        String labels = ticket.currentLabels().isEmpty() ? "(none)"
                : ticket.currentLabels().stream().sorted().collect(Collectors.joining("\n"));
        StringBuilder prompt = new StringBuilder(SPECIFICATION_INSTRUCTIONS)
                .append(UntrustedText.fenced("The ticket title", ticket.title(), MAX_TITLE_CHARS))
                .append(UntrustedText.fenced("The ticket body", body, MAX_BODY_CHARS))
                .append(UntrustedText.fenced("The ticket labels", labels, MAX_LABELS_CHARS));
        if (retryError != null)
            prompt.append("\nYour previous specification was refused for the reason below. Write it again and correct it.\n")
                    .append(UntrustedText.fenced("The validation error", retryError, MAX_ERROR_CHARS));
        if (note != null)
            prompt.append("\nA person asked for this specification to be revised. Take their note into account.\n")
                    .append(UntrustedText.fenced("The revision note", note, MAX_NOTE_CHARS));
        return prompt.toString();
    }
}
