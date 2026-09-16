package dev.codespire.runtime;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * One trusted container, started so a person can sign a harness in (M3.5 part F).
 *
 * <p><b>Why this is not a {@link RunUnitSpec}.</b> That is three containers — an init clone, the agent
 * and the publisher — because a run needs all three and none of them is interchangeable. A sign-in has
 * no repository to clone, nothing to publish and no agent: it is one process waiting on a person. Filling
 * the other two slots with something inert would put a lie in the spec, and the watchdog and salvage
 * rules that read a run unit would then read this as a run that never finished.
 *
 * <p><b>Trusted, and the word carries the whole security argument.</b> This unit gets no repository, no
 * workspace, no prompt and no ticket text. Nothing an attacker can influence enters it, which is why its
 * output may be believed and read back. An agent container is the opposite and its output is never
 * believed. Anything that would carry untrusted input into a unit of this shape breaks the only property
 * that makes it safe, so the spec offers no place to put one: there is no mount, and the environment is
 * the deployment's own.
 *
 * @param unitId labels the container, so an abandoned one can be found and destroyed the way a run's is
 * @param image the image whose pinned CLI drives the sign-in. The same image the runs use, so the flow
 *     is measured against the version that will hold the credential.
 * @param command what to run. A list, never a string: there is no shell here and nothing to quote.
 * @param resultPath the one file the unit is expected to write, read back through the runtime's own copy
 *     channel and never through the unit's output. A credential printed to stdout is a credential in the
 *     daemon's logs, on disk, for as long as that log is kept.
 * @param wallClock the ceiling. A unit waiting on a person that nobody answers must still end.
 */
public record SignInUnitSpec(String unitId, String image, List<String> command, String resultPath,
                             EnterpriseEnvironment enterprise,
                             long memoryBytes, long nanoCpus, long diskBytes, Duration wallClock) {

    public SignInUnitSpec {
        Objects.requireNonNull(unitId, "unitId");
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(resultPath, "resultPath");
        Objects.requireNonNull(enterprise, "enterprise");
        Objects.requireNonNull(wallClock, "wallClock");
        command = List.copyOf(Objects.requireNonNull(command, "command"));
        if (unitId.isBlank()) {
            // Every container is labelled with it, so a blank one is undiscoverable: nothing can
            // attribute it afterwards and destroy targets nothing. Same rule as a run unit.
            throw new IllegalArgumentException("a sign-in unit needs a unitId");
        }
        if (image.isBlank()) throw new IllegalArgumentException("a sign-in unit needs an image");
        if (command.isEmpty()) throw new IllegalArgumentException("a sign-in unit needs a command");
        if (!resultPath.startsWith("/")) {
            throw new IllegalArgumentException("the result path must be absolute inside the unit, was: " + resultPath);
        }
        if (memoryBytes <= 0 || nanoCpus <= 0 || diskBytes <= 0) {
            throw new IllegalArgumentException("a sign-in unit needs real limits; unlimited is not a limit"
                    + " (memory=" + memoryBytes + ", nanoCpus=" + nanoCpus + ", disk=" + diskBytes + ")");
        }
        if (wallClock.isZero() || wallClock.isNegative()) {
            throw new IllegalArgumentException("a sign-in unit needs a wall clock, was: " + wallClock);
        }
    }
}
