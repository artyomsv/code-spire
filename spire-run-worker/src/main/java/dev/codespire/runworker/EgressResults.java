package dev.codespire.runworker;

import dev.codespire.contract.event.RunResult;
import dev.codespire.runtime.EgressAttempt;
import dev.codespire.runtime.RunHandle;
import dev.codespire.runtime.RunRuntime;
import dev.codespire.secrets.SecretScrub;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * What a unit's egress proxy saw, carried onto the run's result.
 *
 * <p>Every result carries it, whatever the outcome: in report mode it is how the hosts a harness really calls are
 * measured, and in enforce mode a refused host beside a delivered run is still something an operator should see. A
 * refusal decides the failure cause only for a run that failed anyway (see {@link RunLauncher}); a run that finished
 * or pushed keeps its outcome.
 */
final class EgressResults {

    private static final Logger LOG = Logger.getLogger(EgressResults.class);

    /** How many refused hosts a failure detail names; the rest are on the result. */
    private static final int HOSTS_NAMED = 5;

    private EgressResults() {
    }

    /**
     * Read once the agent is gone. A host that the scrub would change is dropped rather than reported: the proxy log
     * is the agent's text, and a "host" that carries a credential is not a host.
     *
     * <p>A failed read is not a run fault: the result is decided without it and the failure is logged.
     */
    static List<RunResult.EgressAttempt> read(RunRuntime runtime, RunHandle handle, SecretScrub scrub) {
        List<EgressAttempt> seen;
        try {
            seen = runtime.egressOf(handle);
        } catch (RuntimeException e) {
            LOG.warnf("run %s: the egress proxy's record could not be read (%s); the result carries none",
                    handle.runId(), e.getClass().getSimpleName());
            return List.of();
        }
        List<RunResult.EgressAttempt> attempts = new ArrayList<>();
        for (EgressAttempt attempt : seen) {
            if (scrub.clean(attempt.host()).equals(attempt.host())) {
                attempts.add(new RunResult.EgressAttempt(attempt.host(), attempt.port(), attempt.allowed()));
            }
        }
        return List.copyOf(attempts);
    }

    /** The hosts the proxy refused, in the order it saw them, each once. */
    static List<String> refusedHosts(List<RunResult.EgressAttempt> egress) {
        return egress.stream().filter(attempt -> !attempt.allowed()).map(RunResult.EgressAttempt::host).distinct().toList();
    }

    /** The failure detail of a run that failed after the proxy refused a host: the host first, then what failed. */
    static String blockedDetail(List<String> refused, String agentDetail) {
        String named = String.join(", ", refused.subList(0, Math.min(HOSTS_NAMED, refused.size())));
        String more = refused.size() > HOSTS_NAMED ? " and " + (refused.size() - HOSTS_NAMED) + " more" : "";
        return "the egress proxy refused " + named + more + ", which the harness does not declare; then: " + agentDetail;
    }

    /** The same result carrying the egress, whatever kind it is. */
    static RunResult attach(RunResult result, List<RunResult.EgressAttempt> egress) {
        if (egress.isEmpty()) {
            return result;
        }
        return switch (result) {
            case RunResult.RunFinished finished -> finished.withEgress(egress);
            case RunResult.RunFailed failed -> failed.withEgress(egress);
            case RunResult.RunWorkReady ready -> ready.withEgress(egress);
            default -> result;
        };
    }
}
