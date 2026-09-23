package dev.codespire.runworker;

import dev.codespire.contract.command.HarnessSignInCommand;
import dev.codespire.contract.event.HarnessSignInResult;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.runtime.EnterpriseEnvironment;
import dev.codespire.runtime.SignInRuntime;
import dev.codespire.runtime.SignInUnitSpec;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs one trusted sign-in unit per command, and reports what the operator must do (M3.5 part F).
 *
 * <p>The unit is not an agent run and deliberately shares nothing with one: its own topic, its own
 * runtime port, no repository, no workspace, no prompt. Only the KEK is shared, because the result has
 * to reach the orchestrator and nothing else may read it on the way.
 */
@ApplicationScoped
public class HarnessSignInWorker {

    private static final Logger LOG = Logger.getLogger(HarnessSignInWorker.class);

    /**
     * How long to wait for the CLI to print the link and the code.
     *
     * <p>Short, because this is a local process starting up rather than a person deciding. If it has
     * said nothing by now the output has changed shape or the unit is broken, and either way the
     * operator is better told than left watching a spinner.
     */
    private static final Duration PROMPT_TIMEOUT = Duration.ofSeconds(60);

    private static final long MEGABYTE = 1024L * 1024L;

    /**
     * How old a sign-in unit must be before the sweep treats it as abandoned.
     *
     * <p>Comfortably past the longest a sign-in may run — the orchestrator's ceiling is fourteen
     * minutes and the vendor's code lasts fifteen — so a unit this old is one nobody is coming back to.
     */
    private static final Duration ABANDONED_AFTER = Duration.ofMinutes(30);

    @Inject SignInRuntime runtime;
    @Inject EncryptionService encryption;
    @Inject HarnessRegistry harnesses;

    /** Keyed and AWAITABLE: a Record send answers a stage, which a bare Message send does not. */
    @Inject @Channel("harness-sign-in-results-out")
    Emitter<io.smallrye.reactive.messaging.kafka.Record<String, HarnessSignInResult>> results;

