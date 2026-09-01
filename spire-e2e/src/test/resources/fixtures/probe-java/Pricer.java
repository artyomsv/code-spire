package e2e.probe.pricing;

/** E2E probe definition. Its body is what the code-context provider must retrieve. */
public final class Pricer {

    public static final String MARKER = "E2E-PROBE-DEFINITION-BODY";

    public static long chargeFor(long tokens) {
        return tokens * 2;
    }
}
