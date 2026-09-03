package dev.codespire.arch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The corporate environment is injected at run time and never baked into an image (FR-F14).
 *
 * <p>This is the half of the requirement that no unit test can reach, because it is a property of
 * the Dockerfiles rather than of any Java. The two run-unit images are the ones that matter: the
 * agent and the publisher are what a run creates, and an operator builds their own toolchain FROM
 * the reference agent image, so a proxy baked in here is inherited by every derived image in the
 * fleet.
 *
 * <p><b>Three separate harms, which is why the check is not one pattern.</b> A baked proxy cannot
 * be changed without a rebuild and silently follows an image between environments. A baked CA
 * bundle replaces the trust store of every deployment that pulls the image, including those with no
 * proxy at all. A baked registry credential is a secret in a layer, which
 * {@code docker image history} prints for anyone who can pull.
 *
 * <p>Scanned as text rather than by inspecting a built image, deliberately: the requirement is that
 * the SOURCE never bakes one in, and a text scan fails on the commit that introduces it rather than
 * on whichever CI run next builds an image.
 */
class NoCorporateEnvironmentIsBakedIntoAnImageTest {

    /**
     * The images a run unit is made of. Not every Dockerfile in the repository: the service images
     * are ours to configure and an operator never derives from them, whereas these two are handed
     * to an operator as a base.
     */
    private static final List<String> RUN_UNIT_IMAGES =
            List.of("deploy/agent/codex/Dockerfile", "spire-publisher/Dockerfile");

    /**
     * Names that change TLS or routing for every process in the container.
     *
     * <p>Matched on the name alone, with no value pattern, because the harm is the same whatever
     * the value: an {@code ENV HTTPS_PROXY=} clearing one is as wrong as setting one, since it
     * overrides what the runtime injects and does so invisibly.
     */
    private static final List<String> INJECTED_ONLY = List.of(
            "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "ALL_PROXY",
            "SSL_CERT_FILE", "SSL_CERT_DIR", "GIT_SSL_CAINFO", "NODE_EXTRA_CA_CERTS",
            "REQUESTS_CA_BUNDLE", "CURL_CA_BUNDLE");

    /** {@code ENV NAME=value}, {@code ENV NAME value}, and the multi-line backslash form. */
    private static final Pattern ENV_ASSIGNMENT =
            Pattern.compile("(?m)^\\s*(?:ENV|ARG)?\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*[=\\s]");

    /**
     * A credential-shaped name anywhere on an instruction line.
     *
     * <p>Line-anchored rather than anchored on {@code ARG|ENV} + name, which inspected only the
     * FIRST name after the keyword -- so a second name on a continued {@code ENV FOO=1 \\} line
     * escaped it entirely. The proxy scan above never had that hole precisely because it is
     * line-anchored, and the continuation case in the positive control proves it.
     */
    private static final Pattern SECRETISH = Pattern.compile(
            "(?im)^[^#\\n]*\\b[A-Za-z0-9_]*(?:PASSWORD|SECRET|TOKEN|APIKEY|API_KEY|CREDENTIAL)\\s*[=\\s]");

    @Test
    void noRunUnitImageBakesInAProxyOrATrustStore() throws IOException {
        List<String> baked = new ArrayList<>();
        for (String image : RUN_UNIT_IMAGES) {
            String text = instructions(image);
            for (String name : INJECTED_ONLY) {
                if (assignsEnvironment(text, name)) {
                    baked.add(image + " bakes in " + name);
                }
            }
        }

        assertEquals(List.of(), baked,
                "these are injected per run by EnterpriseEnvironmentConfig; baked into the image they "
                        + "cannot be changed without a rebuild, and an operator's derived image "
                        + "inherits them into deployments that never asked for them");
    }

    /**
     * A credential must not be copied in, argued in, or written to a file in a layer.
     *
     * <p>{@code ARG} is included and is the subtle one: a build argument leaves no {@code ENV} in
     * the final image and is widely believed to be safe for a secret. It is recorded in the image's
     * build history, which {@code docker image history} prints — the reason BuildKit has a separate
     * secret mount at all.
     */
    @Test
    void noRunUnitImageBakesInACredential() throws IOException {
        List<String> baked = new ArrayList<>();
        for (String image : RUN_UNIT_IMAGES) {
            Matcher found = SECRETISH.matcher(instructions(image));
            while (found.find()) {
                baked.add(image + ": " + found.group().trim());
            }
            if (instructions(image).toLowerCase(Locale.ROOT).contains("docker/config.json")) {
                baked.add(image + " copies a registry config");
            }
        }

        assertEquals(List.of(), baked,
                "a build argument is not a secret: it is recorded in the image's build history, "
                        + "which docker image history prints for anyone who can pull it");
    }

