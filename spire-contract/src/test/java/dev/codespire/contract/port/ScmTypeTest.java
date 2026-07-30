package dev.codespire.contract.port;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The registry stores a provider type as a string; the pipeline compares an enum. This lookup is the
 * only bridge, so an unknown or absent string must resolve to empty rather than to a wrong platform —
 * a repo-relative reference resolved against the wrong host fetches a real but unrelated issue.
 */
class ScmTypeTest {

    @Test
    void resolvesEveryDeclaredProviderTypeString() {
        for (ScmType type : ScmType.values()) {
            assertEquals(Optional.of(type), ScmType.fromProviderType(type.providerType()));
        }
    }

    @Test
    void resolvesNothingForAnUnknownOrAbsentString() {
        assertEquals(Optional.empty(), ScmType.fromProviderType("not-a-provider"));
        assertEquals(Optional.empty(), ScmType.fromProviderType(null));
        assertEquals(Optional.empty(), ScmType.fromProviderType(""));
    }
}
