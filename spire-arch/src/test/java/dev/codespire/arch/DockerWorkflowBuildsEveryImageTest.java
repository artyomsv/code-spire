package dev.codespire.arch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DockerWorkflowBuildsEveryImageTest {

    /**
     * Every Dockerfile that upgrades its OS packages is one docker.yml builds. The other two checks
     * read only images the workflow names, so a published image the workflow leaves out — built by
     * hand, as the publisher, model proxy and egress proxy were until 2026-11-14 — passed them all
     * while nothing ever sent it a per-build value. Test fixtures under {@code src/test} are not
     * published and are exempt.
     */
    @Test
    void everyImageThatUpgradesOsPackagesIsBuiltByTheWorkflow() throws IOException {
        Set<Path> built = new LinkedHashSet<>(DockerBuildSources.builtImages().values());
        List<String> unbuilt = new ArrayList<>();
        for (Path file : DockerBuildSources.dockerfilesThatUpgradeOsPackages().keySet()) {
            String relative = RootBuild.repoRoot().relativize(file).toString().replace('\\', '/');
            if (!relative.contains("/src/test/") && !built.contains(file)) {
                unbuilt.add(relative);
            }
        }

        assertEquals(List.of(), unbuilt,
                "these Dockerfiles upgrade their base OS packages but no matrix entry in docker.yml "
                        + "builds them, so nothing sends them a per-build APK_UPGRADE_BUST and the upgrade "
                        + "layer caches forever wherever they are built. Add a matrix entry with "
                        + "`apkUpgrade: true`.");
    }
}