    /** How long to wait for the broker before calling a result undelivered. */
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "spire.run.result-ack-seconds")
    long ackSeconds;

    /**
     * Units this worker started, keyed by sign-in id and CLAIMED before the container exists.
     *
     * <p>The claim is what makes a duplicate Start harmless. Commands are handled unordered, and a
     * redelivery used to create a second container and overwrite this entry — after which the exit of
     * one unit removed the other's only handle, leaving a live container holding a credential that
     * nothing was tracking.
     */
    private final Map<String, SignInRuntime.Handle> running = new ConcurrentHashMap<>();

    /** A claim with no container yet, so a duplicate loses the race even before anything is created. */
    private static final SignInRuntime.Handle CLAIMED = new SignInRuntime.Handle("claimed", "claimed");

    /**
     * Cancels that arrived before the Start they cancel.
     *
     * <p>Unordered handling means a Cancel can be processed while its Start is still inside the
     * runtime, or even before it. Without this the cancel found nothing, said so, and the Start then
     * cheerfully created the unit anyway — so the operator's cancel did nothing and a container sat
     * waiting on a code nobody was going to type.
     */
    private final Map<String, java.time.Instant> cancelled = new ConcurrentHashMap<>();

    @Incoming("harness-sign-in-commands-in")
    @Blocking(ordered = false)
    public java.util.concurrent.CompletionStage<Void> onCommand(Message<HarnessSignInCommand> message) {
        HarnessSignInCommand command = message.getPayload();
        try {
            switch (command) {
                case HarnessSignInCommand.Start start -> start(start);
                case HarnessSignInCommand.Cancel cancel -> cancel(cancel);
            }
        } catch (RuntimeException failure) {
            LOG.errorf(failure, "harness sign-in %s could not be handled", command.signInId());
            emit(new HarnessSignInResult.Failed(command.signInId(),
                    HarnessSignInResult.Failed.UNIT_FAILED, failure.getClass().getSimpleName()));
        }
        return message.ack();
    }

    private void start(HarnessSignInCommand.Start command) {
        // One unit per sign-in id, claimed before anything is created. A redelivered Start loses here.
        if (running.putIfAbsent(command.signInId(), CLAIMED) != null) {
            LOG.infof("sign-in %s is already running here; ignoring a repeated start", command.signInId());
            return;
        }
        // Counted from the operator's press, not from delivery: a start can be replayed or re-sent. One
        // whose wait has run out, or that carries no press time at all (sent before times existed, so
        // only a replay), opens nothing: its code could never be typed (review of PR #168).
        //
        // And it SAYS nothing. "Too late to start another unit" is not "this sign-in expired": with two
        // workers, a late copy reaching one of them would otherwise fail a sign-in the other is running
        // and the operator can still approve. Ending a sign-in nobody runs is the orchestrator's job, and
        // it does it from the row's own clock (HarnessSignIns.resendUnclaimed).
        Duration wait = command.requestedAt() == null ? Duration.ZERO : command.remainingWait(Instant.now());
        if (wait.compareTo(PROMPT_TIMEOUT) <= 0) {
            LOG.infof("not starting sign-in %s: requested at %s, and too little of its wait is left",
                    command.signInId(), command.requestedAt());
            running.remove(command.signInId());
            return;
        }
        if (cancelled.remove(command.signInId()) != null) {
            // The cancel got here first. Creating the unit now would mean the operator's cancel did
            // nothing and a container waited out its ceiling on a code nobody would type.
            running.remove(command.signInId());
            emit(new HarnessSignInResult.Failed(command.signInId(), HarnessSignInResult.Failed.CANCELLED,
                    "the sign-in was cancelled before it started"));
            return;
        }

        // How this arm signs in is the ADAPTER's knowledge. An arm with no such flow is refused here
        // rather than by starting a container that has nothing to run.
        var flow = harnesses.forName(command.harness()).signIn();
        if (flow.isEmpty()) {
            running.remove(command.signInId());
            emit(new HarnessSignInResult.Failed(command.signInId(), HarnessSignInResult.Failed.UNIT_FAILED,
                    "this harness has no subscription sign-in"));
            return;
        }
        SignInPrompt prompt = new SignInPrompt(flow.get().verificationHost());
        SignInUnitSpec spec = new SignInUnitSpec(command.signInId(), command.image(), flow.get().command(),
                flow.get().resultPath(), EnterpriseEnvironment.NONE, 512 * MEGABYTE, 1_000_000_000L,
                64 * MEGABYTE, wait);

        SignInRuntime.Handle handle;
        try { handle = runtime.start(spec, prompt::accept); }
        catch (SignInRuntime.AlreadyClaimed taken) {
            running.remove(command.signInId());
            adopt(command.signInId(), taken);
            return;
        }
        catch (RuntimeException failed) { running.remove(command.signInId()); throw failed; }
        running.put(command.signInId(), handle);
        if (cancelled.remove(command.signInId()) != null) {
            // It arrived while the container was being created — the window the claim alone cannot
            // cover, because there was no handle to stop until now.
            runtime.cancel(handle);
        }
        try {
            if (!awaitPrompt(prompt)) {
                // The CLI said nothing this build can read, or said two different things. Never invent a
                // link or a code, and never pick between two: an operator sent to a guessed address is
                // worse than one told the sign-in did not start, because they will type their account
                // credentials into whatever is there.
                emit(new HarnessSignInResult.Failed(command.signInId(), HarnessSignInResult.Failed.UNIT_FAILED,
                        prompt.ambiguous()
                                ? "the sign-in tool printed more than one address or code, so none was used"
                                : "the sign-in tool printed no link and code this build could read"));
                return;
            }
            // The EFFECTIVE deadline: the sooner of what the vendor promised and what this worker was
            // given. The screen counted down the vendor's figure while the worker waited on its own, so
            // it could show a minute remaining on a unit that had already been destroyed.
            Duration budget = wait;
            Duration life = prompt.expiresIn().filter(vendor -> vendor.compareTo(budget) < 0).orElse(budget);
            emit(new HarnessSignInResult.Prompted(command.signInId(), prompt.link(), prompt.code(),
                    Instant.now().plus(life)));

            SignInRuntime.Exit exit = runtime.awaitExit(handle, wait);
            if (!(exit instanceof SignInRuntime.Exit.Observed observed)) {
                runtime.cancel(handle);
                boolean fault = exit instanceof SignInRuntime.Exit.Unobservable;
                emit(new HarnessSignInResult.Failed(command.signInId(),
                        fault ? HarnessSignInResult.Failed.UNIT_FAILED : HarnessSignInResult.Failed.EXPIRED,
                        fault ? "the sign-in could not be watched to the end"
                              : "nobody approved the code before it expired"));
                return;
            }
            HarnessSignInResult collected = collect(command.signInId(), handle, observed.code(), flow.get().resultPath());
            if (!emit(collected) && collected instanceof HarnessSignInResult.Completed) {
                // The credential reached nobody, and the container holding the only other copy is about
                // to be destroyed below. Destroying it anyway is the deliberate choice: a lost sign-in
                // costs the operator a fresh sign-in, while a credential left in a stopped container is
                // readable by anything that can reach the daemon until somebody notices.
                //
                // A second send is attempted, and it is NOT claimed to arrive. It travels the same
                // broker path that has just failed, so in the outage this exists for it fails too.
                //
                // What the operator sees depends on when the outage began: a row that reached PROMPTED
                // counts down and expires visibly, while one that never did stays PENDING with no
                // countdown, because the prompt is the only thing that writes an expiry. Both are
                // cleared by cancelling, which also needs the broker back. UNVERIFIED.md A5.
                LOG.errorf("sign-in %s completed but could not be delivered; the credential is discarded"
                        + " and the row stays open until the operator cancels it", command.signInId());
                emit(new HarnessSignInResult.Failed(command.signInId(), HarnessSignInResult.Failed.UNIT_FAILED,
                        "the sign-in finished but could not be delivered; start it again"));
            }
        } finally {
            running.remove(command.signInId());
            // ALWAYS, including after a failure. What this removes is a credential the operator just
            // created; leaving it in a stopped container leaves it readable by anything that can reach
            // the daemon, for as long as nobody notices.
            if (!runtime.destroy(handle)) {
                // NOT the same as "it is gone". An unconfirmed removal means a credential may still be
                // in a container, and the sweep below is what eventually clears it.
                LOG.errorf("sign-in %s: its unit could not be confirmed destroyed", command.signInId());
            }
        }
    }

    /**
     * Reads what the unit wrote and seals it for the orchestrator.
     *
     * <p>The bytes are sealed here and never parsed beyond the one field that has been measured. What
     * else the vendor puts in that file has not been seen by this build, and a worker that picked
     * fields out of it would be guessing at a shape nobody has measured.
     */
    private HarnessSignInResult collect(String signInId, SignInRuntime.Handle handle, int exit, String resultPath) {
        Optional<byte[]> written = runtime.result(handle, resultPath);
        if (exit != 0 || written.isEmpty()) {
            // The unit ENDED and wrote nothing usable. That is a failure of the sign-in, not a person
            // who ran out of time, and calling it an expiry sent the operator to try again faster.
            return new HarnessSignInResult.Failed(signInId, HarnessSignInResult.Failed.UNIT_FAILED,
                    "the sign-in ended without a credential (exit " + exit + ")");
        }
        String body = new String(written.get(), StandardCharsets.UTF_8);
        String mode = SignInAuthMode.of(body);
        if (mode == null) {
            return new HarnessSignInResult.Failed(signInId, HarnessSignInResult.Failed.UNIT_FAILED,
                    "the sign-in file did not say which mode it is");
        }
        return new HarnessSignInResult.Completed(signInId,
                encryption.encryptString(body, HarnessSignInResult.sealedAad(signInId)), mode,
                SignInAuthMode.identity(body));
    }

    /**
     * What to do when the daemon says somebody else holds this sign-in's unit: NOTHING.
     *
     * <p>An earlier version acted on the container's state, and the state cannot carry that decision.
     * RUNNING does not prove a live owner — a worker can die while its container keeps waiting. STOPPED
     * does not prove a dead one — the owner has a normal window after the unit exits in which it copies
     * the credential out, seals it and publishes it, and destroying the unit there destroys the result
     * and races a failure against the operator's own success.
     *
     * <p>So a duplicate leaves the unit entirely alone. A genuine orphan is closed by the age-fenced
     * sweep below, which is the only place that can tell the difference: nothing legitimate outlives
     * its own wait by half an hour.
     */
    private void adopt(String signInId, SignInRuntime.AlreadyClaimed taken) {
        LOG.infof("sign-in %s is already held on this daemon (running=%s); leaving it to its owner",
                signInId, taken.existingIsRunning());
    }

    /**
     * Stops a sign-in, wherever its unit is.
     *
     * <p>Three cases, and the first version handled only one. A unit this worker started is in its map.
     * A unit ANOTHER instance started is not — but it is on the same daemon, so it is discoverable by
     * the label it carries. And a cancel that arrives before its own Start has no unit at all yet, so
     * the intent is remembered and the Start refuses to create one.
     */
    private void cancel(HarnessSignInCommand.Cancel command) {
        SignInRuntime.Handle handle = running.get(command.signInId());
        if (handle == null || handle == CLAIMED) {
            handle = runtime.discover(Duration.ZERO).stream()
                    .filter(found -> found.unitId().equals(command.signInId()))
                    .findFirst().orElse(null);
        }
        if (handle == null) {
            // Remembered rather than dismissed: the Start may still be on its way, and a cancel that
            // said "nothing to do" let it create the unit the operator had just cancelled.
            cancelled.put(command.signInId(), java.time.Instant.now());
        } else {
            runtime.cancel(handle);
        }
        emit(new HarnessSignInResult.Failed(command.signInId(), HarnessSignInResult.Failed.CANCELLED, command.reason()));
    }

    private boolean awaitPrompt(SignInPrompt prompt) {
        Instant deadline = Instant.now().plus(PROMPT_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            // Ambiguity does not improve by waiting, and waiting out the whole timeout for a decision
            // already made just keeps a container and an operator hanging.
            if (prompt.ambiguous()) return false;
            if (prompt.complete()) return true;
            try { Thread.sleep(100); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        }
        return prompt.complete();
    }

    /**
     * Publishes, and waits for the broker to say so.
     *
     * @return true when it was acknowledged. An answer rather than {@code void}, because the caller is
     *     about to destroy the only other copy of what it just sent.
     */
    private boolean emit(HarnessSignInResult result) {
        try {
            results.send(io.smallrye.reactive.messaging.kafka.Record.of(result.signInId(), result))
                    .toCompletableFuture()
                    .get(ackSeconds, java.util.concurrent.TimeUnit.SECONDS);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException | java.util.concurrent.ExecutionException
                 | java.util.concurrent.TimeoutException undelivered) {
            LOG.errorf(undelivered, "sign-in %s: a result could not be published", result.signInId());
            return false;
        }
    }

    /**
     * Destroys sign-in units nobody is coming back for.
     *
     * <p>A {@code finally} covers an exception; it does not cover the process dying, and what survives
     * that is a container holding somebody's account credential. The run arm's watchdog looks for run
     * units and will never see one of these, so this sweep is their only recovery.
     *
     * <p>Bounded by AGE, not by this process's own map. A second instance's live unit is not in this
     * one's map either, and destroying it would take the code out from under an operator halfway
     * through approving it. No live unit can outlive the wait its command was given.
     */
    @io.quarkus.scheduler.Scheduled(every = "${spire.harness-sign-in.sweep-interval:5m}",
            concurrentExecution = io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP)
    void destroyAbandonedUnits() {
        for (SignInRuntime.Handle handle : runtime.discover(ABANDONED_AFTER)) {
            if (running.containsKey(handle.unitId())) continue;
            if (runtime.destroy(handle)) {
                LOG.infof("destroyed an abandoned sign-in unit for %s", handle.unitId());
                // The row is closed too. Deleting the container alone left the operator watching a
                // sign-in that nothing would ever report on — and this sweep is the only thing that can
                // safely call a unit abandoned, because nothing legitimate outlives its wait this far.
                emit(new HarnessSignInResult.Failed(handle.unitId(), HarnessSignInResult.Failed.UNIT_FAILED,
                        "this sign-in was abandoned and has been cleaned up; start it again"));
            }
        }
        // A cancel whose Start never arrived would otherwise be remembered for ever. Past the window a
        // Start could still be delivered in, it is nothing but a leak.
        cancelled.values().removeIf(at -> at.isBefore(java.time.Instant.now().minus(ABANDONED_AFTER)));
    }
}
