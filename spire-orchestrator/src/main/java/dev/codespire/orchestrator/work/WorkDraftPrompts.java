package dev.codespire.orchestrator.work;

import dev.codespire.worksource.WorkTicket;
import java.util.stream.Collectors;

/**
 * The prompt of a specification or plan run (M4 slice 3, §6.3, §6.4, A13). The instructions are ours; every text a
 * person or an earlier run wrote, the ticket, the approved specification, a validation error naming what the model wrote,
 * a revision note, is quoted as data in a fence it cannot close ({@link UntrustedText#fenced}). The repository's rules are not fetched here: the run has the
 * clone, so the agent is told to read {@code .codespire} itself (decision 5).
 *
 * <p>An empty ticket body is allowed. A vague ticket is the case this exists for; the composed preparation's
 * {@code ticket_body_empty} refusal is not copied here.
 *
 * <p>Every quoted section has its own cap, so the longest prompt stays under the 64 Ki characters a dispatch accepts:
 * a ticket longer than that is quoted cut, with the cut named, rather than refused at assembly as an unnamed build
 * configuration fault. The caps add to 43 KiB beside about 1 KiB of instructions; a plan prompt's to 31 KiB.
 */
final class WorkDraftPrompts {
    static final int MAX_TITLE_CHARS = 1024;
    static final int MAX_BODY_CHARS = 32 * 1024;
    static final int MAX_LABELS_CHARS = 4 * 1024;
    static final int MAX_ERROR_CHARS = 2 * 1024;
    static final int MAX_NOTE_CHARS = 4 * 1024;
    /** An accepted specification is at most 16 KiB of UTF-8, which is at most as many characters. */
    static final int MAX_SPECIFICATION_CHARS = 17 * 1024;
    static final int MAX_COMMANDS_CHARS = 8 * 1024;

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

    private static final String PLAN_INSTRUCTIONS = """
            Write a plan for building the approved specification below, as a list of steps. Read the repository as you need
            to, and read the file .codespire at the repository root if it exists: it holds this repository's rules.

            Steps are vertical slices: each leaves the repository working and testable, and passes the checks below on its
            own. Write at least one step and at most %d steps.

            The plan is JSON of exactly this shape, with "schemaVersion": 2:

            { "schemaVersion": 2, "specificationSha256": "...",
              "steps": [ { "id": "s1", "title": "...", "instruction": "...", "doneWhen": "..." } ] }

            Every step has a unique id, a title of at most 120 characters, an instruction of at most 4000 characters and a
            doneWhen of at most 1000 characters, none of them blank. Keep the whole plan under 32 KiB.

            Write the plan to the file named by the SPIRE_ARTIFACT environment variable, and change nothing in the
            repository.

            """;

    private WorkDraftPrompts() {}

    /**
     * What a plan run is built from (§6.4): the approved specification as stored, never the ticket read again, its
     * digest, the setup's verify commands and the item's step limit.
     */
    record PlanInput(String specification, String specificationSha256, java.util.List<String> verifyCommands, long maxSteps) {}

    /**
     * The prompt of a plan run. The specification is quoted as data; its digest is stated outside every fence, so the
     * agent copies it into the plan instead of hashing text it saw only inside a prompt.
     *
     * @param retryError why the previous plan was refused, for the one automatic retry; null on a first run
     * @param note what the person who asked for a revision wrote; null when nobody did
     */
    static String plan(PlanInput input, String retryError, String note) {
        String commands = input.verifyCommands().isEmpty() ? "(none declared)" : String.join("\n", input.verifyCommands());
        StringBuilder prompt = new StringBuilder(PLAN_INSTRUCTIONS.formatted(input.maxSteps()))
                .append("In the plan, set \"specificationSha256\" to ").append(input.specificationSha256()).append(".\n\n")
                .append(UntrustedText.fenced("The approved specification", input.specification(), MAX_SPECIFICATION_CHARS))
                .append(UntrustedText.fenced("The checks every step must pass", commands, MAX_COMMANDS_CHARS));
        if (retryError != null)
            prompt.append("\nYour previous plan was refused for the reason below. Write it again and correct it.\n")
                    .append(UntrustedText.fenced("The validation error", retryError, MAX_ERROR_CHARS));
        if (note != null)
            prompt.append("\nA person asked for this plan to be revised. Take their note into account.\n")
                    .append(UntrustedText.fenced("The revision note", note, MAX_NOTE_CHARS));
        return prompt.toString();
    }

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
