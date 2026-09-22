package dev.codespire.contract.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import dev.codespire.contract.work.ThinkingLevel;

import java.util.List;
import java.util.Objects;

/**
 * What an agent image declares about itself (M3.5 part M). Rides {@code cs.harness-image-results}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = HarnessImageResult.Described.class, name = "Described")
})
public sealed interface HarnessImageResult {

    String requestId();

    /**
     * One model the image's harness can run.
     *
     * @param slug what the harness is given as its model name
     * @param displayName what an operator reads
     * @param defaultEffort THAT model's own default thinking level — models differ, so there is no global one
     * @param efforts the thinking levels this model allows, in the vendor's order
     * @param visible the vendor's own "offer this one" flag; a hidden model still runs if named
     * @param priority the vendor's own ordering, lower first
     */
    record Model(String slug, String displayName, String defaultEffort, List<String> efforts,
                 boolean visible, int priority) {
        public Model {
            if (slug == null || slug.isBlank()) throw new IllegalArgumentException("A model slug is required");
            // The same rule every later hop applies. A level only this record accepted could be offered
            // and saved, then refused when the task is prepared -- after the operator walked away.
            String ownDefault = ThinkingLevel.normalise(defaultEffort);
            defaultEffort = ownDefault == null ? "" : ownDefault;
            List<String> levels = new java.util.ArrayList<>();
            for (String level : efforts == null ? List.<String>of() : efforts) {
                String checked = ThinkingLevel.normalise(level);
                if (checked == null) throw new IllegalArgumentException("A declared thinking level is blank");
                levels.add(checked);
            }
            efforts = List.copyOf(levels);
        }
    }

    /**
     * Why an image has no usable list. Named, so the screen can say which of these it is rather than
     * showing an empty dropdown that could mean any of them.
     */
    enum Status {
        /** The label was there and read. */
        OK,
        /** The image carries no catalogue label — built without deploy/agent/build-codex.sh, typically. */
        NO_CATALOGUE,
        /** The label was there and could not be read as a catalogue. */
        UNREADABLE,
        /** The image could not be reached: not held, and the pull failed. */
        IMAGE_UNAVAILABLE
    }

    /**
     * @param models empty unless {@code status} is {@link Status#OK}
     */
    record Described(String requestId, String harness, String image, Status status, List<Model> models)
            implements HarnessImageResult {
        public Described {
            if (requestId == null || requestId.isBlank()) throw new IllegalArgumentException("A request id is required");
            Objects.requireNonNull(harness, "harness");
            Objects.requireNonNull(image, "image");
            Objects.requireNonNull(status, "status");
            models = models == null ? List.of() : List.copyOf(models);
            if (status != Status.OK && !models.isEmpty()) {
                throw new IllegalArgumentException("Only a readable catalogue carries models, status was " + status);
            }
        }
    }
}
