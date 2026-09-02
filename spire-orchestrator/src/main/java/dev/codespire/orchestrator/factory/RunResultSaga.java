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

    @Incoming("run-results-in")
    @Blocking
    public void on(RunResult result) {
        if (result == null) {
            // A poison record: the deserializer already logged it, and it is on cs.dlq.
            return;
        }
        MDC.put(MDC_RUN_ID, result.runId());
        try {
            LOG.infof("run result %s", result.getClass().getSimpleName());
            projection.apply(result);
        } finally {
            MDC.remove(MDC_RUN_ID);
        }
    }
}
