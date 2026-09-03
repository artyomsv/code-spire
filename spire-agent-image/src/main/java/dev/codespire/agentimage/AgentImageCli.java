package dev.codespire.agentimage;

import com.github.dockerjava.api.DockerClient;

import java.io.PrintStream;
import java.util.function.Function;

/**
 * {@code spire-agent-image verify <image>} (FR-F13).
 *
 * <p>Prints the two-part conformance report and exits non-zero when a VERIFIED clause failed. A
 * declared clause never affects the exit code — it is not a conformance result, and letting it
 * decide one would be the blend the whole report shape exists to prevent.
 */
public final class AgentImageCli {

    /** Every verified clause passed. */
    static final int CONFORMS = 0;

    /** At least one verified clause failed. */
    static final int DOES_NOT_CONFORM = 1;

    /** The command could not be understood, or the image could not be checked. */
    static final int USAGE_OR_UNREACHABLE = 2;

    private AgentImageCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err, AgentImageCli::verifyWithDocker));
    }

    /**
     * The whole command, with its streams and its verifier handed in.
     *
     * <p>Separated from {@link #main} so a test can drive it without {@code System.exit} taking the
     * test JVM down with it — and so the EXIT CODE is assertable, which is the half a CI pipeline
     * consumes and the half nobody notices is wrong. An earlier version made that split and then
     * built its own Docker client inside, which left exit 0 and 1 unreachable from a test: the
     * javadoc claimed a seam the signature did not have.
     */
    static int run(String[] args, PrintStream out, PrintStream err,
                   Function<String, ConformanceReport> reports) {
        if (args.length != 2 || !"verify".equals(args[0]) || args[1].isBlank()) {
            err.println("usage: spire-agent-image verify <image>");
            err.println();
            err.println("Checks an image against docs/factory/AGENT-IMAGE-CONTRACT.md. Reports");
            err.println("VERIFIED clauses (proved against the image) and DECLARED clauses (the");
            err.println("image says so; this command did not verify them) separately.");
            err.println();
            err.println("Exit: 0 conforms, 1 does not conform, 2 could not be checked.");
            return USAGE_OR_UNREACHABLE;
        }

        String image = args[1];
        ConformanceReport report;
        try {
            report = reports.apply(image);
        } catch (RuntimeException unreachable) {
            // Distinguished from DOES_NOT_CONFORM on purpose: "I could not check this image" and
            // "this image is wrong" call for opposite actions, and a pipeline that treats them
            // alike will eventually fail a good image because a daemon was busy.
            err.println("could not verify " + image + ": " + unreachable.getMessage());
            return USAGE_OR_UNREACHABLE;
        }

        out.print(report.render());
        if (report.anyNotChecked()) {
            // The report already shows those clauses as failures, because a clause silently omitted
            // reads as a shorter contract. The EXIT CODE says something different, and must: an
            // earlier version returned 1 here while its own comment above said that was the outcome
            // to avoid, and the runbook documented behaviour the code did not have.
            return USAGE_OR_UNREACHABLE;
        }
        return report.conforms() ? CONFORMS : DOES_NOT_CONFORM;
    }

    private static ConformanceReport verifyWithDocker(String image) {
        return AgentImageVerifier.againstDocker(dockerClient()).verify(image);
    }

    /** The daemon this host talks to. Shared with the probe so both speak to the same one. */
    static DockerClient dockerClient() {
        com.github.dockerjava.core.DefaultDockerClientConfig config =
                com.github.dockerjava.core.DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        com.github.dockerjava.transport.DockerHttpClient http =
                new com.github.dockerjava.httpclient5.ApacheDockerHttpClient.Builder()
                        .dockerHost(config.getDockerHost())
                        .sslConfig(config.getSSLConfig())
                        .build();
        return com.github.dockerjava.core.DockerClientImpl.getInstance(config, http);
    }
}
