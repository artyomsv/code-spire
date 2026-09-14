package dev.codespire.publisher;

import dev.codespire.workspace.PublishRepo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Real Git refs prove the hold; publisher log wording alone would also pass a push-then-refuse bug. */
class HeldPublicationTest {
    @TempDir Path dir;
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    final OutcomeWriter outcomes = new OutcomeWriter(new PrintStream(output, true, StandardCharsets.UTF_8));
    static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");
    static final String BRANCH = "spire/TEST-held-publication";

    @AfterEach void release() { PublishRepo.releaseAllPackWindows(); }

    @Test void heldCheckpointLeavesTheRemoteUnchanged() throws Exception {
        Fixture f = fixture();
        Path bundle = f.bundle("work.txt", "TEST work");
        String head = git(f.work, "rev-parse", "HEAD");
        // An agent-authored permit-looking file and DONE are not an authorization channel.
        Files.writeString(dir.resolve("permit.json"), "{\"permit\":true,\"head\":\"" + head + "\"}");
        Files.writeString(dir.resolve("DONE"), "publish now");
        try (PublishRepo repo = f.publisher("held")) {
            assertTrue(cycle(repo, f, PublicationPolicy.held(), List.of()).handle(bundle));
        }
        assertEquals("", ref(f.origin, BRANCH), "held work must never reach the real origin");
        assertTrue(output.toString().contains("\"event\":\"checkpoint\""));
        assertTrue(output.toString().contains("\"head\":\"" + head + "\""));
        assertFalse(output.toString().contains("\"event\":\"pushed\""));
    }

    @Test void permittedPublicationSelectsExactlyOneHead() throws Exception {
        Fixture f = fixture();
        Path first = f.bundle("work.txt", "TEST first");
        String firstHead = git(f.work, "rev-parse", "HEAD");
        Path selected = f.bundle("work.txt", "TEST selected");
        String head = git(f.work, "rev-parse", "HEAD");
        Path later = f.bundle("work.txt", "TEST not approved");
        assertNotEquals(firstHead, head);
        try (PublishRepo repo = f.publisher("permitted")) {
            PublishCycle cycle = cycle(repo, f, permit(head, Clock.fixed(NOW, ZoneOffset.UTC)), List.of());
            assertTrue(cycle.handle(first));
            assertEquals("", ref(f.origin, BRANCH), "older checkpoints must not be pushed on the way to the approved one");
            assertTrue(cycle.handle(selected));
            assertEquals(head, ref(f.origin, BRANCH));
            assertTrue(cycle.handle(later));
            assertEquals(head, ref(f.origin, BRANCH), "a newer unapproved checkpoint is also excluded");
            assertTrue(cycle.finish());
        }
    }

    @Test void permittedHeadMissingIsAnExplicitFailure() throws Exception {
        Fixture f = fixture();
        Path bundle = f.bundle("work.txt", "TEST other head");
        try (PublishRepo repo = f.publisher("missing")) {
            PublishCycle cycle = cycle(repo, f, permit("f".repeat(40), Clock.fixed(NOW, ZoneOffset.UTC)), List.of());
            assertTrue(cycle.handle(bundle));
            assertFalse(cycle.finish());
        }
        assertEquals("", ref(f.origin, BRANCH));
        assertTrue(output.toString().contains("PERMITTED_HEAD_UNAVAILABLE"));
    }

    @Test void expiryIsCheckedAtTheWriteBoundary() throws Exception {
        Fixture f = fixture();
        Path bundle = f.bundle("work.txt", "TEST work");
        String head = git(f.work, "rev-parse", "HEAD");
        MutableClock clock = new MutableClock(NOW);
        PublicationPolicy permit = permit(head, clock);
        assertTrue(permit.validNow(), "the permit was valid when the publisher was configured");
        clock.now = NOW.plusSeconds(60);
        try (PublishRepo repo = f.publisher("expired")) {
            assertFalse(cycle(repo, f, permit, List.of()).handle(bundle));
        }
        assertEquals("", ref(f.origin, BRANCH), "expired authority must not write even a previously approved head");
        assertTrue(output.toString().contains("PUBLICATION_PERMIT_EXPIRED"));
    }

