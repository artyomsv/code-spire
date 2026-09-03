package dev.codespire.runworker;

/**
 * Where the worker's own lines about a run join that run's transcript.
 *
 * <p><b>One allocator per run, and that is the whole reason this exists.</b> The transcript has two
 * producers — the agent's output and an operator's interventions — and they were numbered by two
 * independent counters: the agent's per run from 1, the worker's from a single process-wide counter
 * shared by every concurrent run. Both wrote into {@code run_event}, whose primary key is
 * {@code (run_id, seq)} and whose writer inserts {@code ON CONFLICT DO NOTHING} because a redelivered
 * event must not duplicate a line. So whichever of the two arrived second at a given number was
 * discarded in silence — and the line most likely to lose that race is the operator's, because an
 * agent stream runs to hundreds of events while a note is one.
 *
 * <p>That is the worst line to drop. A steer injects text into an agent running shell at full access
 * inside a credentialed sandbox; the note is the only record that a human did so. Nothing logged the
 * conflict, because a zero-row insert is indistinguishable from the redelivery the clause is for.
 *
 * <p>Basing the notes above the agent's cap was the obvious alternative and is broken: the live tail
 * reads {@code WHERE seq > ?} and orders by {@code seq}, so a note jumping to the top of the range
 * would advance the client's cursor past every event the agent had yet to emit, and the tail would
 * go dead for the rest of the run.
 *
 * <p>So notes are allocated by the run's own {@link RunEventStream}, which already numbers that run's
 * events under a lock. They interleave in true order, the cursor keeps working, and a redelivery
 * still deduplicates.
 */
public interface RunNotes {

    /**
     * Add one line to this run's transcript.
     *
     * <p>{@code error} marks a line an operator needs to notice — a refused instruction rather than a
     * delivered one — so it stands out on a live tail instead of scrolling past among the agent's own
     * output. Never throws: a transcript is what an operator watches, not what a run depends on.
     */
    void note(String kind, String text, boolean error);

    /** Discards every note. For a caller that only wants the terminal result. */
    RunNotes IGNORING = (kind, text, error) -> {
        // nothing
    };
}
