package dev.codespire.orchestrator.work;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.contract.work.WorkPolicy;
import dev.codespire.contract.work.WorkPolicyLimits;
import dev.codespire.orchestrator.factory.BuildDefaults;
import dev.codespire.orchestrator.factory.FactoryPullRequests;
import dev.codespire.orchestrator.factory.RunLaunch;
import io.quarkus.test.junit.QuarkusMock;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A generated repository whose specification runs are dispatched to a TEST transport (M4 slice 3): the base branch
 * answers {@link #HEAD}, the setup is generated, and the label TEST-spec-approve selects spec approve.
 */
abstract class WorkSpecRunFixture extends WorkPreparedFixture {
    static final String HEAD = "d".repeat(40);

    @Inject BuildDefaults defaults;
    @Inject WorkDraftSweep draftSweep;
    @Inject WorkPrepareDispatcher prepare;

    final List<RunCommand.ExecuteWorkDraftRun> draftRuns = new ArrayList<>();
    final List<RunResult> proposed = new ArrayList<>();
    RuntimeException transportFailure;

    @BeforeEach
    void specRunSetup() {
        stubHead();
        defaults.save(repository, new BuildDefaults.Input(defaults.get(repository).revision(), "main", "codex", model, null, null,
                List.of(), null, BuildDefaults.GENERATED), "TEST-spec-admin");
        specApproveProfile();
        QuarkusMock.installMockForType(new WorkPrepareTransport() {
            @Override public RunLaunch.Outcome dispatch(RunCommand.ExecuteWorkDraftRun command) {
                try {
                    assertEquals(1, count("SELECT count(*) FROM work_prepare_effect WHERE run_id=? AND state='uncertain'", command.runId()),
                            "The draft claim must be committed before dispatch");
                } catch (SQLException failure) { throw new AssertionError(failure); }
                draftRuns.add(command);
                if (transportFailure != null) throw transportFailure;
                return new RunLaunch.Dispatched();
            }
        }, WorkPrepareTransport.class);
        QuarkusMock.installMockForType(new FactoryPullRequests() {
            @Override public void propose(RunResult result) { proposed.add(result); }
        }, FactoryPullRequests.class);
    }

    void stubHead() {
        forge.stubFor(get(urlEqualTo("/repos/" + scope + "/branches/main"))
                .willReturn(okJson("{\"name\":\"main\",\"commit\":{\"sha\":\"" + HEAD + "\"}}")));
    }

    /** Intake auto, spec approve, plan auto, under the label TEST-spec-approve. */
    private void specApproveProfile() {
        EnumMap<WorkPolicy.Phase, String> modes = new EnumMap<>(WorkPolicy.Phase.class);
        for (WorkPolicy.Phase phase : WorkPolicy.Phase.values()) modes.put(phase, switch (phase) {
            case DELIVER -> "pr"; case LAND -> "auto_if_green"; case SPEC -> "approve"; default -> "auto";
        });
        UUID id = UUID.randomUUID();
        extraProfiles.add(id);
        WorkPolicy.Profile created = policies.createVersion(new WorkPolicy.Profile(id, "TEST-spec-approve-" + id, 1,
                1_600_000_000 + Math.abs(id.hashCode() % 100_000_000),
                modes, new WorkPolicyLimits(3600, 5, 20, 7200, 2_000_000, 40, Set.of("TEST-sensitive/**"))));
        Map<String, WorkPolicyRegistry.Pin> mapping = new HashMap<>();
        profiles.forEach((name, profile) -> mapping.put("TEST-" + name, new WorkPolicyRegistry.Pin(profile.id(), 1)));
        mapping.put("TEST-spec-approve", new WorkPolicyRegistry.Pin(created.id(), 1));
        policies.save(repository, new WorkPolicyRegistry.Input(policies.get(repository).revision(),
                new WorkPolicyRegistry.Pin(profiles.get("autonomous").id(), 1), mapping));
    }

    String issuePath(int number) { return "/repos/" + scope + "/issues/" + number; }

    /** The ticket {@link #admit} stubs, with another body: the same identity, title and label. */
    void stubTicket(String profile, int number, String body) {
        var ticket = mapper.createObjectNode().put("id", 50000 + number).put("number", number).put("repository_url", forge.baseUrl() + "/repos/" + scope)
                .put("html_url", forge.baseUrl() + "/" + scope + "/issues/" + number).put("title", "TEST-prepared-task").put("body", body)
                .put("state", "open");
        ticket.putArray("labels").add("TEST-" + profile);
        forge.stubFor(get(urlEqualTo(issuePath(number))).willReturn(okJson(ticket.toString())));
    }

    /** Admitted under the profile, the ticket's comments answered, and the specification run started by the sweep. */
    String started(String profile, int number) throws Exception {
        String id = admit(profile, number);
        forge.stubFor(get(urlEqualTo(issuePath(number) + "/comments?per_page=100&page=1")).willReturn(okJson("[]")));
        forge.stubFor(post(urlEqualTo(issuePath(number) + "/comments")).willReturn(okJson("{\"id\":70001}")));
        draftSweep.sweep();
        assertState(store.load(id), "spec", "active", "phase_started");
        return id;
    }

    RunCommand.ExecuteWorkDraftRun dispatched() {
        int before = draftRuns.size();
        prepare.drain();
        assertEquals(before + 1, draftRuns.size(), "one draft run is dispatched");
        return draftRuns.getLast();
    }

    static void assertState(WorkItemEvent item, String phase, String status, String reason) {
        assertEquals(phase + " / " + status + " / " + reason, item.phase() + " / " + item.workflowStatus() + " / " + item.reason());
    }

    String effectState(UUID attempt) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT state FROM work_prepare_effect WHERE attempt_id=?")) {
            ps.setObject(1, attempt);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }
}