    @Test void currentProtectedPathsAreReappliedBeforePublishing() throws Exception {
        Fixture f = fixture();
        Path bundle = f.bundle("work.txt", "TEST work");
        String head = git(f.work, "rev-parse", "HEAD");
        try (PublishRepo repo = f.publisher("held")) {
            assertTrue(cycle(repo, f, PublicationPolicy.held(), List.of()).handle(bundle));
        }
        try (PublishRepo repo = f.publisher("new-policy")) {
            assertFalse(cycle(repo, f, permit(head, Clock.fixed(NOW, ZoneOffset.UTC)), List.of("work.txt")).handle(bundle));
        }
        assertEquals("", ref(f.origin, BRANCH));
        assertTrue(output.toString().contains("gate_refused"));
    }

    @Test void holdStillRunsThePathGate() throws Exception {
        Fixture f = fixture();
        Path bundle = f.bundle("work.txt", "TEST blocked");
        try (PublishRepo repo = f.publisher("held")) {
            assertFalse(cycle(repo, f, PublicationPolicy.held(), List.of("work.txt")).handle(bundle));
        }
        assertEquals("", ref(f.origin, BRANCH));
        assertTrue(output.toString().contains("gate_refused"));
        assertFalse(output.toString().contains("checkpoint"));
    }

    @Test void repeatedPublicationNeedsNoNewBuild() throws Exception {
        Fixture f = fixture();
        Path bundle = f.bundle("work.txt", "TEST work");
        String head = git(f.work, "rev-parse", "HEAD");
        PublicationPolicy permit = permit(head, Clock.fixed(NOW, ZoneOffset.UTC));
        for (String name : List.of("first", "recovered")) {
            try (PublishRepo repo = f.publisher(name)) {
                PublishCycle cycle = cycle(repo, f, permit, List.of());
                assertTrue(cycle.handle(bundle));
                assertTrue(cycle.finish());
            }
            assertEquals(head, ref(f.origin, BRANCH));
        }
        assertEquals("2", git(f.work, "rev-list", "--count", "HEAD"), "one base and one build commit");
    }

    private PublicationPolicy permit(String head, Clock clock) {
        return PublicationPolicy.permitted(head, NOW, NOW.plusSeconds(60), clock);
    }

    private PublishCycle cycle(PublishRepo repo, Fixture f, PublicationPolicy policy, List<String> paths) {
        return new PublishCycle(repo, f.base, BRANCH, paths, 10_000_000L, null, outcomes, policy);
    }

    private Fixture fixture() throws Exception {
        Path origin = Files.createDirectory(dir.resolve("origin.git"));
        git(origin, "init", "--bare", "--initial-branch=main");
        Path work = dir.resolve("TEST-work");
        git(dir, "clone", origin.toUri().toString(), work.toString());
        git(work, "config", "user.email", "TEST@example.invalid");
        git(work, "config", "user.name", "TEST fixture");
        Files.writeString(work.resolve("README.md"), "TEST base");
        git(work, "add", ".");
        git(work, "commit", "-m", "TEST base");
        git(work, "push", "origin", "main");
        String base = git(work, "rev-parse", "HEAD");
        git(work, "checkout", "-b", BRANCH);
        return new Fixture(origin, work, base);
    }

    private final class Fixture {
        final Path origin, work;
        final String base;
        int bundles;
        Fixture(Path origin, Path work, String base) { this.origin = origin; this.work = work; this.base = base; }
        Path bundle(String name, String text) throws Exception {
            Files.writeString(work.resolve(name), text);
            git(work, "add", ".");
            git(work, "commit", "-m", "TEST checkpoint");
            Path bundle = dir.resolve(++bundles + ".bundle");
            git(work, "bundle", "create", bundle.toString(), base + "..HEAD");
            return bundle;
        }
        PublishRepo publisher(String name) throws Exception {
            return PublishRepo.cloneBranch(origin.toUri().toString(), "main", dir.resolve(name), null);
        }
    }

    private static String ref(Path repo, String branch) throws Exception {
        Process p = new ProcessBuilder("git", "rev-parse", "--verify", "--quiet", "refs/heads/" + branch)
                .directory(repo.toFile()).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        return p.waitFor() == 0 ? output : "";
    }

    private static String git(Path cwd, String... args) throws Exception {
        var command = new ProcessBuilder("git");
        command.command().addAll(List.of(args));
        Process p = command.directory(cwd.toFile()).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, p.waitFor(), output);
        return output;
    }

    private static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
