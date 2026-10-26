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

    @Test void anExistingBranchPermitComesFromTheTrustedEnvironment() {
        String expected = "b".repeat(40), tip = "c".repeat(40);
        Map<String, String> env = Map.of("SPIRE_PERMITTED_HEAD", HEAD,
                "SPIRE_PERMIT_ISSUED_AT", NOW.toString(), "SPIRE_PERMIT_EXPIRES_AT", NOW.plusSeconds(1).toString(),
                "SPIRE_EXPECTED_REMOTE_HEAD", expected, "SPIRE_MERGED_BASE_TIP", tip);
        PublicationPolicy permit = PublicationPolicy.fromEnv(PublicationPolicy.Mode.PERMITTED, env);
        assertEquals(expected, permit.expectedRemoteHead());
        assertEquals(tip, permit.mergedBaseTip());
        assertNull(at(NOW).expectedRemoteHead(), "a new-branch permit expects nothing");
        assertNull(PublicationPolicy.fromEnv(PublicationPolicy.Mode.HELD, env).expectedRemoteHead(),
                "only a permitted publisher pushes");
    }

    @Test void anExistingBranchPermitNeedsFullShas() {
        for (String invalid : new String[]{"", "abc", "B".repeat(40)}) {
            assertThrows(IllegalStateException.class, () -> at(NOW).onExistingBranch(invalid, null), invalid);
            assertThrows(IllegalStateException.class, () -> at(NOW).onExistingBranch("b".repeat(40), invalid), invalid);
        }
        assertThrows(IllegalStateException.class, () -> at(NOW).onExistingBranch(null, "c".repeat(40)));
        assertThrows(IllegalStateException.class, () -> PublicationPolicy.held().onExistingBranch("b".repeat(40), null));
    }

    /** A plan step's held publisher refuses a head off the chain it started from (M4 slice 3, A2). */
    @Test void aHeldPlanStepReadsItsStartHead() {
        String start = "c".repeat(40);
        assertEquals(start, PublicationPolicy.fromEnv(PublicationPolicy.Mode.HELD, Map.of("SPIRE_START_HEAD", start)).startHead());
        assertNull(PublicationPolicy.fromEnv(PublicationPolicy.Mode.HELD, Map.of()).startHead(), "a build from the base has none");
        assertThrows(IllegalStateException.class, () -> PublicationPolicy.fromEnv(PublicationPolicy.Mode.HELD, Map.of("SPIRE_START_HEAD", "abc")));
    }

    @Test void aHeldLandFixGatesWithTheTrustedBaseTip() {
        // The held build sees the base merge first; without the tip it refuses before any permit exists.
        String tip = "c".repeat(40);
        PublicationPolicy held = PublicationPolicy.fromEnv(PublicationPolicy.Mode.HELD,
                Map.of("SPIRE_MERGED_BASE_TIP", tip));
        assertEquals(PublicationPolicy.Mode.HELD, held.mode());
        assertEquals(tip, held.mergedBaseTip());
        assertNull(held.expectedRemoteHead(), "a held build pushes nothing");
        assertNull(PublicationPolicy.fromEnv(PublicationPolicy.Mode.HELD, Map.of()).mergedBaseTip());
        for (String invalid : new String[]{"abc", "C".repeat(40)}) {
            assertThrows(IllegalStateException.class, () -> PublicationPolicy.fromEnv(PublicationPolicy.Mode.HELD,
                    Map.of("SPIRE_MERGED_BASE_TIP", invalid)), invalid);
        }
        assertNull(PublicationPolicy.fromEnv(PublicationPolicy.Mode.AUTOMATIC,
                Map.of("SPIRE_MERGED_BASE_TIP", tip)).mergedBaseTip(), "only a land fix's build merges its base");
    }

    @Test void readOnlyIsItsOwnMode() {
        PublicationPolicy readOnly = PublicationPolicy.readOnly();
        assertEquals(PublicationPolicy.Mode.READ_ONLY, readOnly.mode());
        assertEquals(PublicationPolicy.Mode.READ_ONLY, PublicationPolicy.fromEnv(PublicationPolicy.Mode.READ_ONLY,
                Map.of("SPIRE_PERMITTED_HEAD", HEAD, "SPIRE_MERGED_BASE_TIP", "c".repeat(40))).mode());
        assertNull(readOnly.mergedBaseTip(), "a read-only run merges nothing");
        assertNull(readOnly.head(), "a read-only run is permitted no head");
        assertThrows(IllegalStateException.class, () -> readOnly.onExistingBranch("b".repeat(40), null),
                "only a permitted publisher pushes to an existing branch");
    }

    private PublicationPolicy at(Instant now) {
        return PublicationPolicy.permitted(HEAD, NOW, NOW.plusSeconds(1), Clock.fixed(now, ZoneOffset.UTC));
    }
}
