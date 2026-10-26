package dev.codespire.runworker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.work.StepSummary;
import dev.codespire.secrets.SecretScrub;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads the step summary a held build handed over (M4 slice 8, §6.8, A9, A13) into the contract's capped record.
 *
 * <p>The agent wrote the file with the model key in its environment, and the publisher's own redaction knows only the
 * forge credential, so every item is scrubbed here with the run's credentials before it leaves the worker. Control
 * characters go first, as they do from every untrusted text: a key split by one would pass the literal scrub and be
 * joined back by a later strip. It is scrubbed before it is cut: a credential straddling the cut would otherwise leave
 * its head behind. Lenient on shape and strict on size: unknown fields and non-text items
 * are skipped, a list over 20 items is truncated, and an item over 500 characters is cut on a character boundary and
 * ends with an ellipsis. Only a file that is not a JSON object is no summary at all. A backtick run is kept verbatim:
 * fencing it is the prompt's job, when a later step quotes it.
 */
final class StepSummaries {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String ELLIPSIS = "…";

    private StepSummaries() {
    }

    /** The summary the publisher reported, scrubbed; null when none arrived or it was not a JSON object. */
    static StepSummary from(PublisherOutcome outcome, SecretScrub scrub) {
        return outcome.stepSummary().flatMap(json -> parse(json, scrub)).orElse(null);
    }

    static Optional<StepSummary> parse(String json, SecretScrub scrub) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JsonProcessingException unreadable) {
            return Optional.empty();
        }
        if (root == null || !root.isObject()) return Optional.empty();
        return Optional.of(new StepSummary(items(root.get("decisions"), scrub),
                items(root.get("alternativesRejected"), scrub), items(root.get("deviations"), scrub)));
    }

    private static List<String> items(JsonNode list, SecretScrub scrub) {
        List<String> items = new ArrayList<>();
        if (list == null || !list.isArray()) return items;
        for (JsonNode element : list) {
            if (!element.isTextual()) continue;
            String item = cut(scrub.clean(ControlCharacters.strip(element.asText())));
            if (!item.isBlank()) items.add(item);
        }
        return items;
    }

    private static String cut(String item) {
        if (item.codePointCount(0, item.length()) <= StepSummary.MAX_ITEM_CHARS) return item;
        int end = item.offsetByCodePoints(0, StepSummary.MAX_ITEM_CHARS - 1);
        return item.substring(0, end) + ELLIPSIS;
    }
}
