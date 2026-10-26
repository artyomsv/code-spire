package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.work.WorkPreparation;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rules a plan must keep (M4 slice 3, §6.4, A13, A14). Under binding 5 and below a plan is {@code schemaVersion: 1}
 * with exactly one step, as M3 shipped it. Under {@link WorkPreparation#STEPS_BINDING} it is {@code schemaVersion: 2}
 * with 1 to {@code maxStepsPerPlan} steps, unique ids, and every field present, non-blank and within its cap. Either way
 * the plan names the digest of the specification it was made from.
 *
 * <p>Fields are measured after control characters are stripped, the form in which they reach a prompt or a ticket.
 */
final class PlanRules {
    static final int MAX_PLAN_BYTES = 32 * 1024;
    static final int MAX_TITLE_CHARS = 120, MAX_INSTRUCTION_CHARS = 4_000, MAX_DONE_WHEN_CHARS = 1_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** A line break with the blanks around it; control stripping has already removed every carriage return. */
    private static final java.util.regex.Pattern LINE_BREAK = java.util.regex.Pattern.compile("[ \t]*\n\s*");
    /** What each refusal tells the agent on its one retry; the detail itself is what the screens key on. */
    private static final Map<String, String> SENTENCES = Map.of(
            "plan_too_large", "the plan is over 32 KiB",
            "plan_not_json", "the plan is not a JSON object",
            "plan_schema_version", "the plan must be a JSON object with \"schemaVersion\": 2",
            "plan_specification_mismatch", "\"specificationSha256\" is not the digest of the approved specification",
            "plan_step_count", "the plan has no steps, or more steps than the limit; every step needs at least one build run",
            "plan_step_ids", "two steps share an id; every step id must be unique",
            "plan_step_fields", "every step needs an id, a title of at most 120 characters, an instruction of at most 4000"
                    + " characters and a doneWhen of at most 1000 characters, none of them blank");

    /** What the plan is checked against: the preparation binding it is for, the approved specification, the step limit. */
    record Bound(int bindingVersion, String specificationSha256, long maxSteps) {}

    /** The rule that refused the plan, or null with the plan's steps in order. */
    record Checked(String detail, List<WorkArtifacts.Step> steps) {
        static Checked refused(String detail) { return new Checked(detail, List.of()); }

        boolean valid() { return detail == null; }
    }

    private PlanRules() {}

    /** The sentence a refusal is given to the agent in, for its one automatic retry. */
    static String sentence(String detail) {
        return SENTENCES.getOrDefault(detail, detail);
    }

    /** A written plan's text: within its cap, JSON, and then {@link #check}. */
    static Checked accept(String text, Bound bound) {
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_PLAN_BYTES) return Checked.refused("plan_too_large");
        try { return check(MAPPER.readTree(text), bound); }
        catch (JsonProcessingException invalid) { return Checked.refused("plan_not_json"); }
    }

    static Checked check(JsonNode root, Bound bound) {
        boolean steps = bound.bindingVersion() >= WorkPreparation.STEPS_BINDING;
        int version = steps ? 2 : 1;
        if (root == null || !root.isObject() || !root.path("schemaVersion").isInt() || root.path("schemaVersion").asInt() != version)
            return Checked.refused("plan_schema_version");
        if (!bound.specificationSha256().equals(root.path("specificationSha256").asText()))
            return Checked.refused("plan_specification_mismatch");
        JsonNode array = root.path("steps");
        long max = steps ? bound.maxSteps() : 1;
        if (!array.isArray() || array.isEmpty() || array.size() > max) return Checked.refused("plan_step_count");
        return steps ? versionTwo(array) : versionOne(array.get(0));
    }

    /** M3's single step: an id and an instruction; the id stands in for a title it never had. */
    private static Checked versionOne(JsonNode step) {
        String id = text(step, "id", Integer.MAX_VALUE), instruction = text(step, "instruction", Integer.MAX_VALUE);
        if (id == null || instruction == null) return Checked.refused("plan_step_fields");
        return new Checked(null, List.of(new WorkArtifacts.Step(id, id, step.path("instruction").asText(), "")));
    }

    private static Checked versionTwo(JsonNode array) {
        List<WorkArtifacts.Step> steps = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode node : array) {
            String id = line(text(node, "id", MAX_TITLE_CHARS)), title = line(text(node, "title", MAX_TITLE_CHARS));
            String instruction = text(node, "instruction", MAX_INSTRUCTION_CHARS), doneWhen = text(node, "doneWhen", MAX_DONE_WHEN_CHARS);
            if (id == null || title == null || instruction == null || doneWhen == null) return Checked.refused("plan_step_fields");
            if (!ids.add(id)) return Checked.refused("plan_step_ids");
            steps.add(new WorkArtifacts.Step(id, title, instruction, doneWhen));
        }
        return new Checked(null, List.copyOf(steps));
    }

    /**
     * An id or a title on one line (A13): a title is posted on the ticket as a list item, where a line break would put
     * the rest at the start of a comment line, and GitLab runs such a line starting with a slash as a quick action. The
     * plan's text is stored as written, so every read of it collapses the same way and the prompt and the ticket agree.
     */
    private static String line(String text) {
        return text == null ? null : LINE_BREAK.matcher(text).replaceAll(" ");
    }

    /** The field's text after control stripping, or null when it is missing, not text, blank or over its cap. */
    private static String text(JsonNode node, String field, int maxChars) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) return null;
        String clean = UntrustedText.stripControls(value.asText());
        return clean.isBlank() || clean.length() > maxChars ? null : clean;
    }
}
