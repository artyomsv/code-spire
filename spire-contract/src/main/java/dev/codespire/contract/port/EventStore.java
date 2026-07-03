package dev.codespire.contract.port;

import dev.codespire.contract.event.EventEnvelope;

import java.util.List;

/**
 * The append-only event log (DATA-MODEL §3). Single-writer per stream with
 * optimistic concurrency: appending at an already-taken sequence throws
 * {@link ConcurrencyException} — reload, re-decide, retry.
 */
public interface EventStore {

    List<EventEnvelope> load(String streamId);

    /** Appends events at expectedNextSequence, expectedNextSequence+1, ... */
    void append(String streamId, long expectedNextSequence, List<EventEnvelope> events);

    class ConcurrencyException extends RuntimeException {
        public ConcurrencyException(String streamId, long sequence) {
            super("Stream " + streamId + " already has sequence " + sequence);
        }
    }
}
