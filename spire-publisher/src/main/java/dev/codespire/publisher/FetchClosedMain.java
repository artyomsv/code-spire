package dev.codespire.publisher;

import java.io.PrintStream;
import java.time.Clock;
import java.time.Duration;

/**
 * {@code spire-fetch-closed --close host:port --target host:port --refusals 3 --deadline-s 30}. Exit 0 closed, 4 not
 * closed (one JSON line on stdout), 2 bad arguments.
 */
public final class FetchClosedMain {

    static final int NOT_CLOSED = 4;

    static final int BAD_ARGUMENTS = 2;

    private FetchClosedMain() {
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
        FetchClosed.Settings settings;
        try {
            settings = parse(args);
        } catch (IllegalArgumentException e) {
            err.println("spire-fetch-closed: " + e.getMessage());
            return BAD_ARGUMENTS;
        }
        try {
            if (new FetchClosed(settings, connector, clock, sleeper).run()) {
                return 0;
            }
            out.println("{\"fetchClosed\":false}");
            return NOT_CLOSED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("spire-fetch-closed: interrupted");
            return NOT_CLOSED;
        }
    }

    static FetchClosed.Settings parse(String[] args) {
        Seal.Target close = null;
        Seal.Target target = null;
        int refusals = 3;
        int timeoutMs = 500;
        int intervalMs = 250;
        int deadlineS = 30;
        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("missing value for " + flag);
            }
            String value = args[++i];
            switch (flag) {
                case "--close" -> close = Seal.Target.parse(value);
                case "--target" -> target = Seal.Target.parse(value);
                case "--refusals" -> refusals = Json.positive(flag, value);
                case "--timeout-ms" -> timeoutMs = Json.positive(flag, value);
                case "--interval-ms" -> intervalMs = Json.positive(flag, value);
                case "--deadline-s" -> deadlineS = Json.positive(flag, value);
                default -> throw new IllegalArgumentException("unknown argument " + flag);
            }
        }
        return new FetchClosed.Settings(close, target, refusals, Duration.ofMillis(timeoutMs),
                Duration.ofMillis(intervalMs), Duration.ofSeconds(deadlineS));
    }
}
