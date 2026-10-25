package dev.codespire.publisher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Reads the agent's handoff files: the specification or plan ({@value #ARTIFACT}) and the step summary
 * ({@value #STEP_SUMMARY}), M4 slice 3.
 *
 * <p>Everything about such a file is the agent's choice, since the agent container mounts {@code /handoff} read-write:
 * it may be a link to a file the agent could never read itself, a FIFO that blocks the first open forever, or a file
 * that grows while it is read. So the attributes are read without following a link, only a regular file is opened
 * (again without following one), the size is checked before the read and the bytes are counted during it, and the
 * text must be valid UTF-8. Each refusal is typed, so the run worker can say which one it was (A9).
 */
final class HandoffFiles {

    static final String ARTIFACT = "artifact.md";

    static final String STEP_SUMMARY = "step-summary.json";

    /** The artifact transport cap; the orchestrator's own caps (specification 16 KiB, plan 32 KiB) sit inside it. */
    static final int ARTIFACT_MAX_BYTES = 32 * 1024;

    static final int STEP_SUMMARY_MAX_BYTES = 8 * 1024;

    enum Refusal { SYMLINK, NOT_REGULAR, TOO_LARGE, NOT_UTF8 }

    sealed interface Read {
        record Found(String text) implements Read {
        }

        record Absent() implements Read {
        }

        record Refused(Refusal why) implements Read {
        }
    }

    private HandoffFiles() {
    }

    static Read read(Path dir, String name, int maxBytes) throws IOException {
        Path file = dir.resolve(name);
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) {
            return new Read.Absent();
        }
        if (attributes.isSymbolicLink()) {
            return new Read.Refused(Refusal.SYMLINK);
        }
        if (!attributes.isRegularFile()) {
            return new Read.Refused(Refusal.NOT_REGULAR);
        }
        if (attributes.size() >= maxBytes) {
            return new Read.Refused(Refusal.TOO_LARGE);
        }
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            return decode(in, maxBytes);
        }
    }

    /**
     * Reads at most one byte past the cap, so a file that grew after its size was checked is refused rather than
     * truncated into a shorter, valid-looking text.
     */
    static Read decode(InputStream in, int maxBytes) throws IOException {
        byte[] bytes = in.readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) {
            return new Read.Refused(Refusal.TOO_LARGE);
        }
        try {
            return new Read.Found(StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString());
        } catch (CharacterCodingException notUtf8) {
            return new Read.Refused(Refusal.NOT_UTF8);
        }
    }
}
