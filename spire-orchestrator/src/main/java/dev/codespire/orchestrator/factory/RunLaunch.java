package dev.codespire.orchestrator.factory;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.orchestrator.pipeline.BrokerAckFailure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Puts a built run command on the bus, and records what happened when it could not.
 *
 * <p><b>Extracted because it is about to have a second caller, and it is the half that must not be
 * written twice.</b> The REST endpoint owned all of this inline. The {@code /fix} path needs the
 * same three outcomes, and re-implementing them would put two readings of "did the record land?"
 * in the tree — which is the shape this project has already paid for once, when two credential
 * scrubbers diverged and the weaker one ran in the container holding the write token. The
 * ASSEMBLY of a command is genuinely different per caller and stays with each; the publish and its
 * fault classification are identical and live here.
 *
 * <p><b>Answers an outcome rather than throwing.</b> The REST caller turns each into a 503 with its
 * own wording and the saga turns each into a note, so the shared code cannot pick the exception —
 * and a shared helper that threw a {@code ServerErrorException} would drag JAX-RS into a saga.
 *
 * <p>The row is already written by the time this runs. That ordering is the caller's, deliberately:
 * a run must never exist on the bus without a row, so every caller writes first and launches
 * second, and this class only ever UPDATES a row it can assume exists.
 */
@ApplicationScoped
public class RunLaunch {

    private static final Logger LOG = Logger.getLogger(RunLaunch.class);

    /** Stored on the row, which a viewer reads; the broker's own exception text goes to the log. */
    static final String DISPATCH_FAILED_DETAIL =
            "the broker did not acknowledge the command; retry the same request";

    @Inject
    RunCommandEmitter emitter;

    @Inject
    FactoryRunProjection projection;

    /**
     * What became of a command handed to the broker.
     *
     * <p>Sealed with no predicates on it on purpose. It carried an {@code isReArmable()} default
     * that nothing in production ever called — both callers switch over the three cases, which is
     * what sealing buys — while its test asserted the predicate agreed with the type it was
     * derived from. That is a guard over a restatement, and the next author would have had to read
     * it before learning it protected nothing. The three javadocs below say which shape is safe to
     * retry; the exhaustive switch makes the compiler enforce that a fourth case is handled.
     */
    public sealed interface Outcome permits Dispatched, DefiniteMiss, Uncertain {
    }

    /** The broker acknowledged it. The run is the worker's problem now. */
    public record Dispatched() implements Outcome {
    }

    /**
     * The record never reached a partition, so the run definitely did not start.
     *
     * <p>The row stays and says why — deleting it would leave no record of the attempt at all — and
     * this shape IS re-armable, so an identical retry starts the run.
     */
    public record DefiniteMiss(IllegalStateException cause) implements Outcome {
    }

    /**
     * Nobody knows whether the record landed, so nothing is retried until somebody does.
     *
     * <p>This outcome also takes every fault the ack helper could not classify. A caller's wording
     * must therefore be true of a record that was never serialized as well as of one sitting on a
     * partition — which is why the row's own detail says "dispatched", never "published".
     */
    public record Uncertain(IllegalStateException cause) implements Outcome {
    }

    /**
     * Publish the command, and on failure record the row state its outcome implies.
     *
     * <p>Caught at {@link IllegalStateException}, not at {@link BrokerAckFailure}, and the
     * difference matters: narrowing it let any other publish fault escape with the row left
     * {@code queued}, so a run nobody will start sat looking as though it were about to. Anything
     * that is not a classified ack failure counts as AMBIGUOUS, because a fault we cannot read tells
     * us nothing about whether the record left — which is the whole rule here.
     */
    public Outcome launch(RunCommand.ExecuteRun command) {
        String runId = command.runId();
        try {
            emitter.dispatch(command);
            return new Dispatched();
        } catch (IllegalStateException e) {
            if (e instanceof BrokerAckFailure ack && !ack.mayHaveLanded()) {
                LOG.errorf(e, "run %s was recorded but the broker refused its dispatch outright", runId);
                projection.dispatchFailed(runId, DISPATCH_FAILED_DETAIL);
                return new DefiniteMiss(e);
            }
            LOG.errorf(e, "run %s was recorded and its dispatch attempted, but no acknowledgement came"
                    + " back; whether it is running is unknown until its result arrives or an operator"
                    + " says", runId);
            projection.dispatchUncertain(runId, uncertainDetail(runId));
            return new Uncertain(e);
        }
    }

    /**
     * What the uncertain row itself says, and deliberately not the phrasing of the definite miss.
     *
     * <p>"Retry the same request" is the wrong instruction here and the expensive one: the record
     * may already be on the topic, so a retry is how a second agent ends up on the branch.
     *
     * <p>Per-run rather than a constant, because it names the endpoint. This is the only one of the
     * four messages about this condition that survives a page reload — the 503, the 409 and the
     * attention row are all transient — so a detail ending "resolve it explicitly" with no address
     * left the durable one as the least useful.
     *
     * <p>Phrased as the consequence rather than as an order. "Do NOT retry" is an imperative on a
     * state row, and it stops being true the day a resolution UI or a reconciler exists.
     */
    static String uncertainDetail(String runId) {
        return "the command was dispatched and never acknowledged; whether it is running is unknown."
                + " A retry would publish a second command. If it started, its result will resolve this"
                + " row; otherwise POST {\"neverRan\": true} to " + resolutionPath(runId);
    }

    /** Named once, so the four messages about this condition cannot address it differently. */
    static String resolutionPath(String runId) {
        return "/api/runs/" + runId + "/dispatch-resolution";
    }
}
