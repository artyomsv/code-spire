package dev.codespire.arch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An image that upgrades its base OS packages must actually run that upgrade on every build.
 *
 * <p>{@code RUN apk --no-cache upgrade} has no input that changes. With {@code cache-from} restoring
 * a layer cache, BuildKit re-used it on every build, so the upgrade ran exactly once — and its only
 * cache key was the base image, which means the mitigation refreshed precisely when the base
 * retagged. That is the wait it exists to skip, so the whole thing was inert while three files said
 * it worked.
 *
 * <p><b>Measured, not inferred.</b> Docker workflow run 33810550375 logged
 * {@code #48 [stage-1 2/10] RUN apk --no-cache upgrade} followed by {@code #48 CACHED} for all three
 * services, while 102 Trivy alerts stood open against openssl 3.5.7-r0 and libexpat 2.8.3-r0 — both
 * of which the live Alpine index had already fixed (3.5.8-r0, 2.8.4-r0), and which one fresh
 * {@code apk --no-cache upgrade} on the same base installs.
 *
 * <p>Two halves, and either alone is inert, which is why one check holds both:
 *
 * <ul>
 *   <li><b>The Dockerfile must reference the argument in the upgrading command.</b> BuildKit keys a
 *       {@code RUN} on the build args it actually references, so an {@code ARG} the command never
 *       mentions changes no cache key. Declaring it and not using it looks exactly like a fix.</li>
 *   <li><b>The workflow must pass a value that differs per build.</b> {@code github.run_id} is the
 *       one already in scope that is unique per run. A constant here restores the original defect
 *       with more ceremony.</li>
 * </ul>
 *
 * <p>The reverse direction is asserted too: an image with no upgrade must NOT be sent the argument.
 * An unconsumed build arg warns on every build, and a warning nobody can act on trains people past
 * the ones they can.
 *
 * <p>Reads both files as source text, like every other check in this module. The Dockerfile list is
 * <b>derived</b> by scanning the tree rather than declared, so an image added later inherits the
 * rule instead of quietly escaping it.
 */
class ApkUpgradeIsNotCachedTest {

    /** The build argument whose value must change per build for the upgrade layer to re-run. */
    private static final String BUST = "APK_UPGRADE_BUST";



    @Test
    void everyImageThatUpgradesOsPackagesBustsTheLayerCache() throws IOException {
        Map<Path, String> upgrading = DockerBuildSources.dockerfilesThatUpgradeOsPackages();

        assertFalse(upgrading.isEmpty(),
                "no Dockerfile in this repository was found to upgrade its base OS packages. Either "
                        + "the scan stopped reaching the tree or the mitigation was removed — both are "
                        + "failures, and an empty result must never read as a pass.");
        assertTrue(upgrading.containsKey(RootBuild.repoRoot().resolve("Dockerfile")),
                "the root Dockerfile builds all three services and is where this defect was found; "
                        + "the scan no longer sees its `apk --no-cache upgrade`, so it is reading the "
                        + "wrong tree or joining continuations wrongly. Found: " + upgrading.keySet());

        List<String> unbusted = new ArrayList<>();
        for (Map.Entry<Path, String> file : upgrading.entrySet()) {
            if (!file.getValue().contains(BUST)) {
                unbusted.add(RootBuild.repoRoot().relativize(file.getKey()).toString());
            }
        }

        assertEquals(List.of(), unbusted,
                "these Dockerfiles upgrade their base OS packages in a RUN that does not reference "
                        + BUST + ". The layer then has no input that changes, so BuildKit restores it "
                        + "from cache and the upgrade runs once — refreshing only when the base image "
                        + "retags, which is the wait it exists to skip. Add `ARG " + BUST + "=local` "
                        + "and echo the value inside the same RUN: an ARG the command never mentions "
                        + "changes no cache key.");
    }

    @Test
    void theWorkflowSendsAValueThatDiffersPerBuild() throws IOException {
        String workflow = RootBuild.read(".github/workflows/docker.yml");

        assertTrue(workflow.contains(BUST + "={0}', github.run_id"),
                "docker.yml no longer passes " + BUST + " from github.run_id. A constant value keys "
                        + "the upgrade layer the same way on every build, which is the original defect "
                        + "with an argument added. github.run_id is unique per run and already in "
                        + "scope.");
    }

    @Test
    void everyBuiltImageIsSentTheArgumentExactlyWhenItConsumesIt() throws IOException {
        Set<Path> upgrading = DockerBuildSources.dockerfilesThatUpgradeOsPackages().keySet();
        Map<String, Path> built = DockerBuildSources.builtImages();

        assertFalse(built.isEmpty(),
                "no `- name:` entry was parsed out of docker.yml's build matrix. An empty matrix "
                        + "satisfies every assertion below, so failing to parse is an error here "
                        + "rather than an empty answer.");

        String workflow = RootBuild.read(".github/workflows/docker.yml");
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, Path> image : built.entrySet()) {
            boolean consumes = upgrading.contains(image.getValue());
            boolean sent = DockerBuildSources.entryBody(workflow, image.getKey()).contains("apkUpgrade: true");
            if (consumes && !sent) {
                wrong.add(image.getKey() + " builds "
                        + RootBuild.repoRoot().relativize(image.getValue())
                        + ", which consumes " + BUST + ", but its matrix entry sets no "
                        + "`apkUpgrade: true` — the build-args line then renders empty and the "
                        + "upgrade layer caches forever");
            } else if (!consumes && sent) {
                wrong.add(image.getKey() + " is sent " + BUST + " but its Dockerfile never consumes "
                        + "it — Docker warns about an unconsumed build arg on every build, and a "
                        + "warning nobody can act on trains people past the ones they can");
            }
        }

        assertEquals(List.of(), wrong,
                "docker.yml's matrix disagrees with the Dockerfiles it builds");
    }
}
