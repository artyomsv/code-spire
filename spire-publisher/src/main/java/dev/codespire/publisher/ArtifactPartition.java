package dev.codespire.publisher;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuts an artifact too long for one outcome line into ordered parts (harness reviews, step 4), on code-point boundaries.
 *
 * <p>A part holds at most {@link #MAX_PART_BYTES} of UTF-8. That bound is chosen against the worst escaping, not the
 * usual one: JSON writes a control character as six characters (a backslash, {@code u} and four hex digits), and a UTF-8 byte is never fewer
 * than one Java character, so a part's text is at most 6 x 8 KiB = 48 KiB once escaped — under the run worker's 64 KiB
 * line cap with room for the envelope, whatever the agent wrote. The writer strips most control characters first, so
 * real parts are far smaller; the bound does not rely on that.
 */
final class ArtifactPartition {

    static final int MAX_PART_BYTES = 8 * 1024;

    private ArtifactPartition() {
    }

    static List<String> split(String text) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int bytes = 0;
        int at = 0;
        while (at < text.length()) {
            int codePoint = text.codePointAt(at);
            int size = utf8Length(codePoint);
            if (bytes + size > MAX_PART_BYTES) {
                parts.add(text.substring(start, at));
                start = at;
                bytes = 0;
            }
            bytes += size;
            at += Character.charCount(codePoint);
        }
        parts.add(text.substring(start));
        return parts;
    }

    /** A lone surrogate counts as three bytes, more than the one its replacement takes: an overestimate is safe. */
    private static int utf8Length(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        return codePoint < 0x10000 ? 3 : 4;
    }
}
