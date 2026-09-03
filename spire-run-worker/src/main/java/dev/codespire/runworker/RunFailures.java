package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunFailureCause;
import dev.codespire.contract.event.RunResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * The one place the worker turns a failure into a {@link RunResult.RunFailed}.
 *
 * <p>Two properties have to hold of <em>every</em> failure this service emits, and both were
 * previously enforced per call site, which is another way of saying they were not enforced. The
 * retry answer comes from the cause rather than from whoever is constructing the result, and the
 * detail carries none of the run's credentials.
 *
 * <p>Splitting them across classes is how they drifted. The launcher gained both and the dispatcher
 * kept neither, so its {@code RESULT_UNPUBLISHABLE} said "not retryable" on the wire while the
 * taxonomy answered the opposite for the cause stored beside it, and its catch-all — the one that
 * receives the exception nobody has reviewed, by definition — built its detail from a raw message.
 * A shared collaborator is the only shape in which "every failure goes through one place" is a
 * statement about the worker rather than about one of its classes.
 */
@ApplicationScoped
public class RunFailures {

    private static final Logger LOG = Logger.getLogger(RunFailures.class);

    /**
     * The longest detail worth storing. Agent-influenced text reaches this field through the
     * publisher — a bundle's ref names, a rejected tree path — and {@code failure_detail} is a
     * {@code TEXT} column with no bound of its own.
     */
    private static final int MAX_DETAIL_CHARS = 8192;

    private static final String CLIPPED = "… [detail clipped]";

    @Inject
    Credentials credentials;

    /** Holds the one credential a run does not carry: the proxy password, if the URL has one. */
    @Inject
    EnterpriseEnvironmentConfig enterprise;

    /**
     * A failure whose retry answer is the cause's and whose detail carries no credential.
     *
     * <p>Usage is left UNKNOWN here, and a caller that measured it adds it with
     * {@link RunResult.RunFailed#withUsage(java.util.Map)}. Defaulting it to an empty map would
     * assert the run was free, and a failure is not a free outcome: an agent can work for an hour
     * and then have its push rejected.
     */
    public RunResult.RunFailed of(RunCommand.ExecuteRun command, String cause, String detail) {
        return new RunResult.RunFailed(command.runId(), cause,
                clip(scrubFor(command).clean(detail)),
                RunFailureCause.of(cause).isRetryable(), null);
    }

    /**
     * Every credential this run was given, decrypted only to redact it from a failure detail.
     *
     * <p>Decrypted on the failure path rather than held for the run's life: failures are rare, so
     * the plaintext lives no longer than the string it is cleaning.
     *
     * <p><b>Both credentials, and independently.</b> The model key rides the same container create
     * request as the machine account's token, so scrubbing one half of an environment and not the
     * other is a distinction nothing downstream makes. Decrypting them in one block meant a single
     * failure disarmed the scrub for both, which is the opposite of what a defence in depth should
     * do when part of it breaks.
     *
     * <p>Package-visible because the transcript needs the same scrub. The agent runs at full
     * access and writes tool output the harness relays verbatim, so a call as ordinary as
     * {@code printenv} puts the model key into a tool result — and the transcript is read by a
     * viewer. EXECUTION-LAYER.md requires credentials to be redacted from every event, artefact and
     * transcript before it leaves the worker; before this, only failure details were.
     *
     * <p>Each catch is silent about what failed. Naming the credential that would not decrypt, or
     * logging the exception carrying it, is itself a way for one to reach a log line. The
     * degradation is reported once, below, without either.
     */
    SecretScrub scrubFor(RunCommand.ExecuteRun command) {
        List<String> secrets = new ArrayList<>();
        String username = null;
        try {
            Credentials.Scm scm = credentials.scm(command.runId(), command.scmCredential());
            username = scm.readUsername();
            secrets.add(scm.readSecret());
            secrets.add(scm.writeSecret());
        } catch (RuntimeException undecryptable) {
            LOG.warnf("run %s: the machine account's credentials could not be decrypted to redact "
                    + "them; this run's failure details are unscrubbed for it", command.runId());
        }
        try {
            secrets.addAll(credentials.harnessEnv(command.runId(), command.harnessCredential()).values());
        } catch (RuntimeException undecryptable) {
            LOG.warnf("run %s: the harness credential could not be decrypted to redact it; this "
                    + "run's failure details are unscrubbed for it", command.runId());
        }
        // The deployment credential, not the run's: a corporate proxy URL may carry basic auth,
        // it is set in every container, and git and curl quote the URL they tried. Added here
        // rather than at each call site so the transcript and the failure detail are covered by
        // the one scrub that already covers both.
        enterprise.proxySecret().ifPresent(secrets::add);
        return secrets.isEmpty() ? SecretScrub.none()
                : SecretScrub.of(username, secrets.toArray(String[]::new));
    }

    /**
     * Bound a failure detail.
     *
     * <p>Package-visible so the orphan watchdog can use it. That path reports on a run it holds no
     * COMMAND for, so it cannot decrypt that run's credentials and cannot scrub them — and it has
     * no need to: its details are the runtime's own constants plus an exception class name and a
     * run id, none of which can carry one. The LENGTH bound applies regardless, because
     * {@code failure_detail} is a TEXT column with no bound of its own and the runtime's detail is
     * not text this module authors.
     */
    static String clip(String detail) {
        if (detail == null || detail.length() <= MAX_DETAIL_CHARS) {
            return detail;
        }
        return detail.substring(0, MAX_DETAIL_CHARS - CLIPPED.length()) + CLIPPED;
    }
}
