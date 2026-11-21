package dev.codespire.orchestrator.llm.catalog;

import dev.codespire.orchestrator.llm.catalog.CatalogTestDoubles.FakeStates;

/**
 * The state row as a second instance on a new database sees it: its first read is empty (taken before another instance
 * stored the bundle and accepted a remote file), every later call reaches the shared row. Every write is delegated too,
 * so an unconditional store of the bundle would land in the shared row, where a test can see it.
 */
final class StaleOnceStates extends FakeStates {

    private final FakeStates shared;
    private boolean first = true;

    StaleOnceStates(FakeStates shared) {
        this.shared = shared;
    }

    @Override
    public LlmCatalogState read() {
        if (first) {
            first = false;
            return LlmCatalogState.EMPTY;
        }
        return shared.read();
    }

    @Override
    public boolean storeIfEmpty(StoredDocument doc) {
        return shared.storeIfEmpty(doc);
    }

    @Override
    public void storeDocument(StoredDocument doc) {
        shared.storeDocument(doc);
    }

    @Override
    public void recordFailure(String reason) {
        shared.recordFailure(reason);
    }
}
