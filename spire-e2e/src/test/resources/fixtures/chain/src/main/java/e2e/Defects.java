package e2e;

/** E2E fixture. Every defect here is deliberate and marked. */
public final class Defects {

    public static int divide(int numerator, int denominator) {
        return numerator / denominator;  // E2E-DEFECT-A
    }

    public static String at(String[] values, int index) {
        return values[index];  // E2E-DEFECT-B
    }
}
