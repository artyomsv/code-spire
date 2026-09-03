package dev.codespire.agentimage;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The checker against a REAL image on a real daemon.
 *
 * <p>{@link AgentImageVerifierTest} proves the mapping — a "no git" answer becomes a failure naming
 * git. Only this can prove the QUESTION was asked correctly: that the probe's shell script runs in
 * an unfamiliar image, that the entrypoint contract is exercised as a run exercises it, and that a
 * bundle really appears on a shared volume. A conformance checker whose own reference entrypoint is
 * never actually run is the shape this repository keeps finding.
 *
 * <p><b>Built here rather than pulled.</b> The shipped agent image installs a harness toolchain over
 * the network and takes minutes; what the contract is about is the shared entrypoint, which this
 * builds over a tiny base. So the image under test is minimal and REAL: the actual
 * {@code deploy/agent/spire-agent-entrypoint.sh}, not a copy that could drift from it.
 */
class ReferenceImageIT {

    private static final String CONFORMING = "spire-conformance-probe:conforming";

    private static final String AS_ROOT = "spire-conformance-probe:root";

    private static final String NO_TRUST_STORE = "spire-conformance-probe:no-ca";

    private static final DockerClient CLIENT = client();

    private static final List<String> BUILT = new ArrayList<>();

    /** Build contexts, deleted with the images they produced. */
    private static final List<Path> CONTEXTS = new ArrayList<>();

    private static DockerClient client() {
        DefaultDockerClientConfig config =
                DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        DockerHttpClient http = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .build();
        return DockerClientImpl.getInstance(config, http);
    }

