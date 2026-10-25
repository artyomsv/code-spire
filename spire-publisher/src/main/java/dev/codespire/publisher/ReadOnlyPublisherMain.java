package dev.codespire.publisher;

import java.io.PrintStream;
import java.util.Map;

/** RED: the read-only publisher's executable (M4 slice 3). */
public final class ReadOnlyPublisherMain {

    private ReadOnlyPublisherMain() {
    }

    public static void main(String[] args) {
        int exitCode = run(System.getenv(), System.out);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(Map<String, String> env, PrintStream out) {
        throw new UnsupportedOperationException("RED");
    }
}
