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

    @Inject SignInRuntime runtime;
    @Inject EncryptionService encryption;
    @Inject HarnessRegistry harnesses;

    @Inject @Channel("harness-sign-in-results-out")
    Emitter<org.eclipse.microprofile.reactive.messaging.Message<HarnessSignInResult>> results;

    /** Units this worker started, so a cancel can reach one that is still waiting on a person. */
    private final Map<String, SignInRuntime.Handle> running = new ConcurrentHashMap<>();

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
        // How this arm signs in is the ADAPTER's knowledge. An arm with no such flow is refused here
        // rather than by starting a container that has nothing to run.
        var flow = harnesses.forName(command.harness()).signIn();
        if (flow.isEmpty()) {
            emit(new HarnessSignInResult.Failed(command.signInId(), HarnessSignInResult.Failed.UNIT_FAILED,
                    "this harness has no subscription sign-in"));
            return;
        }
        SignInPrompt prompt = new SignInPrompt();
        SignInUnitSpec spec = new SignInUnitSpec(command.signInId(), command.image(), flow.get().command(),
                flow.get().resultPath(), EnterpriseEnvironment.NONE, 512 * MEGABYTE, 1_000_000_000L,
                64 * MEGABYTE, Duration.ofSeconds(command.maxWaitSeconds()));

        SignInRuntime.Handle handle = runtime.start(spec, prompt::accept);
        running.put(command.signInId(), handle);
        try {
            if (!awaitPrompt(prompt)) {
                // The CLI said nothing this could read. Never invent a link or a code: an operator sent
                // to a guessed address is worse than one told the sign-in did not start.
                emit(new HarnessSignInResult.Failed(command.signInId(), HarnessSignInResult.Failed.UNIT_FAILED,
                        "the sign-in tool printed no link and code this build could read"));
                return;
            }
            Duration life = prompt.expiresIn().orElse(Duration.ofSeconds(command.maxWaitSeconds()));
            emit(new HarnessSignInResult.Prompted(command.signInId(), prompt.link(), prompt.code(),
                    Instant.now().plus(life)));

            Optional<Integer> exit = runtime.awaitExit(handle, Duration.ofSeconds(command.maxWaitSeconds()));
            if (exit.isEmpty()) {
                runtime.cancel(handle);
                emit(new HarnessSignInResult.Failed(command.signInId(), HarnessSignInResult.Failed.EXPIRED,
                        "nobody approved the code before it expired"));
                return;
            }
            emit(collect(command.signInId(), handle, exit.get(), flow.get().resultPath()));
        } finally {
            running.remove(command.signInId());
            // ALWAYS, including after a failure. What this removes is a credential the operator just
            // created; leaving it in a stopped container leaves it readable by anything that can reach
            // the daemon, for as long as nobody notices.
            runtime.destroy(handle);
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
            return new HarnessSignInResult.Failed(signInId, HarnessSignInResult.Failed.EXPIRED,
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

    private void cancel(HarnessSignInCommand.Cancel command) {
        SignInRuntime.Handle handle = running.get(command.signInId());
        if (handle == null) {
            // A cancel for a unit this worker does not hold is not a fault: another instance may hold
            // it, or it may already have ended. Saying so beats inventing a failure for the screen.
            LOG.infof("no local sign-in unit for %s to cancel", command.signInId());
            return;
        }
        runtime.cancel(handle);
        emit(new HarnessSignInResult.Failed(command.signInId(), HarnessSignInResult.Failed.CANCELLED, command.reason()));
    }

    private boolean awaitPrompt(SignInPrompt prompt) {
        Instant deadline = Instant.now().plus(PROMPT_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (prompt.complete()) return true;
            try { Thread.sleep(100); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        }
        return prompt.complete();
    }

    private void emit(HarnessSignInResult result) {
        results.send(Message.of(result).addMetadata(
                io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata.<String>builder()
                        .withKey(result.signInId()).build()));
    }
}
