package dev.codespire.runworker;

import java.util.List;
import java.util.UUID;

/**
 * A real git remote for the M0 exit criteria: the {@link TestImages#ORIGIN} container, reached by
 * the run unit at its bridge address and inspected here through {@code docker exec} against the
 * bare repository itself — so "the branch exists on the remote" is read from the remote, not from
 * anything the run reported.
 */
final class TestOrigin implements AutoCloseable {

    /** Must match what Credentials.scm packs as the machine account's username. */
    static final String USER = "spire-bot";

    static final String SECRET = "TEST-origin-secret-" + UUID.randomUUID().toString().substring(0, 8);

    private static final String BARE = "/srv/git/app.git";

    private final String container;

    private TestOrigin(String container) {
        this.container = container;
    }

    static TestOrigin start() throws InterruptedException {
        String name = "spire-m0-origin-" + UUID.randomUUID().toString().substring(0, 8);
        TestImages.docker("run", "-d", "--name", name,
                "-e", "ORIGIN_USER=" + USER, "-e", "ORIGIN_SECRET=" + SECRET, TestImages.ORIGIN);
        TestOrigin origin = new TestOrigin(name);
        for (int i = 0; i < 60; i++) {
            if (TestImages.dockerStatus("exec", name, "pidof", "nginx") == 0) {
                return origin;
            }
            Thread.sleep(500);
        }
        origin.close();
        throw new IllegalStateException("the origin container did not come up");
    }

    /** The address the run unit's containers use: the default bridge, no port mapping needed. */
    String remoteUri() {
        String ip = TestImages.docker("inspect", "-f",
                "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}", container);
        return "http://" + ip + "/app.git";
    }

    String baseCommit() {
        return git("rev-parse", "refs/heads/main");
    }

    /**
     * The tip of any branch, not only of {@code main}.
     *
     * <p>ADR-040's existing mode clones the branch it pushes to, so a run's base commit is that
     * branch's tip rather than the trunk's — and a test that read the trunk would hand the run a
     * commit that is not an ancestor of what it pushes.
     */
    String commitOf(String branch) {
        return git("rev-parse", "refs/heads/" + branch);
    }

    /**
     * A branch that exists BEFORE a run does — a human's branch, with a pull request open on it.
     *
     * <p>The whole subject of ADR-040 is pushing onto one of these, and M0 could not create one:
     * every branch on this remote was made by the publisher, inside {@code spire/}. So the fixture
     * has to make it, or the case cannot be set up at all.
     */
    void branchFrom(String branch, String from) {
        git("branch", "-f", branch, "refs/heads/" + from);
    }

    boolean hasBranch(String branch) {
        return TestImages.dockerStatus("exec", container, "git", "-C", BARE,
                "rev-parse", "--verify", "--quiet", "refs/heads/" + branch) == 0;
    }

    String authorOf(String branch) {
        return git("log", "-1", "--format=%an", "refs/heads/" + branch);
    }

    List<String> filesOf(String branch) {
        return List.of(git("ls-tree", "-r", "--name-only", "refs/heads/" + branch).split("\n"));
    }

    String contentOf(String branch, String path) {
        return git("show", "refs/heads/" + branch + ":" + path);
    }

    private String git(String... args) {
        String[] argv = new String[args.length + 5];
        argv[0] = "exec";
        argv[1] = container;
        argv[2] = "git";
        argv[3] = "-C";
        argv[4] = BARE;
        System.arraycopy(args, 0, argv, 5, args.length);
        return TestImages.docker(argv);
    }

    @Override
    public void close() {
        TestImages.dockerStatus("rm", "-f", container);
    }
}
