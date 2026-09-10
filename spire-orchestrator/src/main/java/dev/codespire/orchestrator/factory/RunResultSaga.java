package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunResult;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

/**
 * Projects each run result onto the {@code factory_run} read model. Blocking, like every other
 * consumer here: the projection is a JDBC write, and a JDBC write on the event loop is the class
 * of stall this codebase has already paid for once.
 */
@ApplicationScoped
public class RunResultSaga {

    private static final Logger LOG = Logger.getLogger(RunResultSaga.class);

    private static final String MDC_RUN_ID = "runId";

    @Inject
    FactoryRunProjection projection;

    @Inject
    RunCharges charges;

    @Inject
    RunCredentialFeedback credentials;

    @Inject
    FactoryPullRequests pullRequests;

    @Incoming("run-results-in")
    @Blocking
    public void on(RunResult result) {
        if (result == null) {
            // A poison record. Dropped, not dead-lettered: returning normally ACKS the record,
            // and cs.dlq is reached by a nack. The deserializer already logged it at ERROR, which
            // is the only trace there will be — said plainly because "it is on cs.dlq" sent a
            // reader to a screen that would be empty.
            return;
        }
        MDC.put(MDC_RUN_ID, result.runId());
        try {
            LOG.infof("run result %s", result.getClass().getSimpleName());
            projection.apply(result);
            // AFTER the projection, deliberately. The run's outcome is the fact an operator is
            // waiting on; the ledger write is best-effort and says so if it fails, so ordering it
            // first would let a ledger outage delay a terminal status that is already known.
            charges.record(result);
            credentials.reactTo(result);
            // LAST, and after the projection on purpose: it reads the row the projection just
            // wrote, it talks to a forge, and it must not delay the terminal status an operator is
            // waiting on. It records its own failures and throws none of them back here.
            pullRequests.propose(result);
        } finally {
            MDC.remove(MDC_RUN_ID);
        }
    }
}
