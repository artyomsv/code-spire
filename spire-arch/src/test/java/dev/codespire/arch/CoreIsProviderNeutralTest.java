package dev.codespire.arch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The core must not know which integration it is talking to — the SCM it reviews on, or the context
 * sources it pulls from.
 *
 * <p>Adapters are swappable only while the shared code makes no provider-dependent
 * decision. Once a saga, worker or read model branches on "is this GitLab?", every
 * later change to that file risks silently breaking the other two providers — and the
 * bug shows up in production, on one platform, not in a test. This check turns that
 * from something a reviewer has to notice into something the build refuses.
 *
 * <p>It has a known limit: it matches provider NAMES, and a provider assumption can be stated without
 * naming one ("ids are monotonic" is true of a comment id and false of a discussion id). Those are
 * found by reviewing semantics, not by this check — see ADR-020.
 *
 * <p>The rule is about decisions, not vocabulary:
 * <ul>
 *   <li><b>Rejected</b> — branching on a provider, or shared code having to choose
 *       between provider-shaped alternatives.</li>
 *   <li><b>Allowed</b> — one rule stated in domain terms that every adapter satisfies.
 *       "Ownership is keyed by the thread a reply arrives under" holds for a comment id
 *       on GitHub and a discussion id on GitLab; the core never learns the difference.</li>
 *   <li><b>Allowed</b> — the composition roots, whose entire job is picking an adapter,
 *       and the enum that declares the names. Those are the {@link #ALLOWED} entries.</li>
 * </ul>
 */
class CoreIsProviderNeutralTest {

    /**
     * The modules that must stay neutral: the domain contract and the three services.
     *
     * <p>The gateway counts. Its per-provider ingress classes live in the adapters, so what
     * remains here is the shared resolve-verify-translate-scope-publish edge every provider
     * flows through — exactly the code where a provider assumption is most costly.
     */
    private static final List<String> CORE_MODULES =
            List.of("spire-contract", "spire-orchestrator", "spire-review-worker", "spire-gateway");

    /**
     * Both plugin axes: the SCM the review runs on, and the context sources it pulls from. They pose
     * the same risk — core code that knows one integration's format or capability — so they get the
     * same rule.
     *
     * <p>No word boundaries: the leak may be a type ({@code BitbucketApiException}), a method
     * ({@code githubConfig}) or a string ({@code "gitlab"}), and an anchored pattern would miss names
     * embedded in a longer identifier.
     */
    private static final Pattern PROVIDER_NAME =
            Pattern.compile("(?i)(bitbucket|github|gitlab|jira|confluence)");

    /**
     * Files permitted to name a provider, each with the reason it is not a leak. Adding
     * an entry is a deliberate, reviewable act — that is the whole point of the list.
     */
    private static final Map<String, String> ALLOWED = allowlist();

    private static Map<String, String> allowlist() {
        Map<String, String> allowed = new LinkedHashMap<>();
        allowed.put("spire-contract/src/main/java/dev/codespire/contract/port/ScmType.java",
                "Declares the provider-type names themselves — the single source of the wire strings.");
        allowed.put("spire-orchestrator/src/main/java/dev/codespire/orchestrator/provider/ProviderClients.java",
                "Composition root: maps a provider type to its adapter. Choosing an adapter IS its job.");
        allowed.put("spire-orchestrator/src/main/java/dev/codespire/orchestrator/ingress/PrUrlParsers.java",
                "Composition root: assembles the per-provider PR-URL parsers; each URL grammar stays in its adapter.");
        allowed.put("spire-review-worker/src/main/java/dev/codespire/worker/adapters/WorkerScmClients.java",
                "Composition root: maps a credential's type to its adapter.");
        allowed.put("spire-review-worker/src/main/java/dev/codespire/worker/adapters/StubScm.java",
                "Dev/test stub. The ports it implements require an ScmType, but the value is never dispatched "
                        + "on — WorkerScmClients injects the stub directly and never resolves it by type.");
        // The gateway's per-provider webhook endpoints. These cannot live in the adapter modules —
        // those are deliberately framework-free, and these are JAX-RS resources — so the gateway is
        // where each provider's endpoint is composed. One thin factory per provider, no shared logic.
        allowed.put("spire-gateway/src/main/java/dev/codespire/gateway/GitHubWebhookResource.java",
                "Composition point: one provider's webhook endpoint. Verification and translation are the "
                        + "adapter ingress's; this only mounts the path and builds it.");
        allowed.put("spire-gateway/src/main/java/dev/codespire/gateway/GitLabWebhookResource.java",
                "Composition point: one provider's webhook endpoint (as above).");
        allowed.put("spire-gateway/src/main/java/dev/codespire/gateway/BitbucketWebhookResource.java",
                "Composition point: one provider's webhook endpoint (as above).");
        allowed.put("spire-gateway/src/main/java/dev/codespire/gateway/WebhookProviders.java",
                "Composition root: enumerates the endpoints above by referencing their own constants, so the "
                        + "shared registry edge validates against them without holding a second list of names.");
        // The context axis. Same rule, same shape of exemption: the roots that CHOOSE a source are
        // allowed to name one; the pipeline that runs the review is not.
        allowed.put("spire-review-worker/src/main/java/dev/codespire/worker/adapters/WorkerContextClients.java",
                "Composition root: maps a context credential's type to its provider.");
        allowed.put("spire-review-worker/src/main/java/dev/codespire/worker/adapters/WorkerContextReferences.java",
                "Composition root: lists the reference extractors. Extraction cannot go through "
                        + "ContextProvider (it runs before credentials are brokered), so naming them here is "
                        + "what keeps DiffWorker and ContextWorker free of any source's syntax.");
        allowed.put("spire-orchestrator/src/main/java/dev/codespire/orchestrator/context/ContextProviderResource.java",
                "Composition root for the Settings -> Context surface: builds a provider per type to run its "
                        + "connectivity check and preview. Its operator-facing messages name the source the "
                        + "operator configured, which is the point of them.");
        allowed.put("spire-orchestrator/src/main/java/dev/codespire/orchestrator/context/ContextKeyValidator.java",
                "Composition root: maps a context type to the API path its connectivity check pings.");
        allowed.put("spire-orchestrator/src/main/java/dev/codespire/orchestrator/factory/FactoryCloneUrls.java",
                "Composition root: maps a provider's API base URL to its clone host. GitHub's cloud API "
                        + "answers on api.github.com while clones go to github.com; a GHE server keeps "
                        + "both on one host. That mapping is provider knowledge and this is its one home.");
        return Collections.unmodifiableMap(allowed);
    }

    @Test
    void coreModulesNameNoIntegrationProviderOutsideTheAllowlist() {
        List<String> violations = new ArrayList<>();
        for (Path source : coreSources()) {
            String relative = relative(source);
            if (!ALLOWED.containsKey(relative)) {
                violations.addAll(providerNamesIn(source, relative));
            }
        }
        if (!violations.isEmpty()) {
            fail(report(violations));
        }
    }

    /**
     * An allowlist with no expiry silently turns into a set of permanent blanket
     * exemptions: a file gets cleaned up, its entry stays, and the next leak into that
     * same file is waved through. So a dead entry fails too.
     */
    @Test
    void theAllowlistCarriesNoStaleEntries() {
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, String> entry : ALLOWED.entrySet()) {
            Path source = repoRoot().resolve(entry.getKey());
            if (!Files.isRegularFile(source)) {
                stale.add(entry.getKey() + "\n      file no longer exists — drop the entry");
            } else if (providerNamesIn(source, entry.getKey()).isEmpty()) {
                stale.add(entry.getKey() + "\n      no longer names a provider — drop the entry so the file "
                        + "is protected again");
            }
        }
        if (!stale.isEmpty()) {
            fail("Stale ALLOWED entries in " + CoreIsProviderNeutralTest.class.getSimpleName() + ":\n\n  "
                    + String.join("\n  ", stale)
                    + "\n\nEach exemption must still be earning its place.\n");
        }
    }

    /**
     * Guards the guard. A wrong repo root or a renamed module would make every check
     * above pass by scanning nothing at all — the one failure mode of a check like this
     * that nobody would notice.
     */
    @Test
    void theScanReachesEveryCoreModulesSources() {
        List<Path> sources = coreSources();
        assertTrue(sources.size() > 100,
                "expected the core modules' sources (~165 files), scanned only " + sources.size());
        for (String module : CORE_MODULES) {
            long scanned = sources.stream().filter(p -> relative(p).startsWith(module + "/")).count();
            assertTrue(scanned >= 5, "only " + scanned + " sources scanned for " + module);
        }
    }

    /** Every code line naming a provider, as {@code path:line} plus the original line. */
    private static List<String> providerNamesIn(Path source, String relative) {
        String original = read(source);
        String[] code = JavaSource.withoutComments(original).split("\n", -1);
        String[] raw = original.split("\n", -1);
        List<String> found = new ArrayList<>();
        for (int i = 0; i < code.length; i++) {
            if (PROVIDER_NAME.matcher(code[i]).find()) {
                found.add(relative + ":" + (i + 1) + "\n      " + raw[i].strip());
            }
        }
        return found;
    }

    private static String report(List<String> violations) {
        StringBuilder message = new StringBuilder();
        message.append(violations.size()).append(" provider-specific reference(s) in a core module:\n\n");
        violations.forEach(violation -> message.append("  ").append(violation).append("\n"));
        message.append("""

                Core modules (spire-contract, spire-orchestrator, spire-review-worker, spire-gateway) must not
                name an SCM or context provider. A provider-dependent decision in shared code means every
                later edit to that file risks silently breaking the other providers, on one
                platform, in production. Comments are exempt — documenting WHY a neutral design
                exists is encouraged.

                Resolve one of these ways, in order of preference:

                  1. State the difference as a neutral capability on the SPI port and let each
                     adapter satisfy it. IdentitySource.whoamiOrValidate(workspace) is the worked
                     example: Bitbucket's access tokens cannot call /user, so the Bitbucket
                     adapter overrides the method with its own fallback and the caller asks one
                     question without learning which providers behave which way.
                  2. Move the choice into a composition root that already selects adapters
                     (ProviderClients / WorkerScmClients) and return a neutral result.
                  3. Only if neither fits: add the file to ALLOWED with a one-line reason, making
                     the exemption explicit and reviewable.
                """);
        return message.toString();
    }

    private static Path repoRoot() {
        String root = System.getProperty("spire.repoRoot");
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("spire.repoRoot is unset — the Gradle test task must pass it "
                    + "(see spire-arch/build.gradle.kts)");
        }
        return Path.of(root);
    }

    private static List<Path> coreSources() {
        List<Path> sources = new ArrayList<>();
        for (String module : CORE_MODULES) {
            Path main = repoRoot().resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(main)) {
                throw new IllegalStateException("Not a source directory: " + main
                        + " — has the module been renamed? Update CORE_MODULES.");
            }
            try (Stream<Path> tree = Files.walk(main)) {
                tree.filter(path -> path.toString().endsWith(".java")).forEach(sources::add);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot walk " + main, e);
            }
        }
        return sources;
    }

    private static String relative(Path source) {
        return repoRoot().relativize(source).toString().replace('\\', '/');
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + source, e);
        }
    }
}
