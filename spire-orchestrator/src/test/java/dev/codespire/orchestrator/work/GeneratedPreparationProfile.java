package dev.codespire.orchestrator.work;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * The generated-preparation path switched on (M4 slice 3, decision 1).
 *
 * <p>One class for every test of that path: each distinct profile restarts Quarkus in the service-test
 * job, so a second profile would cost a second start. Slice 11 deletes the flag and this with it.
 */
public class GeneratedPreparationProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("spire.work.generated-preparation", "true");
    }
}
