package dev.codespire.runtime.k8s;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class UnitRefusalsTest {

    private static final String RUN = UnitNamesTest.RUN;

    @Test
    void aProxiedReviewUnitIsNotRefused() {
        assertEquals(Optional.empty(), UnitRefusals.of(TestUnits.proxiedReview(RUN)));
    }

    @Test
    void aUnitWithNoModelProxyIsRefused() {
        assertEquals(Optional.of("kubernetes_requires_credential_proxy"),
                UnitRefusals.of(TestUnits.withoutModelProxy(RUN)));
    }

    @Test
    void aUnitWithEgressOffIsRefused() {
        assertEquals(Optional.of("kubernetes_requires_credential_proxy"),
                UnitRefusals.of(TestUnits.egressOff(RUN)));
    }

    @Test
    void aHostMountIsRefused() {
        assertEquals(Optional.of("kubernetes_has_no_host_mounts"), UnitRefusals.of(TestUnits.withHostMount(RUN)));
    }

    @Test
    void aMountOfAnotherRunsVolumeIsRefused() {
        assertEquals(Optional.of("kubernetes_reviews_only"), UnitRefusals.of(TestUnits.withForeignMount(RUN)));
    }

    @Test
    void aUnitWithNoInputsIsRefused() {
        assertEquals(Optional.of("kubernetes_reviews_only"), UnitRefusals.of(TestUnits.withoutInputs(RUN)));
    }

    @Test
    void inputsOverTheBoundAreRefused() {
        assertEquals(Optional.of("inputs_too_large_for_kubernetes"), UnitRefusals.of(TestUnits.largeInputs(RUN)));
    }

    @Test
    void inputsOfTwoHundredTwentyFourKibibytesAreNotRefused() {
        assertEquals(Optional.empty(), UnitRefusals.of(TestUnits.inputsOfTwoHundredTwentyFourKibibytes(RUN)));
    }

    @Test
    void inputsExactlyAtTheBoundAreNotRefused() {
        assertEquals(Optional.empty(),
                UnitRefusals.of(TestUnits.withInputsOfBytes(RUN, (int) UnitRefusals.MAX_SECRET_BYTES)));
    }

    @Test
    void anEnvironmentOverTheBoundIsRefused() {
        assertEquals(Optional.of("environment_too_large_for_kubernetes"),
                UnitRefusals.of(TestUnits.largeEnvironment(RUN)));
    }

    @Test
    void anEnvironmentExactlyAtTheBoundIsNotRefused() {
        assertEquals(Optional.empty(),
                UnitRefusals.of(TestUnits.withAgentEnvironmentOfBytes(RUN, (int) UnitRefusals.MAX_SECRET_BYTES)));
    }

    @Test
    void aUnitWithNoFetchHostIsRefused() {
        assertEquals(Optional.of("kubernetes_requires_fetch_host"),
                UnitRefusals.of(TestUnits.withoutFetchHost(RUN)));
    }
}
