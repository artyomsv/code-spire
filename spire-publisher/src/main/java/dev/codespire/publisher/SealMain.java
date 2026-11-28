package dev.codespire.publisher;

import java.io.PrintStream;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code spire-seal --canary host:port... [--control host:port] --rounds 5 --timeout-ms 500 --interval-ms 250
 * --deadline-s 60}. Exit 0 sealed, 3 not sealed (one JSON line on stdout), 2 bad arguments.
 */
public final class SealMain {

    static final int NOT_SEALED = 3;

    static final int BAD_ARGUMENTS = 2;

    private SealMain() {
    }

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err, new Seal.SocketConnector(), Clock.systemUTC(),
                d -> Thread.sleep(d.toMillis()));
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err, Seal.Connector connector, Clock clock,
            Seal.Sleeper sleeper) {
        Seal.Settings settings;
        try {
            settings = parse(args);
        } catch (IllegalArgumentException e) {
            err.println("spire-seal: " + e.getMessage());
            return BAD_ARGUMENTS;
        }
        try {
            Seal.Result result = new Seal(settings, connector, clock, sleeper).run();
            if (result instanceof Seal.Result.Open open) {
                out.println("{\"sealed\":false,\"open\":\"" + Json.escape(open.target().toString()) + "\"}");
                return NOT_SEALED;
            }
            if (result instanceof Seal.Result.NoControl none) {
                out.println("{\"sealed\":false,\"control\":\"" + Json.escape(none.target().toString()) + "\"}");
                return NOT_SEALED;
            }
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("spire-seal: interrupted");
            return NOT_SEALED;
        }
    }

    static Seal.Settings parse(String[] args) {
        List<Seal.Target> canaries = new ArrayList<>();
        Seal.Target control = null;
        int rounds = 5;
        int timeoutMs = 500;
        int intervalMs = 250;
        int deadlineS = 60;
        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("missing value for " + flag);
            }
            String value = args[++i];
            switch (flag) {
                case "--canary" -> canaries.add(Seal.Target.parse(value));
                case "--control" -> control = Seal.Target.parse(value);
                case "--rounds" -> rounds = Json.positive(flag, value);
                case "--timeout-ms" -> timeoutMs = Json.positive(flag, value);
                case "--interval-ms" -> intervalMs = Json.positive(flag, value);
                case "--deadline-s" -> deadlineS = Json.positive(flag, value);
                default -> throw new IllegalArgumentException("unknown argument " + flag);
            }
        }
        return new Seal.Settings(canaries, control, rounds, Duration.ofMillis(timeoutMs),
                Duration.ofMillis(intervalMs), Duration.ofSeconds(deadlineS));
    }
}
