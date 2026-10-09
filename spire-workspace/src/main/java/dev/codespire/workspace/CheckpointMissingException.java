package dev.codespire.workspace;

import java.io.IOException;

/** The gated checkpoint head is in no readable handoff bundle: the kept build cannot be rebuilt (M4). */
public final class CheckpointMissingException extends IOException {
    public CheckpointMissingException(String head) {
        super("checkpoint " + head + " is in no readable bundle; the kept build may have been removed");
    }
}
