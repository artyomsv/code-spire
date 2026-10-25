package dev.codespire.orchestrator.work;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a written specification must hold before anyone is asked to approve it (M4 slice 3, §6.3, A8): the five headings
 * in order, at least one list item under the acceptance criteria, and at most 16 KiB once control characters are
 * stripped. The first failure is returned as a sentence, because it goes into the retry's prompt as it stands.
 */
final class SpecificationRules {
    static final List<String> HEADINGS = List.of("Outcome", "Context", "Acceptance criteria", "Out of scope", "Open questions");
    /** The prompt budget's share for the specification (A8), in UTF-8 bytes. */
    static final int MAX_BYTES = 16 * 1024;
    private static final String CRITERIA = "Acceptance criteria";
    private static final Pattern HEADING = Pattern.compile("^##\\s+(.+?)\\s*$");
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-*+]|\\d+[.)])\\s+\\S.*$");

    private SpecificationRules() {}

    /** @return the first rule the text breaks, or empty when it may be stored */
    static Optional<String> check(String text) {
        String clean = UntrustedText.stripControls(text);
        if (clean.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            return Optional.of("the specification is over 16 KiB; shorten it");
        List<String> lines = clean.lines().toList();
        List<String> headings = headings(lines);
        Optional<String> order = order(headings);
        if (order.isPresent()) return order;
        return criteria(lines) ? Optional.empty() : Optional.of("acceptance criteria need at least one list item");
    }

    private static List<String> headings(List<String> lines) {
        List<String> headings = new ArrayList<>();
        for (String line : lines) {
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) headings.add(heading.group(1));
        }
        return headings;
    }

    private static Optional<String> order(List<String> headings) {
        for (String required : HEADINGS)
            if (!headings.contains(required)) return Optional.of("missing heading: " + required);
        int previous = -1;
        for (String required : HEADINGS) {
            int at = headings.indexOf(required);
            if (at < previous) return Optional.of("headings out of order: " + required + " must follow the headings before it");
            previous = at;
        }
        return Optional.empty();
    }

    /** Whether a list item stands between the criteria heading and the next heading. */
    private static boolean criteria(List<String> lines) {
        boolean inside = false;
        for (String line : lines) {
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) inside = CRITERIA.equals(heading.group(1));
            else if (inside && LIST_ITEM.matcher(line).matches()) return true;
        }
        return false;
    }
}
