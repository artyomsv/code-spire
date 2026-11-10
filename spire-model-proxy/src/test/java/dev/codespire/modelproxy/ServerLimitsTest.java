package dev.codespire.modelproxy;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The bounds a run unit's model proxy runs with, stated once and checked here. */
class ServerLimitsTest {

    @Test
    void theJdkServersBoundsAreTheDeclaredOnes() {
        assertEquals(Map.of(
                "sun.net.httpserver.maxReqTime", "30",
                "sun.net.httpserver.maxRspTime", "3600",
                "jdk.httpserver.maxConnections", "32",
                "sun.net.httpserver.maxReqHeaders", "100",
                "sun.net.httpserver.maxReqHeaderSize", "65536"), ServerLimits.PRODUCTION);
    }

    @Test
    void theProxysOwnBoundsAreTheDeclaredOnes() {
        assertEquals(new Limits(8, 8L * 1024 * 1024, Duration.ofMinutes(10), Duration.ofMinutes(10),
                Duration.ofMinutes(60)), Limits.PRODUCTION);
    }

    /**
     * A property already set wins, which is how this JVM runs the admission tests at 2 seconds; and the guard of those
     * tests: without the property they would wait the production 30 s and fail on their own 8 s bound.
     */
    @Test
    void aPropertyAlreadySetIsKept() {
        ServerLimits.apply();

        assertEquals("2", System.getProperty("sun.net.httpserver.maxReqTime"));
    }
}
