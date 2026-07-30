package dev.codespire.contract.scm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 403 case is the whole reason this lives on the interface rather than as a raw status
 * compare at each call site: at least one SCM answers 403 for rate limiting as well as for
 * permission denial, so treating 403 as a dead credential would report a throttled repo as a
 * broken token and send the operator to rotate a key that was fine.
 */
class ScmApiExceptionTest {

    private static ScmApiException withStatus(int status) {
        return () -> status;
    }

    @Test
    void a401IsAnUnauthorizedCredential() {
        assertTrue(withStatus(401).isUnauthorized());
    }

    @Test
    void a403IsNotTreatedAsAnUnauthorizedCredential() {
        assertFalse(withStatus(403).isUnauthorized());
    }

    @Test
    void otherStatusesAreNotUnauthorized() {
        assertFalse(withStatus(404).isUnauthorized());
        assertFalse(withStatus(429).isUnauthorized());
        assertFalse(withStatus(500).isUnauthorized());
    }

    /** An adapter that can tell its own 403s apart is free to widen the answer. */
    @Test
    void anAdapterMayWidenTheAnswer() {
        ScmApiException widened = new ScmApiException() {
            @Override
            public int status() {
                return 403;
            }

            @Override
            public boolean isUnauthorized() {
                return true;
            }
        };
        assertTrue(widened.isUnauthorized());
    }
}
