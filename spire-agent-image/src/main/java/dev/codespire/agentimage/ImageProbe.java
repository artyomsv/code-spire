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
     * What a probe observed.
     *
     * @param output          everything the container printed, stdout and stderr merged
     * @param handoff         file names left on {@code /handoff}, empty for a plain {@link #run}
     * @param doneWrittenLast whether {@code DONE} was the newest file on {@code /handoff}
     */
    record Result(String output, List<String> handoff, boolean doneWrittenLast) {

        public Result {
            handoff = List.copyOf(handoff);
        }

        static Result of(String output) {
            return new Result(output, List.of(), false);
        }
    }
}
