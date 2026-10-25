package dev.codespire.orchestrator.work;

import java.util.List;
import java.util.Optional;

/** RED: what a written specification must hold (M4 slice 3, §6.3). */
final class SpecificationRules {
    static final List<String> HEADINGS = List.of("Outcome", "Context", "Acceptance criteria", "Out of scope", "Open questions");

    private SpecificationRules() {}

    static Optional<String> check(String text) { throw new UnsupportedOperationException("RED"); }
}
