package dev.codespire.contract.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import dev.codespire.contract.work.WorkRunBinding;
import dev.codespire.contract.work.WorkVerification;
import java.util.Objects;

/**
 * What a verify unit found (M4). Rides {@code cs.run-verifications}, keyed by runId, and NOT
 * {@code cs.run-results}: the item bridge stores any non-ready result there as the build's terminal payload,
 * so a verification on that topic would have replaced the later publication result (spec §4.1).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({@JsonSubTypes.Type(value = RunVerification.RunWorkVerified.class, name = "RunWorkVerified")})
public sealed interface RunVerification {
    String runId();

    record RunWorkVerified(String runId, WorkRunBinding work, WorkVerification verification) implements RunVerification {
        public RunWorkVerified {
            if (runId == null || runId.isBlank()) throw new IllegalArgumentException("A verification names its run");
            Objects.requireNonNull(work, "work");
            Objects.requireNonNull(verification, "verification");
        }
    }
}
