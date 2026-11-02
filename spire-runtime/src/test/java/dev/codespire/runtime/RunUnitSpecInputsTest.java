package dev.codespire.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A run unit's inputs reach init (writable) and the agent (read-only), never the publisher. */
class RunUnitSpecInputsTest {

    private static final UnitInputs INPUTS = new UnitInputs("inputs", "/inputs",
            Map.of("context.md", "TEST-INPUT-CANARY"));

    private static ContainerSpec mounting(Mount... mounts) {
        return new ContainerSpec("img", List.of(), Map.of(), List.of(mounts));
    }

    /** A review unit as harness reviews step 6 builds it: init writes /inputs, the agent reads it, the publisher has none. */
    private static RunUnitSpec withInputs(ContainerSpec init, ContainerSpec agent, ContainerSpec publisher) {
        return new RunUnitSpec("run_1", init, agent, publisher, EnterpriseEnvironment.NONE, EgressPolicy.OFF,
                1024, 1024, 1024, Duration.ofMinutes(1), INPUTS);
    }

    private static ContainerSpec initWritingInputs() {
        return mounting(Mount.writable("workspace", "/workspace"), Mount.writable("inputs", "/inputs"));
    }

    private static ContainerSpec agentReadingInputs() {
        return mounting(Mount.writable("handoff", "/handoff"), Mount.readOnly("inputs", "/inputs"));
    }

    private static ContainerSpec readOnlyPublisher() {
        return mounting(Mount.readOnly("handoff", "/handoff"));
    }

    @Test
    void aUnitWhoseInputsReachOnlyInitAndAgentIsAccepted() {
        // Guards the three refusals below: a check that refused every unit with inputs would pass them all.
        RunUnitSpec spec = withInputs(initWritingInputs(), agentReadingInputs(), readOnlyPublisher());

        assertEquals(INPUTS, spec.inputs());
    }

    @Test
    void aPublisherThatMountsTheInputsIsRefused() {
        // Read-only too: the publisher never needs the inputs, and they carry text only the agent was told to read.
        ContainerSpec publisher = mounting(Mount.readOnly("handoff", "/handoff"), Mount.readOnly("inputs", "/inputs"));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> withInputs(initWritingInputs(), agentReadingInputs(), publisher));
        assertTrue(refused.getMessage().contains("inputs"), refused.getMessage());
    }

    @Test
    void anAgentThatCanWriteTheInputsIsRefused() {
        ContainerSpec agent = mounting(Mount.writable("handoff", "/handoff"), Mount.writable("inputs", "/inputs"));

        assertThrows(IllegalArgumentException.class, () -> withInputs(initWritingInputs(), agent, readOnlyPublisher()));
    }

    @Test
    void anInitThatCannotWriteTheInputsIsRefused() {
        ContainerSpec readOnlyInit = mounting(Mount.writable("workspace", "/workspace"), Mount.readOnly("inputs", "/inputs"));
        ContainerSpec noInputsInit = mounting(Mount.writable("workspace", "/workspace"));

        assertThrows(IllegalArgumentException.class,
                () -> withInputs(readOnlyInit, agentReadingInputs(), readOnlyPublisher()));
        assertThrows(IllegalArgumentException.class,
                () -> withInputs(noInputsInit, agentReadingInputs(), readOnlyPublisher()));
    }

    @Test
    void theWithersKeepTheInputs() {
        RunUnitSpec spec = withInputs(initWritingInputs(), agentReadingInputs(), readOnlyPublisher());

        assertEquals(INPUTS, spec.withInit(initWritingInputs()).inputs(), "withInit");
        assertEquals(INPUTS, spec.withAgent(agentReadingInputs()).inputs(), "withAgent");
        assertEquals(INPUTS, spec.withPublisher(readOnlyPublisher()).inputs(), "withPublisher");
    }

    @Test
    void withInputsReplacesOnlyTheInputs() {
        RunUnitSpec plain = new RunUnitSpec("run_1", initWritingInputs(), agentReadingInputs(), readOnlyPublisher(),
                EnterpriseEnvironment.NONE, EgressPolicy.OFF, 1024, 1024, 1024, Duration.ofMinutes(1));

        RunUnitSpec given = plain.withInputs(INPUTS);

        assertEquals(INPUTS, given.inputs());
        assertEquals(plain.agent(), given.agent());
    }

    @Test
    void aUnitWithoutInputsHasNone() {
        // The 10-argument constructor: every build unit and a held build stored before inputs existed.
        assertEquals(null, RuntimeSpiTest.unit(2L << 30, 2_000_000_000L, Duration.ofMinutes(30)).inputs());
    }

    @Test
    void inputsNeverPrintTheirText() {
        String printed = INPUTS.toString();

        assertFalse(printed.contains("TEST-INPUT-CANARY"), printed);
        assertTrue(printed.contains("context.md"), printed);
        assertTrue(printed.contains("17"), "the byte count of TEST-INPUT-CANARY: " + printed);
    }

    @Test
    void anInputNameWithASlashIsRefused() {
        for (String name : List.of("a/b.md", "..", "../context.md", "")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new UnitInputs("inputs", "/inputs", Map.of(name, "TEST")), name);
        }
        assertThrows(IllegalArgumentException.class, () -> new UnitInputs("inputs", "inputs", Map.of()),
                "the path is absolute");
        assertThrows(IllegalArgumentException.class, () -> new UnitInputs(" ", "/inputs", Map.of()),
                "the volume is named");
    }

    @Test
    void inputsOverTheRuntimeBoundAreRefused() {
        String over = "x".repeat(UnitInputs.MAX_BYTES + 1);

        assertThrows(IllegalArgumentException.class,
                () -> new UnitInputs("inputs", "/inputs", Map.of("context.md", over)));
    }
}
