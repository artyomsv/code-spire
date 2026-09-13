package dev.codespire.contract.work;

import dev.codespire.worksource.CurrentLabel;
import dev.codespire.worksource.LabelEvent;
import java.util.*;

/** Versioned operator policy. Names have no executable meaning. Missing phases grant nothing. */
public final class WorkPolicy {
    private WorkPolicy() {}
    public enum Phase { INTAKE, SPEC, PLAN, BUILD, VERIFY, REVIEW, DELIVER, LAND }
    public record Profile(UUID id, String name, long version, int precedence, Map<Phase, String> modes) {
        public Profile {
            Objects.requireNonNull(id);
            if (name == null || name.isBlank() || version < 1 || precedence < 0)
                throw new IllegalArgumentException("Profile name, version and nonnegative precedence are required");
            EnumMap<Phase, String> complete = new EnumMap<>(Phase.class);
            for (Phase phase : Phase.values()) {
                String mode = modes == null ? "off" : modes.getOrDefault(phase, "off");
                if (mode == null || !vocabulary(phase).contains(mode)) throw new IllegalArgumentException("Invalid mode for " + phase);
                complete.put(phase, mode);
            }
            modes = Map.copyOf(complete);
        }
    }
    public record IgnoredLabel(String label, String reason, String actorId, LabelEvent.Origin origin) {}
    public record AppliedLabel(String label, String actorId, LabelEvent.Origin origin, String eventId, UUID profileId, long profileVersion) {}
    public record Selection(Profile selected, Map<Phase, String> effective, List<IgnoredLabel> ignored, String reason, List<AppliedLabel> applied, Profile ceiling) {
        public Selection { effective = Map.copyOf(effective); ignored = List.copyOf(ignored); applied = List.copyOf(applied); }
    }

    public static Selection select(List<CurrentLabel> current, Set<String> allowedActors,
                                   Map<String, Profile> mappings, Profile ceiling, Map<Phase, String> admitted) {
        List<IgnoredLabel> ignored = new ArrayList<>();
        List<Profile> eligible = new ArrayList<>();
        List<AppliedLabel> applied = new ArrayList<>();
        for (CurrentLabel label : current) {
            Profile profile = mappings.get(label.label());
            if (profile == null) continue;
            String reason = null;
            if (label.trackerActorId() == null || label.trackerActorId().isBlank()) reason = "actor_id_missing";
            else if (label.origin() == null || label.origin() == LabelEvent.Origin.UNATTRIBUTED) reason = "label_unattributed";
            else if (!allowedActors.contains(label.trackerActorId())) reason = "actor_not_allowed";
            if (reason != null) ignored.add(new IgnoredLabel(label.label(), reason, label.trackerActorId(), label.origin()));
            else {
                eligible.add(profile);
                applied.add(new AppliedLabel(label.label(), label.trackerActorId(), label.origin(), label.eventId(), profile.id(), profile.version()));
            }
        }
        if (eligible.isEmpty()) return new Selection(null, Map.of(), ignored, "no_eligible_label", applied, ceiling);
        if (ceiling == null) return new Selection(null, Map.of(), ignored, "ceiling_missing", applied, null);
        Profile selected = eligible.stream().min(Comparator.comparingInt(Profile::precedence)).orElseThrow();
        List<Profile> bounds = new ArrayList<>(eligible);
        bounds.add(ceiling);
        EnumMap<Phase, String> effective = new EnumMap<>(Phase.class);
        for (Phase phase : Phase.values()) {
            List<String> vocabulary = vocabulary(phase);
            int rank = bounds.stream().mapToInt(profile -> vocabulary.indexOf(profile.modes().get(phase))).min().orElse(0);
            if (admitted != null) rank = Math.min(rank, vocabulary.indexOf(admitted.getOrDefault(phase, "off")));
            effective.put(phase, vocabulary.get(rank));
        }
        return new Selection(selected, effective, ignored,
                effective.equals(selected.modes()) ? "policy_selected" : "policy_clamped", applied, ceiling);
    }

    private static List<String> vocabulary(Phase phase) {
        return switch (phase) {
            case DELIVER -> List.of("off", "draft_pr", "pr");
            case LAND -> List.of("off", "approve", "auto_if_green");
            default -> List.of("off", "approve", "auto");
        };
    }
}
