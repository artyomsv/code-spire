package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.contract.port.PullRequestLander;
import dev.codespire.contract.scm.PullRequestRef;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.contract.work.WorkItemLifecycle;
import dev.codespire.encryption.EncryptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The land fix in progress (M4 land, spec §7.5, A7): the land attempt that asked for it, the pull request and branch it
 * fixes, the head it starts from and must still find on the forge, and what its run is told. Read under the item lock by
 * the run dispatcher and by delivery. The payload is encrypted on the effect row, because a check summary may quote source.
 */
@ApplicationScoped
public class WorkLandFixes {
    static final String MERGE_BASE = "refs/spire-base/";

    @Inject EncryptionService encryption;
    @Inject ObjectMapper mapper;

    /**
     * What the land effect hands a fix.
     *
     * @param baseTip the pull request's base as the land effect read it, for a conflict or a branch behind; null for a
     *     failed check, whose fix merges nothing, so no path is excused as the base's
     */
    public record Payload(String baseBranch, String baseTip, String instruction) {}

    /**
     * @param effect the land attempt that requested the fix
     * @param approvedHead the head the fix clones from the forge, and the remote head its push expects (A7)
     * @param branch the item branch: the fix clones it and pushes to it
     * @param baseBranch the pull request's base, which the fix may never push to and whose tip the run unit fetches
     * @param runs the runs this fix has claimed, so a verify retry inside it is told only its own verify's findings
     */
    public record Fix(UUID effect, String reason, String approvedHead, PullRequestRef pullRequest, String branch, String baseBranch,
                      String baseTip, String payload, Set<String> runs) {}

    /** The current generation's land fix, while one is in progress. Caller holds the item lock. */
    public Optional<Fix> current(Connection c, List<EventEnvelope> history) throws SQLException {
        List<WorkItemEvent> facts = history.stream().map(event -> (WorkItemEvent) event.payload()).toList();
        if (!WorkItemLifecycle.landFixInProgress(facts)) return Optional.empty();
        int requestedAt = lastRequest(facts);
        WorkItemEvent requested = facts.get(requestedAt);
        UUID effect = requested.progress().attemptId();
        Set<String> runs = new HashSet<>();
        for (WorkItemEvent event : facts.subList(requestedAt, facts.size()))
            if ("BUILD_DISPATCH_CLAIMED".equals(event.milestone()) && event.control() != null && event.control().runId() != null) runs.add(event.control().runId());
        try (PreparedStatement ps = c.prepareStatement("SELECT approved_head,fix_payload FROM work_land_effect WHERE effect_id=? AND state='fixing'")) {
            ps.setObject(1, effect);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("The land fix of attempt " + effect + " has no fixing effect row");
                Payload payload = decode(effect, rs.getBytes(2));
                return Optional.of(new Fix(effect, requested.reason(), rs.getString(1), requested.progress().execution().pullRequest(),
                        requested.control().branch(), payload.baseBranch(), payload.baseTip(), payload.instruction(), Set.copyOf(runs)));
            }
        }
    }

    private static int lastRequest(List<WorkItemEvent> facts) {
        for (int index = facts.size() - 1; index >= 0; index--) if ("LAND_FIX_REQUESTED".equals(facts.get(index).milestone())) return index;
        throw new IllegalStateException("A land fix in progress has no request");
    }

    /** What the fix run's prompt gains after the task. */
    public static String promptSection(Fix fix) {
        return "\n\nThe forge refused to merge this task's pull request. This build fixes it on the pull request's own branch, "
                + "which is checked out at the head that was approved.\n" + fix.payload();
    }

    /** The fix for one refusal: the failed checks to make pass, or the base to merge in. */
    static Payload payload(String reason, PullRequestLander.LandState pr) {
        String base = pr.baseBranch();
        return switch (reason) {
            case "land_checks_failed" -> new Payload(base, null, failedChecks(pr.checks()));
            case "land_conflict" -> new Payload(base, pr.baseSha(), "The pull request conflicts with its base branch " + base + ". Merge `"
                    + MERGE_BASE + base + "` into this branch and resolve the conflicts; commit the merge.\n");
            case "land_branch_behind" -> new Payload(base, pr.baseSha(), "The pull request is behind its base branch " + base + ". Merge `"
                    + MERGE_BASE + base + "` into this branch; there are no conflicts to resolve. Commit the merge.\n");
            default -> throw new IllegalArgumentException("Not a land fix reason " + reason);
        };
    }

    private static String failedChecks(PullRequestLander.CheckSummary checks) {
        StringBuilder text = new StringBuilder("These checks failed on the pull request. Change the code so they pass, and commit the change.\n");
        List<PullRequestLander.FailedCheck> failed = checks == null ? List.of() : checks.failed();
        for (PullRequestLander.FailedCheck check : failed) {
            text.append("\nCheck: ").append(check.name()).append(" (").append(check.conclusion()).append(')');
            if (check.title() != null && !check.title().isBlank()) text.append("\nTitle: ").append(check.title());
            if (check.summary() != null && !check.summary().isBlank()) text.append("\nSummary:\n").append(check.summary());
            for (String annotation : check.annotations()) text.append("\n- ").append(annotation);
            text.append('\n');
        }
        return text.toString();
    }

    byte[] encode(UUID effect, Payload payload) {
        try { return encryption.encrypt(mapper.writeValueAsBytes(payload), aad(effect)); }
        catch (IOException failure) { throw new IllegalStateException("Cannot encode the land fix of " + effect, failure); }
    }

    private Payload decode(UUID effect, byte[] stored) {
        if (stored == null) throw new IllegalStateException("The land fix of " + effect + " carries no payload");
        try { return mapper.readValue(encryption.decrypt(stored, aad(effect)), Payload.class); }
        catch (IOException failure) { throw new IllegalStateException("Cannot read the land fix of " + effect, failure); }
    }

    private static String aad(UUID effect) {
        return "work-land-fix:" + effect;
    }
}
