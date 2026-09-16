package dev.codespire.runtime.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import dev.codespire.runtime.RuntimeType;
import dev.codespire.runtime.SignInRuntime;
import dev.codespire.runtime.SignInUnitSpec;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The Docker arm of {@link SignInRuntime} (M3.5 part F).
 *
 * <p>One container, no volume and no mount. Everything it writes lives in the container's own
 * filesystem and dies with it, which is the shortest life a credential can have while still being
 * readable once.
 *
 * <p><b>The result is copied out, never printed.</b> {@code docker cp} reads the file straight from
 * the stopped container into this process. Having the unit echo it instead would put a live sign-in
 * into the daemon's log for that container, on disk, for as long as the log is kept — and this class
 * streams that log to a caller who shows it on a screen.
 */
public final class DockerSignInRuntime implements SignInRuntime {

    /** Shared with the run arm, so one label finds every unit this project left behind. */
    static final String UNIT_ID_LABEL = "dev.codespire.signInId";

    /**
     * The same bound the run arm puts on an agent, for a different reason.
     *
     * <p>Nothing untrusted runs here, so this is not a containment boundary — it is a guard against a
     * hung or looping CLI taking the host with it while a person is away from their desk.
     */
    private static final long PIDS_LIMIT = 256;

    private final DockerClient client;

    public DockerSignInRuntime() {
        com.github.dockerjava.core.DefaultDockerClientConfig config =
                com.github.dockerjava.core.DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        this.client = com.github.dockerjava.core.DockerClientImpl.getInstance(config,
                new com.github.dockerjava.httpclient5.ApacheDockerHttpClient.Builder()
                        .dockerHost(config.getDockerHost())
                        .sslConfig(config.getSSLConfig())
                        .build());
    }

    /** For a test that already holds a client, and for nothing else. */
    DockerSignInRuntime(DockerClient client) {
        this.client = client;
    }

    @Override
    public RuntimeType type() {
        return RuntimeType.DOCKER;
    }

    @Override
    public Handle start(SignInUnitSpec spec, Consumer<String> lines) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(UNIT_ID_LABEL, spec.unitId());

        HostConfig host = HostConfig.newHostConfig()
                .withMemory(spec.memoryBytes())
                .withNanoCPUs(spec.nanoCpus())
                .withPidsLimit(PIDS_LIMIT)
                .withSecurityOpts(java.util.List.of("no-new-privileges"))
                .withCapDrop(Capability.ALL)
                // awaitExit must be able to read a status, and an auto-removed container has none.
                // Teardown is destroy()'s job, and destroy() is what removes the credential.
                .withAutoRemove(false);

        String id = client.createContainerCmd(spec.image())
                // The ENTRYPOINT, not the Cmd. The agent image's entrypoint is what turns it into a
                // factory agent: it wants a prompt on stdin and a workspace to work in. A Cmd would
                // arrive as arguments to that, and the sign-in would never run at all.
                .withEntrypoint(spec.command())
                .withCmd(java.util.List.of())
                .withEnv(spec.enterprise().environment().entrySet().stream()
                        .map(entry -> entry.getKey() + "=" + entry.getValue()).toList())
                .withLabels(labels)
                .withHostConfig(host)
                .exec()
                .getId();
        client.startContainerCmd(id).exec();

        // Follows the log until the container exits. The caller needs the link and the code while the
        // unit is still waiting, so reading the log only at the end would show them nothing until it
        // was too late to use.
        client.logContainerCmd(id).withStdOut(true).withStdErr(true).withFollowStream(true)
                .exec(new LineCallback(lines));
        return new Handle(spec.unitId(), id);
    }

    /**
     * Turns the daemon's byte frames back into lines.
     *
     * <p>A frame is a chunk of the stream, not a line. The one-time code and the link can land split
     * across two of them, and a caller that showed half a code would send the operator to type half a
     * code — so a partial tail is held until its newline arrives.
     */
    private static final class LineCallback extends ResultCallback.Adapter<Frame> {

        /**
         * Terminal colour and cursor codes, removed before a line leaves this class.
         *
         * <p>The vendor CLI writes to a terminal and colours the link and the code. Those escapes are
         * meaningless to every caller here — one shows the line on a web page, another matches the code
         * against a shape — and they break both: the page renders the escapes, and the match fails
         * because a colour code ends in a letter that sits flush against the code's first character.
         */
        private static final java.util.regex.Pattern ANSI =
                java.util.regex.Pattern.compile("\\u001B\\[[0-9;]*[a-zA-Z]");

        private final Consumer<String> lines;
        private final StringBuilder pending = new StringBuilder();

        private void emit(String line) {
            String plain = ANSI.matcher(line).replaceAll("").stripTrailing();
            if (!plain.isBlank()) lines.accept(plain);
        }

        private LineCallback(Consumer<String> lines) {
            this.lines = lines;
        }

        @Override
        public void onNext(Frame frame) {
            pending.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
            int newline;
            while ((newline = pending.indexOf("\n")) >= 0) {
                String line = pending.substring(0, newline);
                pending.delete(0, newline + 1);
                emit(line);
            }
        }

        @Override
        public void onComplete() {
            // Whatever the unit printed without a trailing newline is still something it said.
            emit(pending.toString());
            pending.setLength(0);
            super.onComplete();
        }
    }

    @Override
    public Optional<Integer> awaitExit(Handle handle, Duration within) {
        try (WaitContainerResultCallback wait = client.waitContainerCmd(handle.reference())
                .exec(new WaitContainerResultCallback())) {
            return Optional.of(wait.awaitStatusCode(within.toMillis(), TimeUnit.MILLISECONDS));
        } catch (RuntimeException | IOException stillRunning) {
            // A wait that elapses and a daemon that hung up are both "no status yet" to the caller,
            // which then cancels. Distinguishing them would change nothing it does.
            return Optional.empty();
        }
    }

    @Override
    public Optional<byte[]> result(Handle handle, String resultPath) {
        try (InputStream archive = client.copyArchiveFromContainerCmd(handle.reference(), resultPath).exec();
             TarArchiveInputStream tar = new TarArchiveInputStream(archive)) {
            TarArchiveEntry entry = tar.getNextEntry();
            // One file, and only a file: a directory at the result path means the unit wrote something
            // this was not asked to read, and guessing which member is the credential is not a guess
            // worth making.
            if (entry == null || entry.isDirectory()) return Optional.empty();
            return Optional.of(tar.readAllBytes());
        } catch (RuntimeException | IOException absent) {
            // The unit wrote nothing there, which is the ordinary shape of a sign-in nobody approved.
            return Optional.empty();
        }
    }

    @Override
    public void cancel(Handle handle) {
        try { client.stopContainerCmd(handle.reference()).withTimeout(5).exec(); }
        catch (RuntimeException alreadyStopped) { /* stopping a stopped unit is the outcome asked for */ }
    }

    @Override
    public void destroy(Handle handle) {
        try { client.removeContainerCmd(handle.reference()).withForce(true).exec(); }
        catch (RuntimeException alreadyGone) { /* the credential is gone, which is the point */ }
    }
}