    @AfterAll
    static void removeBuiltImagesAndContexts() {
        for (Path context : CONTEXTS) {
            try (var paths = Files.walk(context)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // A leaked temp file is tidier than a teardown that replaces the result.
                    }
                });
            } catch (IOException ignored) {
                // Same.
            }
        }
        for (String tag : BUILT) {
            try {
                CLIENT.removeImageCmd(tag).withForce(true).exec();
            } catch (RuntimeException alreadyGone) {
                // Nothing else to do; a leaked probe image is tidier than a failed teardown that
                // replaces the test's own result.
            }
        }
    }

    /**
     * The repository's real entrypoint, on a minimal base.
     *
     * <p>{@code asRoot} is the one difference in the second image, so a failure can only be the
     * clause under test — the discipline that makes a negative case worth having at all.
     */
    /** The image the contract describes: non-root, both mount points, git, a trust store. */
    private static String buildConforming() throws IOException {
        return buildProbeImage(CONFORMING, false, false);
    }

    /** The same image, running as uid 0. */
    private static String buildAsRoot() throws IOException {
        return buildProbeImage(AS_ROOT, true, false);
    }

    /** The same image with its trust store removed, and nothing else changed. */
    private static String buildWithoutTrustStore() throws IOException {
        return buildProbeImage(NO_TRUST_STORE, false, true);
    }

    /**
     * Two boolean flags, reached only through the three named builders above.
     *
     * <p>Call sites reading {@code buildProbeImage(NO_TRUST_STORE, false, true)} are exactly the
     * unreadability the no-flag-parameters rule targets, and one transposition silently builds a
     * different image than the test name claims.
     */
    private static String buildProbeImage(String tag, boolean asRoot, boolean withoutTrustStore)
            throws IOException {
        if (BUILT.contains(tag)) {
            // Built once per class. Two cases want the conforming image, and building it twice
            // also put its tag in BUILT twice, so teardown removed it and then threw.
            return tag;
        }
        // The assertion BEFORE the temp directory, or a wrong repo root leaks one per attempt.
        Path entrypoint = repoRoot().resolve("deploy/agent/spire-agent-entrypoint.sh");
        assertTrue(Files.isRegularFile(entrypoint),
                "the real entrypoint must be what is built, or this tests a copy that can drift");
        Path context = Files.createTempDirectory("spire-conformance-build-");
        CONTEXTS.add(context);
        Files.copy(entrypoint, context.resolve("spire-agent-entrypoint"));

        String dockerfile = String.join("\n",
                "FROM alpine:3.20",
                withoutTrustStore
                        ? "RUN apk add --no-cache git && rm -rf /etc/ssl/certs /etc/ssl/cert.pem"
                        : "RUN apk add --no-cache git ca-certificates",
                "RUN adduser -D -u 1001 agent \\",
                " && mkdir -p /workspace /handoff \\",
                " && chown 1001:1001 /workspace /handoff",
                // COPY then chmod, not COPY --chmod: docker-java drives the daemon's LEGACY
                // builder, which does not support that flag. The shipped Dockerfile uses it and is
                // built by the CLI, where BuildKit is the default.
                "COPY spire-agent-entrypoint /usr/local/bin/spire-agent-entrypoint",
                "RUN chmod 755 /usr/local/bin/spire-agent-entrypoint",
                "LABEL dev.codespire.agent.toolchain=none",
                "LABEL dev.codespire.agent.harness=conformance-probe",
                asRoot ? "USER 0:0" : "USER 1001:1001",
                "WORKDIR /workspace",
                "ENTRYPOINT [\"/usr/local/bin/spire-agent-entrypoint\"]",
                "");
        Files.writeString(context.resolve("Dockerfile"), dockerfile, StandardCharsets.UTF_8);

        CLIENT.buildImageCmd(context.toFile()).withTags(java.util.Set.of(tag))
                .exec(new com.github.dockerjava.api.command.BuildImageResultCallback())
                .awaitImageId();
        BUILT.add(tag);
        return tag;
    }

    /**
     * Handed in by Gradle, not guessed from the working directory.
     *
     * <p>The module directory's parent is right under Gradle and is still a guess — and
     * {@code spire-arch} solved this one module away, with {@code RootBuild.repoRoot()} reading an
     * explicit property and a comment saying why. Guessing fails loudly here rather than quietly,
     * but it fails on the entrypoint assertion, which names the wrong cause.
     */
    private static Path repoRoot() {
        String root = System.getProperty("spire.repoRoot");
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("spire.repoRoot is unset — the Gradle test task must "
                    + "pass it (see spire-agent-image/build.gradle.kts)");
        }
        return Path.of(root);
    }

    private static ConformanceReport verify(String image) {
        return AgentImageVerifier.againstDocker(CLIENT).verify(image);
    }

    /** The reference entrypoint is its own first test. */
    @Test
    void aConformingImagePassesEveryVerifiedClause() throws IOException {
        ConformanceReport report = verify(buildConforming());

        assertTrue(report.conforms(), report.render());
        assertEquals(Clauses.VERIFIED,
                report.verified().stream().map(ConformanceReport.Verification::id).toList());
    }

    /**
     * A real image that breaks ONE clause fails exactly that clause.
     *
     * <p>The negative half matters more than the positive one: a checker that passes everything is
     * indistinguishable from a checker that checks nothing. The trust store is the break to use,
     * because it is genuinely independent -- the entrypoint makes no TLS call, so nothing else can
     * fail as a consequence and the assertion can be an exact list.
     */
    @Test
    void anImageWithNoTrustStoreFailsExactlyThatClause() throws IOException {
        ConformanceReport report = verify(buildWithoutTrustStore());

        assertFalse(report.conforms(), report.render());
        assertEquals(List.of(Clauses.CA_CERTIFICATES),
                report.failures().stream().map(ConformanceReport.Verification::id).toList(),
                "one difference in the image must produce one failure: " + report.render());
    }

    /**
     * An image running as root fails the clause about it.
     *
     * <p>Asserted as MEMBERSHIP, not as the only failure -- a first version claimed the latter and
     * a real root image produced four. Root also breaks the handoff probe, and this test has not
     * established why; asserting "only this one" would encode a belief nobody verified, which is
     * the kind of claim this repository treats as a defect. The exact-list discrimination lives in
     * the trust-store case above, where the break really is isolated.
     */
    @Test
    void anImageRunningAsRootFailsTheNonRootClause() throws IOException {
        ConformanceReport report = verify(buildAsRoot());

        assertFalse(report.conforms(), report.render());
        assertTrue(report.failures().stream()
                        .anyMatch(failure -> failure.id().equals(Clauses.NON_ROOT)),
                report.render());
    }

    /**
     * A declaration is reported from the label and never verified, on a real image.
     *
     * <p>The probe image declares a toolchain of {@code none} and a harness that does not exist.
     * Both are reported as SAID, and the image still conforms — because nothing checked them, and
     * pretending otherwise is the blend this report shape prevents.
     */
    @Test
    void declaredClausesAreReportedSeparatelyFromVerifiedOnes() throws IOException {
        ConformanceReport report = verify(buildConforming());

        assertEquals(List.of(Clauses.TOOLCHAIN, Clauses.HARNESS),
                report.declared().stream().map(ConformanceReport.Declaration::id).toList());
        assertEquals("conformance-probe", report.declared().stream()
                .filter(declaration -> declaration.id().equals(Clauses.HARNESS))
                .findFirst().orElseThrow().claimed());
        assertTrue(report.conforms(),
                "a harness that does not exist is a claim, and no verified clause checked it");
    }
}
