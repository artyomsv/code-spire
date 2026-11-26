package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.llm.PromptKind;
import dev.codespire.contract.llm.PromptTemplate;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.caps.CapRefusal;
import dev.codespire.orchestrator.caps.SpendGate;
import dev.codespire.orchestrator.policy.ReviewPolicy;
import dev.codespire.orchestrator.prompt.WorkerPromptTemplates;
import dev.codespire.orchestrator.view.TimelineBroadcaster;

import java.time.Duration;

/** The collaborators {@link ResultSagaLimitReadTest} needs and asserts nothing about; none opens a database. */
final class ResultSagaLimitReadFakes {

    private static final int MAX_ATTEMPTS = 3;
    private static final long DELAY_SECONDS = 5L;

    private ResultSagaLimitReadFakes() {
    }

    static ReviewPolicy policy() {
        return new ReviewPolicy() {
            @Override
            public int maxAttempts() {
                return MAX_ATTEMPTS;
            }

            @Override
            public Duration retryDelay(int attempt) {
                return Duration.ofSeconds(DELAY_SECONDS * attempt);
            }
        };
    }

    static TimelineBroadcaster silentTimeline() {
        return new TimelineBroadcaster() {
            @Override
            public void record(String lane, String type, String reviewId, String detail) {
            }
        };
    }

    static SpendGate uncapped() {
        return new SpendGate() {
            @Override
            public Decision decide() {
                return Decision.of(CapRefusal.allow());
            }
        };
    }

    static WorkerPromptTemplates noPrompts() {
        return new WorkerPromptTemplates() {
            @Override
            public PromptTemplate forKind(PromptKind kind, RepoRef repo) {
                return null;
            }
        };
    }
}
