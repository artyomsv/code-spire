package dev.codespire.runtime.docker;

import dev.codespire.runtime.UnitInputs;

/** The tar the runtime copies into a created init container's inputs volume (harness reviews, step 6). */
final class InputsArchive {

    private InputsArchive() {
    }

    static byte[] of(UnitInputs inputs) {
        throw new UnsupportedOperationException("not built yet");
    }
}
