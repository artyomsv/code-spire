package dev.codespire.runtime;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Runs one trusted container so a person can sign a harness in (M3.5 part F).
 *
 * <p>A separate port from {@link RunRuntime} rather than four more methods on it. {@code RunRuntime} is
 * the path that spends money and holds the push credential; every arm that implements it must get all of
 * salvage, orphan discovery, steering and cancellation right. This needs none of that, and widening that
 * interface would oblige every future arm to implement a screen's mechanism before it could run a single
 * agent.
 *
 * <p><b>Only one arm implements it today, and that is stated rather than hidden:</b> Docker. A
 * Kubernetes deployment needs its own, and until it has one, subscription sign-in is unavailable there
 * and says so. That is the same boundary RUN-TOPOLOGY already draws for the run unit.
 */
public interface SignInRuntime {

    RuntimeType type();

    /**
     * Starts the unit and streams its output, line by line, as it appears.
     *
     * <p>The stream is how the operator learns what to do: the link and the code are printed while the
     * unit is still waiting, and a caller that only read the output at the end would show them nothing
     * until it was too late to use.
     *
     * <p>The credential never travels this way. It is written to the spec's result path and read with
     * {@link #result}, because output reaches the daemon's logs and stays there.
     */
    /**
     * @throws AlreadyClaimed when a unit for this sign-in already exists. The claim is the DAEMON's, not
     *     the caller's: looking first and then creating is two operations, and two workers handling the
     *     same redelivered command both find nothing and both create one.
     */
    Handle start(SignInUnitSpec spec, Consumer<String> lines);

    /**
     * Another unit already holds this sign-in's identity.
     *
     * <p>Carries whether that unit is still running, because the two cases need opposite answers. A
     * live one has an owner that will report it, and stopping it would take the code out from under an
     * operator mid-approval. A stopped one is an orphan of a worker that died: nobody will ever report
     * it, so the caller must end the sign-in rather than leave a row open for ever.
     */
    final class AlreadyClaimed extends RuntimeException {
        private final transient Handle existing;
        private final boolean running;

        public AlreadyClaimed(Handle existing, boolean running) {
            super("a sign-in unit for " + existing.unitId() + " already exists");
            this.existing = existing;
            this.running = running;
        }

        public Handle existing() { return existing; }

        public boolean existingIsRunning() { return running; }
    }

    /**
     * Waits for the unit to exit.
     *
     * <p>Three outcomes, not two. An earlier version had "a code or nothing", which reported a
     * disconnected daemon as a person who did not answer in time — and those send different people to
     * different places: one waits for the operator, the other is an outage nobody would go looking for.
     */
    sealed interface Exit {
        /** The unit ended and the runtime read its status. */
        record Observed(int code) implements Exit {}
        /** It was still running when the wait elapsed. Nobody approved. */
        record StillRunning() implements Exit {}
        /** The runtime could not observe it at all. A fault, not a timeout. */
        record Unobservable(String detail) implements Exit {}
    }

    Exit awaitExit(Handle handle, Duration within);

    /**
     * Reads the file the unit wrote, copied straight out of the stopped container.
     *
     * <p>The path is passed rather than remembered: the caller holds the spec that declared it, and an
     * arm that kept its own copy would hold state a restart loses silently.
     *
     * @return the bytes, or empty if the unit wrote nothing there
     */
    Optional<byte[]> result(Handle handle, String resultPath);

    /** Stops a unit nobody is going to answer. Safe to call on one that has already exited. */
    void cancel(Handle handle);

    /**
     * Removes the container and anything it wrote.
     *
     * <p>Always called, including after a failure, because what it destroys is a credential the operator
     * has just created. Leaving it in a stopped container means leaving it readable by anything that can
     * reach the daemon, for as long as nobody notices.
     *
     * @return true only when the unit is CONFIRMED gone — removed, or already absent. False means the
     *     arm could not establish that, and the caller must say so loudly rather than move on: a
     *     swallowed removal failure reads exactly like a successful one while the credential is still
     *     sitting in a stopped container's filesystem.
     */
    boolean destroy(Handle handle);

    /**
     * Every sign-in unit this deployment left behind, found by its label.
     *
     * <p>{@link #destroy} in a caller's {@code finally} covers an exception. It does not cover the
     * process dying, which is the case that matters most here, because what survives is a container
     * holding somebody's account credential. The run arm has the same problem and solves it the same
     * way — discovery by label — but it looks for run units and will never see one of these.
     *
     * @param olderThan how long a unit must have existed before it counts as abandoned. Required, not
     *     optional: a sweep that took every unit it found would destroy a SECOND instance's live
     *     sign-in, pulling the code out from under an operator who is halfway through approving it. No
     *     live unit can outlive the wait its command was given, so age is the one signal that is true
     *     across instances.
     */
    /**
     * The unit carrying this sign-in's identity, whatever its age or state.
     *
     * <p>Separate from {@link #discover} on purpose: that one is age-fenced for the orphan sweep, and
     * using it to answer "who holds this sign-in" hid a container created in the current second — the
     * very one whose name had just caused a conflict.
     */
    Optional<Handle> find(String unitId);

    List<Handle> discover(Duration olderThan);

    /** What an arm hands back so the caller can name the unit again. Opaque by design. */
    record Handle(String unitId, String reference) {
        public Handle {
            if (unitId == null || unitId.isBlank()) throw new IllegalArgumentException("a handle needs its unitId");
            if (reference == null || reference.isBlank()) throw new IllegalArgumentException("a handle needs the arm's own reference");
        }
    }
}
