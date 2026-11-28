package dev.codespire.runtime.k8s;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class UnitNamesTest {

    static final String RUN = "run::github:TEST-owner/TEST-repo:review-1-abcdef123456-0a1b2c3d:1";
    private static final Pattern LEGAL = Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?");

    @Test
    void everyNameIsALegalObjectNameOfAtMostSixtyThreeCharacters() {
        for (String runId : List.of(RUN, "run::" + "TEST/".repeat(40))) {
            List<String> names = new ArrayList<>();
            names.add(UnitNames.anchor(runId));
            names.add(UnitNames.unitPod(runId));
            names.add(UnitNames.proxyPod(runId));
            names.add(UnitNames.unitPolicy(runId));
            names.add(UnitNames.proxyPolicy(runId));
            names.addAll(UnitNames.secretNames(runId));
            for (String name : names) {
                assertTrue(name.length() <= 63, name);
                assertTrue(LEGAL.matcher(name).matches(), name);
            }
            assertTrue(LEGAL.matcher(UnitNames.labels(runId, "unit").get(UnitNames.UNIT_LABEL)).matches());
        }
    }

    @Test
    void theDigestIsSixteenHexCharactersAndStable() {
        assertTrue(UnitNames.digest(RUN).matches("[0-9a-f]{16}"));
        // Golden value: SHA-256 of RUN, first eight bytes. A change renames every live unit's objects.
        assertEquals("ef148d679aaba80c", UnitNames.digest(RUN));
    }

    @Test
    void runIdsThatDifferInOneCharacterGetDifferentNames() {
        assertNotEquals(UnitNames.anchor(RUN), UnitNames.anchor(RUN.replace(":1", ":2")));
        assertNotEquals(UnitNames.anchor(RUN), UnitNames.anchor(RUN.replace("TEST-repo", "TEST-Repo")));
    }

    @Test
    void theLabelValueIsTheDigestNeverTheRunId() {
        assertEquals(UnitNames.digest(RUN), UnitNames.labels(RUN, "unit").get("dev.codespire/unit"));
        assertEquals("unit", UnitNames.labels(RUN, "unit").get("dev.codespire/role"));
        assertFalse(UnitNames.anchor(RUN).contains("test"));
    }

    @Test
    void secretNamesListsEveryEnvironmentRoleAndTheInputs() {
        List<String> names = UnitNames.secretNames(RUN);
        assertEquals(7, names.size());
        assertEquals(7, names.stream().distinct().count());
        assertTrue(names.contains(UnitNames.inputsSecret(RUN)));
        for (String role : List.of("fetch", "agent", "publisher", "model", "egress", "fetchproxy")) {
            assertTrue(names.contains(UnitNames.environmentSecret(RUN, role)), role);
        }
    }
}
