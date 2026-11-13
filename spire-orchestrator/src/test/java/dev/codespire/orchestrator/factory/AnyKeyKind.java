package dev.codespire.orchestrator.factory;

import java.util.Optional;

/** The pool's answer over every key kind, for suites about the pool itself rather than one harness's vendor. */
final class AnyKeyKind {
    private AnyKeyKind() {}

    static HarnessCredentialPool.Selection select(HarnessCredentialPool pool) {
        return pool.select(Optional.empty());
    }
}
