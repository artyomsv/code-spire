package dev.codespire.arch;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The credential pool's automatic rotation has no producer, and this fails the build on the day it
 * gets one.
 *
 * <p>{@code RunCredentialFeedback} retires a harness credential when a run reports
 * {@code CREDENTIAL_REJECTED}. A review established by grep that <b>nothing emits that cause</b>: the
 * harness tier's own {@code FailureCause} has no credential value, the publisher's vocabulary has
 * none, and {@code RunFailureCause.ALIASES} maps nothing onto it. A provider refusing a key surfaces
 * as {@code PROVIDER_ERROR} then {@code MODEL_UNAVAILABLE}, which the feedback rule deliberately
 * ignores — so a dead key stays in rotation and the pool hands it out again. That is verbatim the
 * state the pool's own migration header calls "how a pool quietly stops rotating while looking
 * healthy", shipped by the change that wrote the sentence.
 *
 * <p><b>This asserts the GAP, which is unusual and is the point.</b> Three places describe the
 * consequence of there being no producer — the feedback class, the pool, and a debt entry — and all
 * three become wrong the moment somebody adds one. A guard that failed while the producer was missing
 * would be a permanently red build about known work. A guard that fails when it ARRIVES makes the
 * documentation self-correcting: whoever wires it up cannot land without reading why it was absent.
 *
 * <p>Same shape as the neutrality scan's stale-allowlist rule, which fails when an exemption stops
 * being needed rather than when it starts.
 *
 * <p>It lives here rather than beside the pool because this module's test task already declares every
 * module's main sources as a Gradle input. Without that, editing another module reports a cached PASS
 * from the very change the check exists to catch — measured, not assumed: the first version of this
 * guard lived in the orchestrator, and adding a pretend producer to the harness tier left its task
 * UP-TO-DATE and the guard silent.
 */
class CredentialRefusalHasNoProducerTest {

    private static final String CAUSE = "CREDENTIAL_REJECTED";

    /**
     * The sites that may name the cause without being a producer: the module that DEFINES it, the one
     * that CONSUMES it, and one unrelated attention row for SCM and LLM registry credential health —
     * same words, different subject. Listed rather than pattern-matched, so a real producer cannot
     * hide behind the coincidence.
     */
    private static final List<String> DEFINITION_AND_CONSUMERS = List.of(
            "spire-contract/src/main/java/dev/codespire/contract/event/RunFailureCause.java",
            "spire-orchestrator/src/main/java/dev/codespire/orchestrator/factory/RunCredentialFeedback.java",
            "spire-orchestrator/src/main/java/dev/codespire/orchestrator/attention/AttentionQueries.java");

    @Test
    void nothingProducesTheCredentialRefusalCauseYet() throws IOException {
        List<String> naming = mainSourcesNaming();

        assertTrue(naming.size() >= DEFINITION_AND_CONSUMERS.size(),
                "the scan reached almost nothing, which is a broken scan rather than a clean result: "
                        + naming);
        List<String> unexpected = new ArrayList<>(naming);
        unexpected.removeAll(DEFINITION_AND_CONSUMERS);

        assertEquals(List.of(), unexpected, """
                Something now names CREDENTIAL_REJECTED besides its definition and its consumers, which \
                probably means the credential pool's automatic rotation finally has a producer. That is \
                good news, and it makes three pieces of documentation wrong. Before deleting this test, \
                update RunCredentialFeedback's javadoc (it says nothing produces this), \
                HarnessCredentialPool's javadoc (it says neither exhaustion state has an automatic \
                producer), and \
                techdebt/spire-orchestrator/4-2-no-harness-reports-a-rate-limit-so-the-pool-only-heals-by-hand.md. \
                Then add a seam test proving a real refusal reaches the pool.""");
    }

    private static List<String> mainSourcesNaming() throws IOException {
        Path root = repoRoot();
        List<String> found = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(root)) {
            for (Path file : tree.filter(Files::isRegularFile).toList()) {
                String path = root.relativize(file).toString().replace(File.separatorChar, '/');
                if (!path.endsWith(".java") || !path.contains("/src/main/java/")) {
                    continue;
                }
                if (Files.readString(file, StandardCharsets.UTF_8).contains(CAUSE)) {
                    found.add(path);
                }
            }
        }
        return found;
    }

    private static Path repoRoot() {
        String root = System.getProperty("spire.repoRoot");
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("spire.repoRoot is unset — the Gradle test task must pass it "
                    + "(see spire-arch/build.gradle.kts)");
        }
        return Path.of(root);
    }
}
