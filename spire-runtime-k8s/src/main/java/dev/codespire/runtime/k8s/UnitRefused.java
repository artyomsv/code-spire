package dev.codespire.runtime.k8s;

/** The arm will not run this unit; nothing was created. The detail is one of {@link UnitRefusals}' names. */
public final class UnitRefused extends RuntimeException {

    private final String detail;

    public UnitRefused(String runId, String detail) {
        super("the Kubernetes arm refused run " + runId + ": " + detail);
        this.detail = detail;
    }

    public String detail() {
        return detail;
    }
}
