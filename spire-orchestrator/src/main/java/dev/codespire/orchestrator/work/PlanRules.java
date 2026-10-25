package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

final class PlanRules {
    static final int MAX_PLAN_BYTES = 32 * 1024;

    record Bound(int bindingVersion, String specificationSha256, long maxSteps) {}

    record Checked(String detail, List<WorkArtifacts.Step> steps) {}

    private PlanRules() {}

    static Checked check(JsonNode root, Bound bound) { throw new UnsupportedOperationException("RED"); }

    static Checked accept(String text, Bound bound) { throw new UnsupportedOperationException("RED"); }
}
