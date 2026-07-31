package dev.codespire.worker.adapters;

import dev.codespire.contract.port.BlobStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class PostgresBlobStoreByReviewTest {

    @Inject
    PostgresBlobStore store;

    @Test
    void returnsTheDecryptedPayloadForAReview() {
        String reviewId = "review::acme/widgets#901";
        byte[] payload = "{\"items\":[]}".getBytes(StandardCharsets.UTF_8);
        store.put(BlobStore.Kind.CONTEXT, reviewId, payload);

        assertArrayEquals(payload, store.getByReview(reviewId));

        store.deleteByReview(reviewId);
    }

    /** A review that resolved nothing writes no blob at all — the normal path with no provider. */
    @Test
    void returnsNullWhenTheReviewHasNoBlob() {
        assertNull(store.getByReview("review::acme/widgets#902"));
    }

    /** The lookup key is the owner, so one review can never read another's context. */
    @Test
    void doesNotReturnABlobOwnedByAnotherReview() {
        String mine = "review::acme/widgets#903";
        String theirs = "review::acme/widgets#904";
        store.put(BlobStore.Kind.CONTEXT, theirs, "{\"items\":[1]}".getBytes(StandardCharsets.UTF_8));

        assertNull(store.getByReview(mine));

        store.deleteByReview(theirs);
    }
}
