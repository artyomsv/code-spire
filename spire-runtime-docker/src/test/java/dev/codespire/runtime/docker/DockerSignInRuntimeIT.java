package dev.codespire.runtime.docker;

import com.github.dockerjava.api.DockerClient;
import dev.codespire.runtime.EnterpriseEnvironment;
import dev.codespire.runtime.SignInRuntime;
import dev.codespire.runtime.SignInUnitSpec;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The trusted sign-in unit, against a real daemon (M3.5 part F).
 *
 * <p>Two things need proving and neither can be faked: that a caller sees what the operator must type
 * WHILE the unit is still waiting, and that the unit's result comes out through the copy channel rather
 * than its log. A stub of the daemon would prove the calls were made, not that Docker answers them.
 *
 * <p><b>No code is ever printed.</b> The device code is matched against a shape and counted; putting it
 * in an assertion message would write a live authorization handle into a CI log, which is the habit this
 * whole design exists to avoid.
 *
 * <p><b>The first test makes one anonymous call to the vendor's device endpoint</b>, which is the only
 * way to prove the flow the operator will actually use. It signs nothing in: nobody approves the code,
 * the unit that would collect a token is destroyed, and the authorization expires on its own. It needs
 * the reference image and a network, and skips without either — which is also why it does not run on a
 * machine that has not built that image.
 */
class DockerSignInRuntimeIT {

    /** The reference agent image. Absent on a machine that has not built it, and then this is skipped. */
    private static final String IMAGE = "spire-agent-codex:latest";

    private static final long MEGABYTE = 1024L * 1024L;

    private final DockerRunRuntime runs = new DockerRunRuntime();
    private final DockerClient client = runs.client();
    private final DockerSignInRuntime runtime = new DockerSignInRuntime(client);

    private SignInUnitSpec spec(String unitId, List<String> command, String resultPath) {
        return new SignInUnitSpec(unitId, IMAGE, command, resultPath, EnterpriseEnvironment.NONE,
                512 * MEGABYTE, 1_000_000_000L, 64 * MEGABYTE, Duration.ofMinutes(2));
    }

    private boolean imagePresent() {
        try { client.inspectImageCmd(IMAGE).exec(); return true; }
        catch (RuntimeException absent) { return false; }
    }

    /**
     * The real device-code flow. Nobody approves it, which is the ordinary case for a unit that times
     * out — and the one the screen has to handle without leaving a credential behind.
     */
    @Test
    void aSignInUnitShowsTheLinkAndCodeWhileItIsStillWaiting() throws Exception {
        assumeTrue(imagePresent(), IMAGE + " is not built on this machine");
        List<String> seen = new CopyOnWriteArrayList<>();
        SignInRuntime.Handle handle = runtime.start(
                spec("TEST-signin-" + System.nanoTime(), List.of("codex", "login", "--device-auth"),
                        "/home/agent/.codex/auth.json"),
                seen::add);
        try {
            // The unit is WAITING, not finished, so this must arrive before any exit.
            String link = awaitLine(seen, Pattern.compile("https://\\S*auth\\.openai\\.com/\\S*device"));
            assertTrue(link.startsWith("https://"), "an operator is asked to sign in at whatever this points at");
            assertNotNull(awaitLine(seen, Pattern.compile("\\b[A-Z0-9]{4}-[A-Z0-9]{4,6}\\b")),
                    "the one-time code must reach the caller while the code is still usable");

            // Nobody approves it. Nothing may be collected, and nothing may be left behind.
            runtime.cancel(handle);
            assertEquals(Optional.empty(), runtime.result(handle, "/home/agent/.codex/auth.json"),
                    "an unapproved sign-in writes no credential");
        } finally {
            assertTrue(runtime.destroy(handle), "removal must be CONFIRMED, not assumed");
        }
        assertTrue(gone(handle), "the unit and whatever it wrote must not outlive the attempt");
    }

    /** The result travels by the copy channel. Proved with bytes the test itself chose. */
    @Test
    void theUnitsResultIsReadOutOfTheContainerRatherThanItsLog() throws Exception {
        assumeTrue(imagePresent(), IMAGE + " is not built on this machine");
        String marker = "TEST-not-a-credential-" + System.nanoTime();
        List<String> seen = new CopyOnWriteArrayList<>();
        SignInRuntime.Handle handle = runtime.start(
                spec("TEST-copy-" + System.nanoTime(),
                        List.of("sh", "-c", "printf '%s' '" + marker + "' > /tmp/result.json; echo wrote it"),
                        "/tmp/result.json"),
                seen::add);
        try {
            assertEquals(new SignInRuntime.Exit.Observed(0), runtime.awaitExit(handle, Duration.ofSeconds(60)));
            assertEquals(marker, new String(runtime.result(handle, "/tmp/result.json").orElseThrow(), StandardCharsets.UTF_8));
            // The bytes came out of the file, and the log never carried them.
            assertTrue(seen.stream().noneMatch(line -> line.contains(marker)),
                    "a credential in the daemon's log is a credential on disk for as long as the log is kept");
            assertTrue(seen.stream().anyMatch(line -> line.contains("wrote it")), "the log is still streamed");
        } finally {
            runtime.destroy(handle);
        }
    }