    /**
     * The scan must have read the images it claims to have checked.
     *
     * <p>The vacuity guard {@code ContractSchemaSnapshotTest} shipped without: every assertion above
     * is an empty-list comparison, and a renamed or moved Dockerfile makes them all pass while
     * checking nothing. The positive control is stronger than a file-exists check — it asserts the
     * scanner recognises an assignment in the text it actually read.
     */
    @Test
    void theScanReachedBothImagesAndCanRecogniseAnAssignment() throws IOException {
        for (String image : RUN_UNIT_IMAGES) {
            String text = instructions(image);
            assertFalse(text.isBlank(), image + " read as empty");
            assertTrue(ENV_ASSIGNMENT.matcher(text).find(),
                    image + " parsed to no instruction at all, so the checks above are vacuous");
        }

        assertTrue(assignsEnvironment("ENV HTTPS_PROXY=http://proxy:3128", "HTTPS_PROXY"),
                "the detector must see the form it exists to catch");
        assertTrue(assignsEnvironment("ENV HOME=/home/agent \\\n    SSL_CERT_FILE=/x", "SSL_CERT_FILE"),
                "a continued ENV block sets every name in it, not only the first");
        assertTrue(assignsEnvironment("ENV HTTPS_PROXY http://proxy:3128", "HTTPS_PROXY"),
                "the legacy space-separated ENV form is valid Dockerfile syntax and bakes in "
                        + "just as hard");
        assertTrue(assignsEnvironment("ARG SSL_CERT_FILE /etc/corp.crt", "SSL_CERT_FILE"),
                "and so does the ARG spelling of it");
        assertFalse(assignsEnvironment("# HTTPS_PROXY is injected per run, never baked in", "HTTPS_PROXY"),
                "a comment saying why must not fail the check that says the same thing");

        // The credential scan has its own positive control, or a typo in its alternation would
        // make an empty-list assertion pass while matching nothing at all.
        assertTrue(SECRETISH.matcher("ARG REGISTRY_PASSWORD=x").find());
        assertTrue(SECRETISH.matcher("ENV FOO=1 \\\n    REGISTRY_SECRET=x").find(),
                "a name on a CONTINUED line is the form a keyword-anchored pattern misses");
        assertFalse(SECRETISH.matcher("# this image holds NO credential").find());
    }

    /**
     * Whether a Dockerfile assigns this name, comments removed.
     *
     * <p>Comments are stripped first for the reason the neutrality scan strips them: the honest way
     * to record why a value is absent is to say so in the file, and a check that fails on its own
     * explanation teaches people to delete the explanation.
     */
    private static boolean assignsEnvironment(String text, String name) {
        String quoted = Pattern.quote(name);
        // TWO forms, because Dockerfile has two. The equals form can appear anywhere on an
        // instruction line, including a continued one; the LEGACY space form is only valid
        // directly after ENV or ARG. Requiring the equals sign left `ENV HTTPS_PROXY http://...`
        // -- still valid syntax, and baked in just as hard -- passing a guard whose own javadoc
        // claimed to cover it.
        return Pattern.compile("(?m)^[^#\\n]*?\\b" + quoted + "\\s*=").matcher(text).find()
                || Pattern.compile("(?m)^\\s*(?:ENV|ARG)\\s+" + quoted + "\\s+\\S")
                        .matcher(text).find();
    }

    /** The Dockerfile with comment lines removed; a trailing comment cannot follow an instruction. */
    private static String instructions(String relativePath) throws IOException {
        Path file = RootBuild.repoRoot().resolve(relativePath);
        assertTrue(Files.isRegularFile(file), relativePath + " is not where this check expects it");
        try (Stream<String> lines = Files.lines(file)) {
            return lines.filter(line -> !line.stripLeading().startsWith("#"))
                    .reduce(new StringBuilder(), (buffer, line) -> buffer.append(line).append('\n'),
                            StringBuilder::append)
                    .toString();
        }
    }
}
