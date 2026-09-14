package dev.codespire.publisher;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PublicationPolicyTest {
    static final String HEAD = "a".repeat(40);
    static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");

    @Test void headMustBeComplete() {
        for (String head : new String[]{null, "", "abc", "A".repeat(40), "g".repeat(40)}) {
            assertThrows(IllegalStateException.class,
                    () -> PublicationPolicy.permitted(head, NOW, NOW.plusSeconds(1), Clock.systemUTC()));
        }
    }

    @Test void validityWindowMustBeOrdered() {
        for (Instant expires : new Instant[]{null, NOW, NOW.minusSeconds(1)}) {
            assertThrows(IllegalStateException.class,
                    () -> PublicationPolicy.permitted(HEAD, NOW, expires, Clock.systemUTC()));
        }
        assertThrows(IllegalStateException.class,
                () -> PublicationPolicy.permitted(HEAD, null, NOW, Clock.systemUTC()));
    }

    @Test void validityIncludesIssueAndExcludesExpiry() {
        assertFalse(at(NOW.minusNanos(1)).validNow());
        assertTrue(at(NOW).validNow());
        assertTrue(at(NOW.plusSeconds(1).minusNanos(1)).validNow());
        assertFalse(at(NOW.plusSeconds(1)).validNow());
    }

    @Test void entrypointChoosesAuthority() {
        Map<String, String> env = Map.of("SPIRE_PERMITTED_HEAD", HEAD,
                "SPIRE_PERMIT_ISSUED_AT", NOW.toString(), "SPIRE_PERMIT_EXPIRES_AT", NOW.plusSeconds(1).toString());
        assertEquals(PublicationPolicy.Mode.HELD, PublicationPolicy.fromEnv(PublicationPolicy.Mode.HELD, env).mode());
        assertEquals(PublicationPolicy.Mode.AUTOMATIC, PublicationPolicy.fromEnv(PublicationPolicy.Mode.AUTOMATIC, env).mode());
        PublicationPolicy permit = PublicationPolicy.fromEnv(PublicationPolicy.Mode.PERMITTED, env);
        assertEquals(PublicationPolicy.Mode.PERMITTED, permit.mode());
        assertEquals(HEAD, permit.head());
        assertEquals(NOW, permit.issuedAt());
        assertEquals(NOW.plusSeconds(1), permit.expiresAt());
        assertThrows(IllegalStateException.class,
                () -> PublicationPolicy.fromEnv(PublicationPolicy.Mode.PERMITTED, Map.of()));
        assertThrows(IllegalStateException.class,
                () -> PublicationPolicy.fromEnv(PublicationPolicy.Mode.PERMITTED,
                        Map.of("SPIRE_PERMITTED_HEAD", HEAD, "SPIRE_PERMIT_ISSUED_AT", "not a timestamp",
                                "SPIRE_PERMIT_EXPIRES_AT", NOW.toString())));
    }

    private PublicationPolicy at(Instant now) {
        return PublicationPolicy.permitted(HEAD, NOW, NOW.plusSeconds(1), Clock.fixed(now, ZoneOffset.UTC));
    }
}
