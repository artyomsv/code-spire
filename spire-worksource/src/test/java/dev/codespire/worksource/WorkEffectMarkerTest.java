package dev.codespire.worksource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WorkEffectMarkerTest {
    @Test void markerAndBodyHaveOneStableIdentity() { assertEquals("TEST-text\n\n<!-- work-effect:TEST-effect -->",WorkEffectMarker.body("TEST-text","TEST-effect")); }
    @Test void missingIdentityIsRefused() { assertThrows(WorkSourceException.class,()->WorkEffectMarker.of(null)); }
    @Test void markupCannotEscapeTheMarker() { assertThrows(WorkSourceException.class,()->WorkEffectMarker.of("TEST-->injected")); }
    @Test void oversizedIdentityIsRefused() { assertThrows(WorkSourceException.class,()->WorkEffectMarker.of("TEST-"+"x".repeat(96))); }
    @Test void missingCommentIsRefused() { assertThrows(WorkSourceException.class,()->WorkEffectMarker.body(null,"TEST-effect")); }
    @Test void blankCommentIsRefused() { assertThrows(WorkSourceException.class,()->WorkEffectMarker.body("  ","TEST-effect")); }
}
