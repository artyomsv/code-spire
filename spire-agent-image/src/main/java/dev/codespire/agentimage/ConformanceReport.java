package dev.codespire.agentimage;

import java.util.List;
import java.util.Objects;

/**
 * What {@code spire-agent-image verify} answers about one image (FR-F13).
 *
 * <p><b>The two halves are separate lists, and that is the whole design.</b> A verified clause is
 * one this checker PROVED, by reading the image config or by running it. A declared clause is one
 * the image CLAIMS through a label and the checker cannot prove — proving it would need something
 * the checker does not have, most often the operator's repository.
 *
 * <p>A report that blends them is a report nobody can act on. "Toolchain: OK" reads as proof; if it
 * only means the label was present, then an image declaring a toolchain it does not carry passes,
 * and the first thing to notice is a run that has already been paid for.
 *
 * <p><b>The split is structural rather than a flag on one type.</b> A single {@code Clause} carrying
 * an {@code assurance} field would leave "report a declared clause as verified" one line away, and
 * that line would look correct — the same reasoning that gives {@code HostMount} no writable form.
 * Here the checker cannot produce a {@link Verification} for a declared clause because it cannot
 * construct one: {@link Declaration} has no pass/fail at all, and the compact constructor below
 * refuses a verification carrying a declared clause's id.
 *
 * <p><b>And the split survives the terminal.</b> Every string an IMAGE controls is stripped of
 * control characters before it can reach {@link #render()}. Docker stores {@code ESC}, {@code CR}
 * and {@code LF} in a label verbatim — measured — so a hostile label could otherwise print
 * {@code \r\n  PASS  …} to forge lines in the verified half, or an SGR conceal to hide the
 * {@code DOES NOT CONFORM} line that comes after it. Separating the halves in the data model buys
 * nothing if the image can re-blend them on the screen the operator reads.
 */
public record ConformanceReport(String image, List<Verification> verified, List<Declaration> declared) {

    /** Marks a clause the checker could not answer, so the CLI can exit differently for it. */
    static final String NOT_CHECKED = "NOT CHECKED";

    public ConformanceReport {
        Objects.requireNonNull(image, "image");
        verified = List.copyOf(Objects.requireNonNull(verified, "verified"));
        declared = List.copyOf(Objects.requireNonNull(declared, "declared"));
        if (verified.isEmpty()) {
            // conforms() is an allMatch, which is vacuously true over nothing — so an empty report
            // would render "CONFORMS: every verified clause passed". That is the shape this
            // repository already paid for once in a contract-snapshot test.
            throw new IllegalArgumentException("a conformance report with no verified clause would "
                    + "render as CONFORMS while having checked nothing");
        }
        for (Verification verification : verified) {
            if (Clauses.DECLARED.contains(verification.id())) {
                throw new IllegalArgumentException("\"" + verification.id() + "\" is a DECLARED "
                        + "clause and cannot be reported as verified; nothing proved it");
            }
        }
    }

    /**
     * A clause this checker proved, disproved, or could not reach.
     *
     * <p>{@code detail} names what was observed, never merely "failed": an operator holding a
     * conformance failure has to be able to fix the image, and "mount-points: FAIL" without the
     * ownership it actually found sends them to read this checker's source.
     *
     * <p>{@code notChecked} is carried as a field rather than inferred from the detail text, because
     * prefix-matching prose to decide an exit code is a coupling that breaks the first time somebody
     * rewords a message.
     */
    public record Verification(String id, boolean passed, boolean notChecked, String detail) {

        public Verification {
            Objects.requireNonNull(id, "id");
            detail = printable(Objects.requireNonNull(detail, "detail"));
            if (passed && notChecked) {
                throw new IllegalArgumentException("a clause that was not checked cannot have passed");
            }
        }

        static Verification passed(String id, String detail) {
            return new Verification(id, true, false, detail);
        }

        static Verification failed(String id, String detail) {
            return new Verification(id, false, false, detail);
        }

        /** The checker could not answer. A failure in the report, and not the image's fault. */
        static Verification notChecked(String id, String why) {
            return new Verification(id, false, true,
                    NOT_CHECKED + " — " + why + ". This is a checker problem, not necessarily an "
                            + "image one.");
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
            claimed = printable(claimed);
            Objects.requireNonNull(whyNotVerifiable, "whyNotVerifiable");
        }

        /** Whether the image declared anything at all for this clause. */
        public boolean isPresent() {
            return claimed != null && !claimed.isBlank();
        }
    }

    /**
     * Replaces every control character with {@code ?}.
     *
     * <p>Applied in the constructors so no path can reach {@link #render()} carrying one, whatever
     * a future caller does. C0, DEL, C1 and the two Unicode line separators — the last because a
     * terminal and a log parser disagree about whether they end a line, which is enough to forge
     * one in a CI log.
     */
    static String printable(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder safe = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> safe.appendCodePoint(
                codePoint < 0x20 || codePoint == 0x7f
                        || (codePoint >= 0x80 && codePoint <= 0x9f)
                        || codePoint == 0x2028 || codePoint == 0x2029
                        ? '?' : codePoint));
        return safe.toString();
    }

    /** Conformance is about the verified half only; a declaration cannot pass or fail. */
    public boolean conforms() {
        return verified.stream().allMatch(Verification::passed);
    }

    /**
     * Whether any clause went unanswered.
     *
     * <p>The CLI exits differently for this: "I could not check this image" and "this image is
     * wrong" call for opposite actions, and a pipeline that treats them alike eventually fails a
     * good image because a daemon was busy. An earlier version said exactly that in a comment while
     * returning the same code for both.
     */
    public boolean anyNotChecked() {
        return verified.stream().anyMatch(Verification::notChecked);
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
        out.append("agent image: ").append(printable(image)).append('\n');

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
