package dev.codespire.contract.scm;

/**
 * The label a posted review leaves on its pull request (verdict labels, V120): {@code name} is set, and every other
 * label starting {@code prefix} is removed. The names are fixed to the operator's convention for now.
 */
public record VerdictLabel(String name, String prefix) {

    public static final String PREFIX = "review: ";
    public static final VerdictLabel APPROVED = new VerdictLabel(PREFIX + "approved", PREFIX);
    public static final VerdictLabel CHANGES_REQUESTED = new VerdictLabel(PREFIX + "changes-requested", PREFIX);
    /** Review waits for CI: the checks failed, so no review ran (2026-11-24-review-waits-for-ci-design.md). */
    public static final VerdictLabel CI_FAILED = new VerdictLabel(PREFIX + "ci-failed", PREFIX);

    public VerdictLabel {
        if (name == null || prefix == null || !name.startsWith(prefix)) {
            throw new IllegalArgumentException("A verdict label must start with its prefix: " + name + " / " + prefix);
        }
    }

    /** Whether {@code other} is another label of this family, which setting this one removes. */
    public boolean replaces(String other) {
        return other.startsWith(prefix) && !other.equals(name);
    }
}
