package dev.codespire.runtime.k8s;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5-AC-2: on a cluster whose CNI enforces no network policy (flannel), a unit fails
 * {@code network_policy_not_enforced} and no agent ever runs. The seal is the only guard here: the policies exist
 * and do nothing. The unit uses real images throughout, so without the seal its agent would run.
 */
@Tag("open-cluster")
class OpenClusterIT {

    private String namespace;

    @AfterEach
    void removeTheNamespace() {
        ClusterUnits.deleteNamespace(namespace);
    }

    @Test
    void aUnitOnAClusterThatEnforcesNoPolicyNeverRunsItsAgent() {
        namespace = KindCluster.newNamespace("test-open-unit");
        KubernetesRunRuntime runtime = ClusterUnits.runtime(ClusterUnits.config(namespace));
        String runId = ClusterUnits.runId();

        UnitNotStarted failed = assertThrows(UnitNotStarted.class,
                () -> runtime.create(ClusterUnits.unit(runId, "echo TEST-agent-line")));

        assertTrue(failed.detail().startsWith(UnitStart.POLICY_NOT_ENFORCED), failed.detail());
        assertTrue(ClusterUnits.agentNeverStarted(ClusterUnits.pod(namespace, UnitNames.unitPod(runId))),
                "the agent container started on a cluster that enforces no policy");
    }
}
