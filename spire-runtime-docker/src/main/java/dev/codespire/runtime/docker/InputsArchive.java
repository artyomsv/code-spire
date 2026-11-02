package dev.codespire.runtime.docker;

import dev.codespire.runtime.UnitInputs;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileTime;
import java.util.Map;

/**
 * The tar the runtime copies into a created init container's inputs volume (harness reviews, step 6): one entry per
 * file, owned by root and readable by all ({@code 0444}), dated the epoch so the same inputs make the same bytes.
 */
final class InputsArchive {

    /** A regular file, read-only for everyone: the agent reads it as whatever user its image runs as. */
    private static final int READ_ONLY_FILE = 0100444;

    private InputsArchive() {
    }

    static byte[] of(UnitInputs inputs) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Map.Entry<String, String> file : inputs.files().entrySet()) {
                byte[] text = file.getValue().getBytes(StandardCharsets.UTF_8);
                tar.putArchiveEntry(entry(file.getKey(), text.length));
                tar.write(text);
                tar.closeArchiveEntry();
            }
            tar.finish();
        } catch (IOException notWritten) {
            // In memory, so this is not an I/O fault but a defect: say so rather than declare a checked exception.
            throw new UncheckedIOException("could not build the inputs archive", notWritten);
        }
        return bytes.toByteArray();
    }

    private static TarArchiveEntry entry(String name, long size) {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(size);
        entry.setMode(READ_ONLY_FILE);
        entry.setUserId(0);
        entry.setGroupId(0);
        entry.setUserName("root");
        entry.setGroupName("root");
        entry.setModTime(FileTime.fromMillis(0));
        return entry;
    }
}
