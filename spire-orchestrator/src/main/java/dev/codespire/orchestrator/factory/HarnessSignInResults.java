package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.HarnessSignInResult;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

/**
 * What the trusted sign-in unit reported, applied to the sign-in a person is watching (M3.5 part F).
 *
 * <p>A thin saga on purpose: every decision is in {@link HarnessSignIns}, where it is reachable from a
 * test without a broker. This class exists to take a record off a topic and hand it over.
 */
@ApplicationScoped
public class HarnessSignInResults {

    private static final Logger LOG = Logger.getLogger(HarnessSignInResults.class);

    @Inject HarnessSignIns signIns;

    @Incoming("harness-sign-in-results-in")
    @Blocking(ordered = false)
    public java.util.concurrent.CompletionStage<Void> onResult(Message<HarnessSignInResult> message) {
        HarnessSignInResult result = message.getPayload();
        try {
            switch (result) {
                case HarnessSignInResult.Prompted prompted -> signIns.prompted(prompted);
                case HarnessSignInResult.Completed completed -> signIns.completed(completed);
                case HarnessSignInResult.Failed failed -> signIns.failed(failed);
            }
        } catch (RuntimeException failure) {
            // Never rethrow: a sign-in that cannot be recorded must not stall the topic, and the
            // screen shows a stuck PENDING row, which is the truth.
            LOG.errorf(failure, "sign-in %s could not be recorded", result.signInId());
        }
        return message.ack();
    }
}
