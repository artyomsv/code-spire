package dev.codespire.context.code;

import dev.codespire.contract.port.LanguageSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleWiringTest {

    /**
     * The module exists, compiles, and can see spire-contract's SPI. Trivial on purpose:
     * its job is to give the module a test so TestTierCoverageTest has something to find.
     */
    @Test
    void theModuleSeesTheLanguageSupportPort() {
        assertTrue(LanguageSupport.class.isInterface());
    }
}
