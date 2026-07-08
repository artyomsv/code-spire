-- Worker-owned (schema-per-service): the assembled review context, encrypted
-- client-side before it is written (the DB only ever sees ciphertext), keyed by
-- the contextRef threaded on ContextAssembled -> GenerateReview (CONTRACT §8,
-- DATA-MODEL §4). review_id is a first-class column so a review's blobs are
-- always reachable for deletion: on review delete AND on re-run (which supersedes
-- the prior contextRef), everything is cleared by `WHERE review_id = ?` — no orphans.
CREATE TABLE context_blob (
    context_id TEXT PRIMARY KEY,            -- BlobRef.key (UUID)
    review_id  TEXT NOT NULL,              -- owner; the deletion key
    kind       TEXT NOT NULL,              -- BlobStore.Kind (CONTEXT)
    ciphertext BYTEA NOT NULL,             -- Tink AES-256-GCM; AAD = review_id
    aad        TEXT NOT NULL,              -- the AAD used, so get() can decrypt standalone
    size_bytes INTEGER NOT NULL,           -- plaintext length (observability)
    media_type TEXT NOT NULL,              -- 'application/json' for assembled context
    filename   TEXT,                       -- null for context; used when the store holds real files (D12)
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX context_blob_review ON context_blob (review_id);
