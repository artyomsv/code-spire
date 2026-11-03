package dev.codespire.contract.review;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * The files a harness review's agent reads at {@code /inputs} (harness reviews, step 6): {@code context.md} and, on a
 * re-review, {@code prior-findings.md}. Plain text on the bus, as findings already are (ADR-014); never a diff
 * (ADR-011). Its {@code toString} prints names and byte counts, never text.
 */
public record HarnessReviewInputs(List<InputFile> files) {

    /** The total UTF-8 bytes of every file's text. */
    public static final int MAX_BYTES = 224 * 1024;

    public static final String CONTEXT = "context.md";

    public static final String PRIOR_FINDINGS = "prior-findings.md";

    /** A reply's only input (harness reviews, step 7): the thread, its anchor and the review's other findings. */
    public static final String THREAD = "thread.md";

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]*\\.md");

    public static final HarnessReviewInputs NONE = new HarnessReviewInputs(List.of());

    public HarnessReviewInputs {
        files = List.copyOf(Objects.requireNonNull(files, "files"));
        Set<String> names = new HashSet<>();
        long total = 0;
        for (InputFile file : files) {
            if (!names.add(file.name())) throw new IllegalArgumentException("Two input files are named " + file.name());
            total += file.bytes();
        }
        if (total > MAX_BYTES) {
            throw new IllegalArgumentException("Inputs of " + total + " bytes exceed the cap of " + MAX_BYTES);
        }
    }

    /** One file: a plain Markdown name, so it can name no other directory, and its text. */
    public record InputFile(String name, String text) {

        public InputFile {
            if (name == null || !NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("An input file needs a plain Markdown name, not " + name);
            }
            Objects.requireNonNull(text, "text");
        }

        /** The UTF-8 bytes of the text. */
        public int bytes() {
            return text.getBytes(StandardCharsets.UTF_8).length;
        }

        @Override
        public String toString() {
            return name + "=" + bytes() + " bytes";
        }
    }

    /** Every file's text by its name, in order. */
    public Map<String, String> asMap() {
        Map<String, String> map = new LinkedHashMap<>();
        for (InputFile file : files) {
            map.put(file.name(), file.text());
        }
        return Collections.unmodifiableMap(map);
    }

    @Override
    public String toString() {
        StringJoiner printed = new StringJoiner(", ", "HarnessReviewInputs[", "]");
        for (InputFile file : files) {
            printed.add(file.toString());
        }
        return printed.toString();
    }
}
