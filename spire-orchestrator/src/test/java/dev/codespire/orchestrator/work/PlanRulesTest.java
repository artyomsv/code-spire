package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.codespire.contract.work.WorkPreparation;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/** The plan's rules (M4 slice 3, §6.4, A13, A14): version 1 keeps one step, version 2 carries N under binding 6 only. */
class PlanRulesTest {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String SPEC_SHA = WorkPreparation.digest("TEST-specification");
    static final PlanRules.Bound SIX = new PlanRules.Bound(WorkPreparation.STEPS_BINDING, SPEC_SHA, 5);
    static final PlanRules.Bound FIVE = new PlanRules.Bound(WorkPreparation.VERIFY_BINDING, SPEC_SHA, 5);

    static ObjectNode step(String id, String title, String instruction, String doneWhen) {
        return MAPPER.createObjectNode().put("id", id).put("title", title).put("instruction", instruction).put("doneWhen", doneWhen);
    }

    static ObjectNode step(int n) {
        return step("TEST-s" + n, "TEST-step-" + n, "TEST-instruction-" + n, "TEST-done-" + n);
    }

    static JsonNode plan(int version, String digest, List<ObjectNode> steps) {
        ObjectNode root = MAPPER.createObjectNode().put("schemaVersion", version).put("specificationSha256", digest);
        ArrayNode array = root.putArray("steps");
        steps.forEach(array::add);
        return root;
    }

    static List<ObjectNode> steps(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count).mapToObj(PlanRulesTest::step).toList();
    }

    @Test
    void versionOneKeepsExactlyOneStep() {
        assertEquals("plan_step_count", PlanRules.check(plan(1, SPEC_SHA, steps(2)), FIVE).detail());
    }

    @Test
    void versionOneWithOneStepStillPasses() {
        PlanRules.Checked checked = PlanRules.check(plan(1, SPEC_SHA, List.of(MAPPER.createObjectNode().put("id", "TEST-step")
                .put("instruction", "TEST-instruction"))), FIVE);

        assertNull(checked.detail());
        assertEquals("TEST-instruction", checked.steps().getFirst().instruction());
    }

    @Test
    void versionTwoNeedsBindingSix() {
        assertEquals("plan_schema_version", PlanRules.check(plan(2, SPEC_SHA, steps(1)), FIVE).detail());
    }

    @Test
    void bindingSixAcceptsNSteps() {
        PlanRules.Checked checked = PlanRules.check(plan(2, SPEC_SHA, steps(3)), SIX);

        assertNull(checked.detail());
        assertEquals(List.of("TEST-s1", "TEST-s2", "TEST-s3"), checked.steps().stream().map(WorkArtifacts.Step::id).toList());
        assertEquals(new WorkArtifacts.Step("TEST-s2", "TEST-step-2", "TEST-instruction-2", "TEST-done-2"), checked.steps().get(1));
    }

    @Test
    void duplicateIdsAreRefused() {
        assertEquals("plan_step_ids", PlanRules.check(plan(2, SPEC_SHA, List.of(step(1), step("TEST-s1", "TEST-other", "TEST-x", "TEST-y"))), SIX).detail());
    }

    @Test
    void moreThanMaxStepsIsRefused() {
        assertEquals("plan_step_count", PlanRules.check(plan(2, SPEC_SHA, steps(6)), SIX).detail());
        assertNull(PlanRules.check(plan(2, SPEC_SHA, steps(5)), SIX).detail(), "exactly the limit passes");
        assertEquals("plan_step_count", PlanRules.check(plan(2, SPEC_SHA, List.of()), SIX).detail());
    }

    @ParameterizedTest
    @CsvSource({"title,121", "instruction,4001", "doneWhen,1001"})
    void eachFieldCapApplies(String field, int length) {
        ObjectNode over = step(1).put(field, "x".repeat(length));
        ObjectNode at = step(1).put(field, "x".repeat(length - 1));

        assertEquals("plan_step_fields", PlanRules.check(plan(2, SPEC_SHA, List.of(over)), SIX).detail());
        assertNull(PlanRules.check(plan(2, SPEC_SHA, List.of(at)), SIX).detail(), "the cap itself passes");
    }

    @ParameterizedTest
    @CsvSource({"id", "title", "instruction", "doneWhen"})
    void everyFieldIsRequired(String field) {
        assertEquals("plan_step_fields", PlanRules.check(plan(2, SPEC_SHA, List.of(step(1).put(field, "  "))), SIX).detail());
        ObjectNode missing = step(1);
        missing.remove(field);
        assertEquals("plan_step_fields", PlanRules.check(plan(2, SPEC_SHA, List.of(missing)), SIX).detail());
    }

    @Test
    void theDigestMustBeTheApprovedSpecification() {
        assertEquals("plan_specification_mismatch", PlanRules.check(plan(2, "e".repeat(64), steps(1)), SIX).detail());
    }

    @Test
    void aPlanUnderBindingSixIsVersionTwo() {
        assertEquals("plan_schema_version", PlanRules.check(plan(1, SPEC_SHA, steps(1)), SIX).detail());
    }

    @Test
    void anAcceptedTextIsJsonWithinItsCap() {
        String valid = plan(2, SPEC_SHA, steps(2)).toString();

        assertNull(PlanRules.accept(valid, SIX).detail());
        assertEquals("plan_not_json", PlanRules.accept("TEST-not-json", SIX).detail());
        assertEquals("plan_too_large", PlanRules.accept(valid + " ".repeat(PlanRules.MAX_PLAN_BYTES), SIX).detail());
    }
}
