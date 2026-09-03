package dev.codespire.agentimage;

import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;

/**
 * {@code spire agent-image verify <image>} (FR-F13).
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

    /** The command could not be understood, or the image could not be reached. */
    static final int USAGE_OR_UNREACHABLE = 2;

    private AgentImageCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * The whole command, with its streams handed in.
     *
     * <p>Separated from {@link #main} so a test can drive it without {@code System.exit} taking the
     * test JVM down with it — and so the EXIT CODE is assertable, which is the half a CI pipeline
     * consumes and the half nobody notices is wrong.
     */
    static int run(String[] args, java.io.PrintStream out, java.io.PrintStream err) {
        if (args.length != 2 || !"verify".equals(args[0]) || args[1].isBlank()) {
            err.println("usage: spire-agent-image verify <image>");
            err.println();
            err.println("Checks an image against docs/factory/AGENT-IMAGE-CONTRACT.md. Reports");
            err.println("VERIFIED clauses (proved against the image) and DECLARED clauses (the");
            err.println("image says so; this command did not verify them) separately.");
            return USAGE_OR_UNREACHABLE;
        }

        String image = args[1];
        try {
            ConformanceReport report = AgentImageVerifier.againstDocker(dockerClient()).verify(image);
            out.print(report.render());
            return report.conforms() ? CONFORMS : DOES_NOT_CONFORM;
        } catch (RuntimeException unreachable) {
            // Distinguished from DOES_NOT_CONFORM on purpose: "I could not check this image" and
            // "this image is wrong" call for opposite actions, and a pipeline that treats them
            // alike will eventually fail a good image because a daemon was busy.
            err.println("could not verify " + image + ": " + unreachable.getMessage());
            return USAGE_OR_UNREACHABLE;
        }
    }

    private static com.github.dockerjava.api.DockerClient dockerClient() {
        DefaultDockerClientConfig config =
                DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        DockerHttpClient http = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .build();
        return DockerClientImpl.getInstance(config, http);
    }
}
