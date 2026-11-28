package dev.codespire.runtime.k8s;

/**
 * The unit's objects exist but its agent never started. The unit stays behind, discoverable by its anchor, so the
 * orphan watchdog can remove it and an operator can read why. The detail is the run's failure detail.
 */
public final class UnitNotStarted extends RuntimeException {

    private final String detail;

    private final boolean imageProblem;

    public UnitNotStarted(String detail, boolean imageProblem) {
        super(detail);
        this.detail = detail;
        this.imageProblem = imageProblem;
    }

    public String detail() {
        return detail;
    }

    /** True when a container could not get its image: the cause is the image, not the runtime. */
    public boolean imageProblem() {
        return imageProblem;
    }
}
