package dev.codespire.agentimage;

import java.util.List;
import java.util.Objects;

/**
 * What {@code spire agent-image verify} answers about one image (FR-F13).
 *
 * <p><b>The two halves are separate lists, and that is the whole design.</b> A verified clause is
 * one this checker PROVED, by reading the image config or by running the image. A declared clause is
 * one the image CLAIMS through a label and the checker cannot prove — proving it would need
 * something the checker does not have, most often the operator's repository.
 *
 * <p>A report that blends them is a report nobody can act on. "Toolchain: OK" reads as proof; if it
 * only means the label was present, then an image declaring a toolchain it does not carry passes,
 * and the first thing to notice is a run that has already been paid for.
 *
 * <p><b>The split is structural rather than a flag on one type.</b> A single {@code Clause} carrying
 * an {@code assurance} field would leave "report a declared clause as verified" one line away, and
 * that line would look correct — the same reasoning that gives {@code HostMount} no writable form.
 * Here the checker cannot produce a {@link Verification} for a declared clause because it cannot
 * construct one: {@link Declaration} has no pass/fail at all, only whether the label was present.
 */
public record ConformanceReport(String image, List<Verification> verified, List<Declaration> declared) {

    public ConformanceReport {
        Objects.requireNonNull(image, "image");
        verified = List.copyOf(Objects.requireNonNull(verified, "verified"));
        declared = List.copyOf(Objects.requireNonNull(declared, "declared"));
    }

    /**
     * A clause this checker proved or disproved.
     *
     * <p>{@code detail} names what was observed, never merely "failed": an operator holding a
     * conformance failure has to be able to fix the image, and "mount-points: FAIL" without the
     * ownership it actually found sends them to read this checker's source.
     */
    public record Verification(String id, boolean passed, String detail) {

        public Verification {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(detail, "detail");
        }

        static Verification passed(String id, String detail) {
            return new Verification(id, true, detail);
        }

        static Verification failed(String id, String detail) {
            return new Verification(id, false, detail);
        }
    }

    /**
     * A clause the image claims and this checker cannot prove.
     *
     * <p>There is no {@code passed} here on purpose. A declaration is a fact about what the image
     * SAID; the only two states are that it said something and that it said nothing, and neither is
     * a conformance result. {@code whyNotVerifiable} travels with it so the report explains its own
     * limits rather than leaving a reader to assume the checker was lazy.
     */
    public record Declaration(String id, String claimed, String whyNotVerifiable) {

        public Declaration {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(whyNotVerifiable, "whyNotVerifiable");
        }

        /** Whether the image declared anything at all for this clause. */
        public boolean isPresent() {
            return claimed != null && !claimed.isBlank();
        }
    }

    /** Conformance is about the verified half only; a declaration cannot pass or fail. */
    public boolean conforms() {
        return verified.stream().allMatch(Verification::passed);
    }

    public List<Verification> failures() {
        return verified.stream().filter(verification -> !verification.passed()).toList();
    }

    /**
     * The operator-facing report.
     *
     * <p>Two headed sections, and the declared one says out loud that it is not verification. A
     * reader who skims must not come away with "everything above the fold was checked".
     */
    public String render() {
        StringBuilder out = new StringBuilder();
        out.append("agent image: ").append(image).append('\n');

        out.append("\nVERIFIED — checked against this image\n");
        for (Verification verification : verified) {
            out.append(verification.passed() ? "  PASS  " : "  FAIL  ")
                    .append(verification.id())
                    .append("  ")
                    .append(verification.detail())
                    .append('\n');
        }

        out.append("\nDECLARED — the image says so; this command did NOT verify it\n");
        for (Declaration declaration : declared) {
            out.append(declaration.isPresent() ? "  says  " : "  none  ")
                    .append(declaration.id())
                    .append("  ")
                    .append(declaration.isPresent() ? declaration.claimed() : "(no label)")
                    .append("  — ")
                    .append(declaration.whyNotVerifiable())
                    .append('\n');
        }

        out.append('\n')
                .append(conforms()
                        ? "CONFORMS: every verified clause passed."
                        : "DOES NOT CONFORM: " + failures().size() + " verified clause(s) failed.")
                .append('\n');
        return out.toString();
    }
}
