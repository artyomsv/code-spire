package dev.codespire.worker.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.port.BlobStore;
import dev.codespire.contract.review.ContextItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Loads the context the aggregator assembled for a review, for both review paths (harness reviews, step 6). A null
 * ref, a vanished blob or an unreadable one all read as no context: the review still runs, without it.
 */
@ApplicationScoped
public class AssembledContextReader {

    @Inject
    BlobStore blobStore;

    @Inject
    ObjectMapper mapper;

    public List<ContextItem> load(String contextRef) {
        throw new UnsupportedOperationException("not built yet");
    }
}
