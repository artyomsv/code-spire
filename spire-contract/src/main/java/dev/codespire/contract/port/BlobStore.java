package dev.codespire.contract.port;

/**
 * Transient encrypted object storage (S3/MinIO, DATA-MODEL §4). Holds ONLY the
 * assembled context (TTL auto-deleted); payloads are Tink-encrypted client-side
 * inside the adapter — the store only ever sees ciphertext. Lands with P2.
 */
public interface BlobStore {

    enum Kind { CONTEXT }

    record BlobRef(String key) {
    }

    BlobRef put(Kind kind, String reviewId, byte[] plaintext);

    byte[] get(BlobRef ref);

    void delete(BlobRef ref);
}
