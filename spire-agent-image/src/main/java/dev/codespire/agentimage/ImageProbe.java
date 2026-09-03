package dev.codespire.agentimage;

import com.github.dockerjava.api.command.InspectImageResponse;

import java.util.List;

/**
 * How the verifier reaches an image.
 *
 * <p>A seam, so the clause LOGIC is testable without a daemon while the clause EVIDENCE still comes
 * from a real one. That split matters here: what a fake proves is that a "no git" answer becomes a
 * failure naming git — the mapping — and only a real container can prove that the question was
 * asked correctly in the first place. Both tests exist, and neither is a substitute for the other.
 */
public interface ImageProbe {

    /** The image's own configuration: entrypoint, user, labels. */
    InspectImageResponse inspect(String image);

    /** Runs a command in the image, OVERRIDING its entrypoint, and returns what it printed. */
    Result run(String image, List<String> argv);

    /**
     * Runs the image's OWN entrypoint with this argv as the harness, and the prompt on stdin.
     *
     * <p>The distinction from {@link #run} is the point of having both: those clauses are about the
     * entrypoint's behaviour, so overriding it would test nothing. This is the only probe that
     * exercises the image as a run would.
     */
    Result runAgent(String image, List<String> harnessArgv, String prompt);

    /**
     * What became of {@code DONE} on the handoff.
     *
     * <p>Three answers rather than a boolean, because "never written" and "written before the last
     * bundle" fail the same clause and call for opposite fixes — and a boolean made the report say
     * the second about an entrypoint that had done the first.
     */
    enum Done {

        /** No bundle is newer than DONE, which is what the publisher relies on. */
        WRITTEN_LAST,

        /** A bundle appeared after DONE; the publisher would drain before it existed. */
        BUNDLE_AFTER_DONE,

        /** No DONE at all; the publisher would wait for its timeout. */
        NEVER_WRITTEN
    }

    /**
     * What a probe observed.
     *
     * @param output  everything the container printed, stdout and stderr merged
     * @param started whether the container's own setup got as far as the harness. FALSE means the
     *                checker learned nothing about the image's handoff behaviour, which is not the
     *                same as learning that the behaviour is wrong — a distinction an earlier version
     *                collapsed, and it made a conforming entrypoint look like three separate defects
     * @param handoff file names left on the handoff volume, empty for a plain {@link #run}
     * @param done    what became of DONE
     */
    record Result(String output, boolean started, List<String> handoff, Done done) {

        public Result {
            handoff = List.copyOf(handoff);
        }

        static Result of(String output) {
            return new Result(output, true, List.of(), Done.NEVER_WRITTEN);
        }

        /** The probe could not get the image as far as running the harness. */
        static Result neverStarted(String output) {
            return new Result(output, false, List.of(), Done.NEVER_WRITTEN);
        }
    }
}
