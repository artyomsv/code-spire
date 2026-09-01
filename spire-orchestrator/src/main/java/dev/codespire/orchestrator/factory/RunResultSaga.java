package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

/**
 * Projects {@code cs.run-results} onto {@code factory_run}.
 *
 * <p>A saga in name only for M0 — it emits nothing further. What arrives later (the review of the
 * run's pull request, the fix chain) attaches here, which is why it is a saga rather than a
 * projection listener from the start: the shape that will be needed is cheaper to have than to
 * retrofit across every result handler.
 */
@ApplicationScoped
public class RunResultSaga {

    private static final Logger LOG = Logger.getLogger(RunResultSaga.class);

    @Inject
    FactoryRunProjection projection;

    @Incoming("run-results-in")
    public void on(RunResult result) {
        if (result == null) {
            // A poison record: the deserializer already logged it, and it is on cs.dlq.
            return;
        }
        LOG.infof("run %s: %s", result.runId(), result.getClass().getSimpleName());
        projection.apply(result);
    }
}
