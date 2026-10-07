package dev.codespire.runtime;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One verify attempt against a held build (M4, spec §3.1): a {@code prepare} container in the trusted publisher
 * image that rebuilds the checkpoint into a new volume, then one agent-image container per check command.
 *
 * <p>The checks run code the agent wrote, so what they can reach is fixed here, where the unit is built:
 * only the new verify volume, no handoff, no credential. The prepare container holds the read credential and
 * runs nothing the agent wrote.
 *
 * @param timeout the limit for prepare and every check together
 */
public record VerifyUnitSpec(String runId, UUID attemptId, ContainerSpec prepare, List<ContainerSpec> checks,
                             EnterpriseEnvironment enterprise, long memoryBytes, long nanoCpus, long diskBytes,
                             Duration timeout) {
    /** The verify volume's logical name is this prefix plus the attempt id. */
    public static final String VOLUME_PREFIX = "verify-";
    public static final String HANDOFF = "handoff";
    public static final String WORKSPACE_PATH = "/workspace";
    public static final int MAX_CHECKS = 20;

    /** A variable a check could read a secret through: refused by name, whatever its value. */
    private static final Pattern CREDENTIAL_NAME = Pattern.compile("(?i)SPIRE_.*|.*(SECRET|TOKEN|KEY|PASSWORD|CREDENTIAL).*");

    public VerifyUnitSpec {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(prepare, "prepare");
        Objects.requireNonNull(enterprise, "enterprise");
        Objects.requireNonNull(timeout, "timeout");
        checks = List.copyOf(Objects.requireNonNull(checks, "checks"));
        if (runId.isBlank()) throw new IllegalArgumentException("a verify unit names its held run");
        if (memoryBytes <= 0 || nanoCpus <= 0 || diskBytes <= 0) throw new IllegalArgumentException("a verify unit needs positive resource limits");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("a verify unit needs a time limit");
        if (checks.size() > MAX_CHECKS) throw new IllegalArgumentException("a verify unit runs at most " + MAX_CHECKS + " checks");
        String volume = VOLUME_PREFIX + attemptId;
        requirePrepareMounts(prepare, volume);
        for (ContainerSpec check : checks) requireContainedCheck(check, volume);
    }

    /** The verify volume's logical name, which the runtime scopes to this run. */
    public String volume() {
        return VOLUME_PREFIX + attemptId;
    }

    private static void requirePrepareMounts(ContainerSpec prepare, String volume) {
        boolean handoff = false, workspace = false;
        for (Mount mount : prepare.mounts()) {
            if (mount.run() != null) throw new IllegalArgumentException("prepare reads its own run's handoff only");
            if (mount.volume().equals(HANDOFF) && mount.readOnly()) handoff = true;
            else if (mount.volume().equals(volume) && !mount.readOnly() && mount.path().equals(WORKSPACE_PATH)) workspace = true;
            else throw new IllegalArgumentException("prepare mounts the handoff read-only and the verify volume only, not " + mount);
        }
        if (!handoff || !workspace) throw new IllegalArgumentException("prepare needs the handoff read-only and the verify volume writable");
    }

    private static void requireContainedCheck(ContainerSpec check, String volume) {
        if (check.entrypoint() == null) {
            throw new IllegalArgumentException("a check overrides the agent image's entrypoint, which would commit and bundle");
        }
        if (check.mounts().size() != 1 || !check.mounts().getFirst().equals(Mount.writable(volume, WORKSPACE_PATH))) {
            throw new IllegalArgumentException("a check mounts the verify volume at " + WORKSPACE_PATH + " and nothing else, was " + check.mounts());
        }
        for (String name : check.environment().keySet()) {
            if (false) { // MUTANT
                throw new IllegalArgumentException("a check receives no credential, and " + name + " could carry one");
            }
        }
    }
}
