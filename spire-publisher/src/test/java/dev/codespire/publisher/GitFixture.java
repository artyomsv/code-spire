package dev.codespire.publisher;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A bare origin and an agent clone that writes numbered bundles into a handoff directory, as the agent image does. */
final class GitFixture {
    final Path root, bare, agent, handoff;
    final String base;
    private int bundles;

    GitFixture(Path root) throws Exception {
        this.root = root;
        bare = Files.createDirectories(root.resolve("origin.git"));
        run(bare, "git", "init", "--bare", "--initial-branch=main");
        Path seed = Files.createDirectories(root.resolve("seed"));
        run(seed, "git", "clone", bare.toUri().toString(), ".");
        identity(seed);
        Files.writeString(seed.resolve("README.md"), "TEST base\n");
        run(seed, "git", "add", "-A");
        run(seed, "git", "commit", "-m", "TEST base");
        run(seed, "git", "push", "origin", "main");
        base = out(seed, "git", "rev-parse", "HEAD");
        agent = root.resolve("agent");
        run(root, "git", "clone", bare.toUri().toString(), agent.toString());
        identity(agent);
        run(agent, "git", "checkout", "-b", "spire/TEST");
        handoff = Files.createDirectories(root.resolve("handoff"));
    }

    String originUri() { return bare.toUri().toString(); }

    void commitAndBundle(String file, String content) throws Exception {
        Files.writeString(agent.resolve(file), content + "\n");
        run(agent, "git", "add", "-A");
        run(agent, "git", "commit", "-m", "TEST " + file);
        run(agent, "git", "bundle", "create", handoff.resolve(++bundles + ".bundle").toString(), base + "..HEAD");
    }

    String agentHead() throws Exception { return out(agent, "git", "rev-parse", "HEAD"); }

    private void identity(Path repo) throws Exception {
        run(repo, "git", "config", "user.email", "TEST@factory.invalid");
        run(repo, "git", "config", "user.name", "TEST");
    }

    void run(Path cwd, String... argv) throws Exception {
        Process process = new ProcessBuilder(argv).directory(cwd.toFile()).inheritIO().start();
        assertEquals(0, process.waitFor(), String.join(" ", argv));
    }

    String out(Path cwd, String... argv) throws Exception {
        Process process = new ProcessBuilder(argv).directory(cwd.toFile()).redirectErrorStream(true).start();
        String text = new String(process.getInputStream().readAllBytes()).trim();
        assertEquals(0, process.waitFor(), text);
        return text;
    }
}
