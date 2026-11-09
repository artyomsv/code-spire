package dev.codespire.runworker;

import dev.codespire.harness.CredentialRoute;
import dev.codespire.harness.HarnessInvocation;
import dev.codespire.runtime.ContainerSpec;
import dev.codespire.runtime.RunUnitSpec;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Where a unit's model credential is held (harness reviews step 3): the invocation the arm builds the agent from, and,
 * when a model proxy holds the credential, the route it forwards by and the real credential it is handed.
 *
 * @param route          the model proxy's route, or null when the agent holds the credential
 * @param realCredential the worker's credential map, which goes to the model proxy's environment and nowhere else
 */
record AgentCredential(HarnessInvocation invocation, CredentialRoute route, Map<String, String> realCredential) {

    AgentCredential {
        Objects.requireNonNull(invocation, "invocation");
        realCredential = Map.copyOf(realCredential);
    }

    /** The agent holds the credential and talks to the vendor itself. */
    static AgentCredential direct(HarnessInvocation invocation) {
        return new AgentCredential(invocation, null, Map.of());
    }

    /** A model proxy holds {@code realCredential}; the agent's invocation holds placeholders only. */
    static AgentCredential proxied(HarnessInvocation placeholders, CredentialRoute route,
                                   Map<String, String> realCredential) {
        return new AgentCredential(placeholders, Objects.requireNonNull(route, "route"), realCredential);
    }

    /** {@code unit} as it is, or with the model proxy that holds the credential: its only destination. */
    RunUnitSpec attachModelProxy(RunUnitSpec unit, String modelProxyImage) {
        if (route == null) {
            return unit;
        }
        return unit.withModelProxy(new ContainerSpec(modelProxyImage, List.of(),
                ModelProxyEnvironment.of(route, realCredential), List.of()));
    }

    /** Never prints the credential: this record is one {@code log.info} away from a leak. */
    @Override
    public String toString() {
        return "AgentCredential[invocation=" + invocation + ", route=" + route + ", realCredential="
                + realCredential.keySet() + " (values withheld)]";
    }
}
