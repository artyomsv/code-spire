package dev.codespire.runworker;

/**
 * The text the worker appends to a dispatched prompt, chosen by the unit's topology: a build is told to commit, a
 * specification or plan run is told the repository is read-only and where its result goes.
 *
 * <p><b>RUN-TOPOLOGY §"Two layers make the commits happen" once described a layer that did not
 * exist.</b> It said the prompt instructs the agent to commit and the autosave backstops it;
 * nothing anywhere told an agent to commit. So the autosave was the only writer, and every
 * branch the factory had pushed carried its constant message — {@code autosave: work in
 * progress} — describing nothing, on work a human is asked to review.
 *
 * <p><b>Appended by the worker rather than in the prompt builders</b>, for three reasons. A fix run's
 * prompt is built by {@code FixPrompt}, but a BUILD run's is the caller's text verbatim, so
 * there is no second place to put it that covers both. It is delivery-agnostic: it rides the
 * invocation's prompt whether the arm takes stdin or argv. And it lands AFTER the Kafka
 * hop, so it cannot push a dispatch past {@code MAX_PROMPT_CHARS} and make a record unroutable.
 *
 * <p><b>A read-only run must not receive the commit instruction</b> (M4 slice 4 review). Told to commit and to
 * describe what it changed, an agent writes the specification into the work tree, where the read-only publisher
 * discards it, and leaves {@code $SPIRE_ARTIFACT} empty: a paid run that fails as missing its artifact.
 */
final class AgentPrompts {

    private AgentPrompts() {
    }

    /**
     * A build's prompt, plus the one thing every pushing run needs the agent to do with its work. The summary is
     * the belt to the commit's braces: an agent that commits properly never needs it, and one that forgets still
     * leaves a sentence behind instead of a constant.
     */
    static String withCommitInstruction(String prompt) {
        return prompt + COMMIT_INSTRUCTION;
    }

    /** A specification or plan run's prompt: nothing about commits, only the artifact file. */
    static String withReadOnlyInstruction(String prompt) {
        return prompt + READ_ONLY_INSTRUCTION;
    }

    /**
     * Deliberately does not name a path. {@code SPIRE_SUMMARY} is exported by the agent image's
     * entrypoint, which owns where the file lives; naming the path here would put the same literal
     * in two repositories' worth of places, free to disagree.
     */
    private static final String COMMIT_INSTRUCTION = """


            ---
            Commit your work as you go. Give each commit a message that says what changed and why.

            When you have finished, write ONE line describing what you changed to the file named by
            the SPIRE_SUMMARY environment variable. Anything you leave uncommitted is committed for
            you, and that line becomes its message — without it the commit says only "work in
            progress", which tells a reviewer nothing.""";

    /** Names no path either: {@code SPIRE_ARTIFACT} is the entrypoint's, for the same reason as above. */
    private static final String READ_ONLY_INSTRUCTION = """


            ---
            The repository is read-only for this run. Read it as you need, but change no file in it
            and make no commit: anything you change there is discarded.

            When you have finished, write your result to the file named by the SPIRE_ARTIFACT
            environment variable. That file is the only thing this run returns; without it the run
            has failed.""";
}
