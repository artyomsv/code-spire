package dev.codespire.runtime.docker;

import dev.codespire.runtime.EgressAttempt;
import dev.codespire.runtime.EgressPolicy;
import dev.codespire.runtime.ModelProxyReport;
import dev.codespire.runtime.RunHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.codespire.runtime.docker.ModelProxyUnits.MODEL_CALL;
import static dev.codespire.runtime.docker.ModelProxyUnits.REACHED_MODEL;
import static dev.codespire.runtime.docker.ModelProxyUnits.modelCall;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What crosses a proxied unit on a real daemon: the agent reaches the vendor through the model proxy and nothing else,
 * the egress proxy records and enforces the model proxy's calls, and the report counts a refused credential.
 */
class DockerModelProxyTrafficIT {

    private final DockerUnits units = new DockerUnits();

    private final DockerRunRuntime runtime = units.runtime();

    private final ModelProxyUnits proxied = new ModelProxyUnits(units);

    @BeforeAll
    static void buildTheImages() throws Exception {
        ModelProxyUnits.buildTheImages();
    }

    @AfterEach
    void tearDownEveryUnit() {
        units.tearDown();
    }

    @Test
    void theAgentReachesOnlyTheModelProxy() {
        String upstream = proxied.startUpstream(200);
        String probeEgress = "OUT=$(wget -Y off -T 5 -O /dev/null " + EgressPolicy.PROXY_ADDRESS + "/ 2>&1); "
                + "case \"$OUT\" in *'bad address'*|*'timed out'*|*'refused'*|*'unreachable'*) echo EGRESS-UNREACHABLE;; "
                + "*) echo \"EGRESS-ANSWERED $OUT\";; esac; ";
        RunHandle handle = units.start(proxied.proxiedUnit("run_model_reach",
                "env | grep -i '_proxy' || echo NO-PROXY-VARIABLES; " + MODEL_CALL + probeEgress
                        + modelCall("OUTSIDE", "http://" + upstream + "/v1/responses"),
                ModelProxyUnits.Vendor.enforcing(upstream)));

        List<String> agent = units.agentLog(handle);
        runtime.salvage(handle);

        assertTrue(agent.contains("NO-PROXY-VARIABLES"), "a proxied agent is handed no proxy: " + agent);
        assertTrue(agent.contains(REACHED_MODEL), "the agent must reach the vendor through the model proxy: " + agent);
        assertTrue(agent.contains("EGRESS-UNREACHABLE"), "the agent must not reach the egress proxy: " + agent);
        assertTrue(agent.contains("OUTSIDE-FAILED"), "the agent must reach nothing outside: " + agent);
    }

    @Test
    void theModelProxysTrafficIsRecordedByTheEgressProxy() {
        String upstream = proxied.startUpstream(200);
        RunHandle handle = units.start(proxied.proxiedUnit("run_model_recorded", MODEL_CALL,
                ModelProxyUnits.Vendor.enforcing(upstream)));

        List<String> agent = units.agentLog(handle);
        runtime.salvage(handle);
        List<EgressAttempt> egress = runtime.egressOf(handle);

        assertTrue(agent.contains(REACHED_MODEL), agent.toString());
        assertTrue(egress.contains(new EgressAttempt(upstream, 80, true)),
                units.diagnosis(egress, "run_model_recorded"));
        assertEquals(new ModelProxyReport(1, 0, List.of()), runtime.modelProxyOf(handle));
    }

    @Test
    void aProxiedUnitEnforcesEvenWhenTheGlobalModeIsReport() {
        // REPORT would let everything through; a proxied unit's egress proxy enforces the spec's hosts whatever the
        // mode, so the upstream, not among them, is refused.
        String upstream = proxied.startUpstream(200);
        RunHandle handle = units.start(proxied.proxiedUnit("run_model_enforced", MODEL_CALL,
                ModelProxyUnits.Vendor.reporting(upstream)));

        List<String> agent = units.agentLog(handle);
        runtime.salvage(handle);
        List<EgressAttempt> egress = runtime.egressOf(handle);

        assertTrue(agent.contains("MODEL-FAILED"), "an undeclared host must not answer: " + agent);
        assertTrue(egress.contains(new EgressAttempt(upstream, 80, false)),
                "the refusal is recorded: " + units.diagnosis(egress, "run_model_enforced"));
    }

    @Test
    void theReportCountsARejectedModelCall() {
        String upstream = proxied.startUpstream(401);
        RunHandle handle = units.start(proxied.proxiedUnit("run_model_rejected", MODEL_CALL,
                ModelProxyUnits.Vendor.enforcing(upstream)));

        units.agentLog(handle);
        runtime.salvage(handle);

        ModelProxyReport report = runtime.modelProxyOf(handle);
        assertEquals(1, report.rejected(), "a 401 on the model call is a refused credential: " + report);
        assertEquals(1, report.requests(), report.toString());
    }
}
