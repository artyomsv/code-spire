package dev.codespire.contract.scm;

import java.util.List;

/**
 * A review thread as re-fetched from the SCM (spec §5) — never persisted. Carries the conversation and the
 * code anchor ({@code path}/{@code line}/{@code commit}) that the whole thread hangs on.
 */
public record ThreadTranscript(ThreadRef threadRef, String path, int line, String commit,
                               List<ThreadMessage> messages) {

    public ThreadTranscript {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