    /** Reading a path the unit never wrote answers empty rather than throwing. */
    @Test
    void aUnitThatWroteNothingAnswersEmpty() {
        assumeTrue(imagePresent(), IMAGE + " is not built on this machine");
        SignInRuntime.Handle handle = runtime.start(
                spec("TEST-empty-" + System.nanoTime(), List.of("sh", "-c", "echo nothing written"), "/tmp/absent.json"),
                line -> { });
        try {
            assertEquals(new SignInRuntime.Exit.Observed(0), runtime.awaitExit(handle, Duration.ofSeconds(60)));
            assertEquals(Optional.empty(), runtime.result(handle, "/tmp/absent.json"));
        } finally {
            runtime.destroy(handle);
        }
    }

    /**
     * Two workers, one redelivered command: the daemon elects one owner.
     *
     * <p>Looking before creating is two operations, so both would look, both would find nothing, and
     * both would build a unit for one sign-in — leaving one of them holding a credential that nothing
     * was tracking. A container name is unique per daemon, which makes CREATION the election.
     */
    @Test
    void asecondUnitForTheSameSignInIsRefusedByTheDaemon() {
        assumeTrue(imagePresent(), IMAGE + " is not built on this machine");
        String unitId = "TEST-claim-" + System.nanoTime();
        SignInRuntime.Handle first = runtime.start(
                spec(unitId, List.of("sh", "-c", "sleep 30"), "/tmp/absent.json"), line -> { });
        try {
            var refused = assertThrows(SignInRuntime.AlreadyClaimed.class, () -> runtime.start(
                    spec(unitId, List.of("sh", "-c", "sleep 30"), "/tmp/absent.json"), line -> { }));

            assertEquals(unitId, refused.existing().unitId());
            assertTrue(refused.existingIsRunning(),
                    "a live unit has an owner that will report it, and must not be taken from under it");
        } finally {
            runtime.destroy(first);
        }
    }

    /**
     * The conflict finds the unit even when it was created a moment ago.
     *
     * <p>The lookup used to go through the age-fenced sweep query, whose whole-second comparison hides
     * anything created in the current second — precisely the container whose name had just caused the
     * conflict. The loser then rethrew, and a LIVE owner's sign-in was failed because of it.
     */
    @Test
    void aConflictFindsAUnitCreatedInTheSameSecond() {
        assumeTrue(imagePresent(), IMAGE + " is not built on this machine");
        String unitId = "TEST-fresh-" + System.nanoTime();
        SignInRuntime.Handle first = runtime.start(
                spec(unitId, List.of("sh", "-c", "sleep 30"), "/tmp/absent.json"), line -> { });
        try {
            // Immediately: no sleep, no settling. This is the window that used to lose the unit.
            var refused = assertThrows(SignInRuntime.AlreadyClaimed.class, () -> runtime.start(
                    spec(unitId, List.of("sh", "-c", "sleep 30"), "/tmp/absent.json"), line -> { }));

            assertEquals(first.reference(), refused.existing().reference(),
                    "the conflict must name the very unit that caused it");
        } finally {
            runtime.destroy(first);
        }
        // And the age-fenced sweep still refuses to see it, which is why the two queries are separate.
        assertTrue(runtime.discover(Duration.ofMinutes(30)).stream()
                        .noneMatch(unit -> unit.unitId().equals(unitId)),
                "a fresh unit is not an abandoned one");
    }

    /** find() answers by identity whatever the state, so a duplicate is never mistaken for an absence. */
    @Test
    void aUnitIsFoundByItsSignInIdWhateverItsState() throws Exception {
        assumeTrue(imagePresent(), IMAGE + " is not built on this machine");
        String unitId = "TEST-find-" + System.nanoTime();
        SignInRuntime.Handle handle = runtime.start(
                spec(unitId, List.of("sh", "-c", "echo done"), "/tmp/absent.json"), line -> { });
        try {
            assertEquals(new SignInRuntime.Exit.Observed(0), runtime.awaitExit(handle, Duration.ofSeconds(60)));
            assertEquals(handle.reference(), runtime.find(unitId).orElseThrow().reference(),
                    "a stopped unit is still the holder of its sign-in");
        } finally {
            runtime.destroy(handle);
        }
        assertTrue(runtime.find(unitId).isEmpty(), "and it is gone once destroyed");
    }

    /** A stopped one reports as not running. The CALLER no longer acts on that, but the arm still says. */
    @Test
    void aStoppedUnitForTheSameSignInIsReportedAsNotRunning() throws Exception {
        assumeTrue(imagePresent(), IMAGE + " is not built on this machine");
        String unitId = "TEST-orphan-" + System.nanoTime();
        SignInRuntime.Handle first = runtime.start(
                spec(unitId, List.of("sh", "-c", "echo done"), "/tmp/absent.json"), line -> { });
        try {
            assertEquals(new SignInRuntime.Exit.Observed(0), runtime.awaitExit(first, Duration.ofSeconds(60)));

            var refused = assertThrows(SignInRuntime.AlreadyClaimed.class, () -> runtime.start(
                    spec(unitId, List.of("sh", "-c", "sleep 30"), "/tmp/absent.json"), line -> { }));

            assertFalse(refused.existingIsRunning(), "nothing is watching a unit that has already ended");
        } finally {
            runtime.destroy(first);
        }
    }

    private String awaitLine(List<String> seen, Pattern pattern) throws InterruptedException {
        for (int attempt = 0; attempt < 300; attempt++) {
            for (String line : new ArrayList<>(seen)) {
                var match = pattern.matcher(line);
                if (match.find()) return match.group();
            }
            Thread.sleep(200);
        }
        throw new AssertionError("the unit never printed anything matching " + pattern);
    }

    private boolean gone(SignInRuntime.Handle handle) {
        try { client.inspectContainerCmd(handle.reference()).exec(); return false; }
        catch (RuntimeException removed) { return true; }
    }
}
