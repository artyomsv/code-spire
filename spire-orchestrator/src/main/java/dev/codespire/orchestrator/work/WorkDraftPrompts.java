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
 */
final class WorkDraftPrompts {
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
        StringBuilder prompt = new StringBuilder(SPECIFICATION_INSTRUCTIONS)
                .append(UntrustedText.fenced("The ticket title", ticket.title()))
                .append(UntrustedText.fenced("The ticket body", ticket.body() == null || ticket.body().isBlank() ? "(empty)" : ticket.body()))
                .append(UntrustedText.fenced("The ticket labels", ticket.currentLabels().isEmpty() ? "(none)"
                        : ticket.currentLabels().stream().sorted().collect(Collectors.joining("\n"))));
        if (retryError != null)
            prompt.append("\nYour previous specification was refused for the reason below. Write it again and correct it.\n")
                    .append(UntrustedText.fenced("The validation error", retryError));
        if (note != null)
            prompt.append("\nA person asked for this specification to be revised. Take their note into account.\n")
                    .append(UntrustedText.fenced("The revision note", note));
        return prompt.toString();
    }
}
