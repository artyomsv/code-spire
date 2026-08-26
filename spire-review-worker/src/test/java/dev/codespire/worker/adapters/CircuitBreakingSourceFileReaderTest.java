package dev.codespire.worker.adapters;

import dev.codespire.context.code.CodeContextApiException;
import dev.codespire.context.code.SourceFileReader;
import dev.codespire.worker.adapters.ProviderCircuits.CircuitOpenException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The wrap that puts {@code spire-context-code}'s reads behind the shared per-host circuit — built
 * here, in the worker, rather than inside that module, because {@link ProviderCircuits} is
 * worker-owned and ADR-021 forbids the Apache-2.0 adapter from depending on a service module.
 *
 * <p>Its whole risk is the same one {@code CircuitBreakingLlmProviderTest} guards against: counting a
 * failure that was really an answer. Here that means a 404 for a moved or deleted file — the normal
 * case per {@link SourceFileReader#read}'s own contract — must never count toward a circuit shared
 * with the SCM adapters, or a repository with reorganized paths would pause reviewing on its own host.
 */
class CircuitBreakingSourceFileReaderTest {

    private static final String HOST = "code.example.invalid";

    private final AtomicLong now = new AtomicLong();
    private final ProviderCircuits circuits = new ProviderCircuits(now::get);

    /** A real reader already swallows a 404 into null (the interface's own contract) — this mirrors that. */
    private static final SourceFileReader ABSENT_FILE_READER = new SourceFileReader() {
        @Override
        public String read(String repo, String path, String commit) {
            return null;
        }

        @Override
        public String apiHost() {
            return HOST;
        }
    };

    private static SourceFileReader failingWith(int status) {
        return new SourceFileReader() {
            @Override
            public String read(String repo, String path, String commit) {
                throw new CodeContextApiException(status, "GET", path, null);
            }

            @Override
            public String apiHost() {
                return HOST;
            }
        };
    }

    @Test
    void repeatedAbsentFilesNeverOpenTheCircuit() {
        CircuitBreakingSourceFileReader wrapped = new CircuitBreakingSourceFileReader(ABSENT_FILE_READER, circuits);

        for (int i = 0; i < 10; i++) {
            assertNull(wrapped.read("acme/widgets", "src/Gone.java", "cafe1234"));
        }
        // If a null answer counted as a failure, this call would already be refused by an open circuit.
        assertDoesNotThrow(() -> wrapped.read("acme/widgets", "src/Gone.java", "cafe1234"));
    }

    /**
     * Defense in depth: {@link CodeContextApiException}'s own javadoc anticipated a circuit keyed off
     * its {@code isNotFound()}/status distinction, so even a reader that (unlike the real ones) threw a
     * 404 instead of swallowing it must not have that count.
     */
    @Test
    void aThrown404NeverCountsTowardTheCircuit() {
        SourceFileReader reader = failingWith(404);
        CircuitBreakingSourceFileReader wrapped = new CircuitBreakingSourceFileReader(reader, circuits);

        for (int i = 0; i < ProviderCircuits.FAILURE_THRESHOLD * 2; i++) {
            assertThrows(CodeContextApiException.class,
                    () -> wrapped.read("acme/widgets", "src/Gone.java", "cafe1234"));
        }

        // Still reaching the delegate (not CircuitOpenException) proves the circuit never opened.
        CodeContextApiException stillThrown = assertThrows(CodeContextApiException.class,
                () -> wrapped.read("acme/widgets", "src/Gone.java", "cafe1234"));
        assertEquals(404, stillThrown.status());
    }

    /** A rejected credential is an answer too, not illness — mirrors the 404 case above. */
    @Test
    void aRejectedCredentialNeverOpensTheCircuit() {
        SourceFileReader reader = failingWith(401);
        CircuitBreakingSourceFileReader wrapped = new CircuitBreakingSourceFileReader(reader, circuits);

        for (int i = 0; i < ProviderCircuits.FAILURE_THRESHOLD * 2; i++) {
            assertThrows(CodeContextApiException.class,
                    () -> wrapped.read("acme/widgets", "src/Alpha.java", "cafe1234"));
        }

        CodeContextApiException stillThrown = assertThrows(CodeContextApiException.class,
                () -> wrapped.read("acme/widgets", "src/Alpha.java", "cafe1234"));
        assertEquals(401, stillThrown.status());
    }

    @Test
    void repeatedServerFailuresOpenTheCircuit() {
        SourceFileReader reader = failingWith(500);
        CircuitBreakingSourceFileReader wrapped = new CircuitBreakingSourceFileReader(reader, circuits);

        for (int i = 0; i < ProviderCircuits.FAILURE_THRESHOLD; i++) {
            assertThrows(CodeContextApiException.class,
                    () -> wrapped.read("acme/widgets", "src/Alpha.java", "cafe1234"));
        }

        assertThrows(CircuitOpenException.class,
                () -> wrapped.read("acme/widgets", "src/Alpha.java", "cafe1234"));
    }

    @Test
    void apiHostDelegatesDirectly() {
        CircuitBreakingSourceFileReader wrapped = new CircuitBreakingSourceFileReader(ABSENT_FILE_READER, circuits);
        assertEquals(HOST, wrapped.apiHost());
    }
}
