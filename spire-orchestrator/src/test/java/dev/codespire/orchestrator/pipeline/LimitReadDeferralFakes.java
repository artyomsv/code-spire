package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.IntegrationEvent.ContextAssembled;
import dev.codespire.contract.event.IntegrationEvent.ReviewFailed;
import dev.codespire.orchestrator.settings.AppSettingRepository;

import java.util.Optional;

/** {@link LimitReadDeferral} fakes for saga unit tests, which have no database: the real one writes rows. */
final class LimitReadDeferralFakes {

    private LimitReadDeferralFakes() {
    }

    /** For a suite whose limit read always succeeds: closing the window writes nothing. */
    static LimitReadDeferral none() {
        return new LimitReadDeferral() {
            @Override
            Optional<ReviewFailed> defer(ContextAssembled e, AppSettingRepository.ReadFailed fault) {
                throw new AssertionError("this suite's limit read never fails", fault);
            }

            @Override
            void recovered(String reviewId) {
            }
        };
    }
}
