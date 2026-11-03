package dev.codespire.worker.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.port.BlobStore;
import dev.codespire.contract.review.AssembledContext;
import dev.codespire.contract.review.ContextItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.List;

/**
 * Loads the context the aggregator assembled for a review, for both review paths (harness reviews, step 6). A null
 * ref, a vanished blob or an unreadable one all read as no context: the review still runs, without it.
 */
@ApplicationScoped
public class AssembledContextReader {

    private static final Logger LOG = Logger.getLogger(AssembledContextReader.class);

    @Inject
    BlobStore blobStore;

    @Inject
    ObjectMapper mapper;

    /**
     * Loads the assembled context the aggregator persisted for this run. A null
     * ref (no context source configured) or a vanished blob (deleted / superseded
     * by a re-run mid-flight) both degrade to an empty list — the review still
     * runs, just without ticket context.
     */
    public List<ContextItem> load(String contextRef) {
        if (contextRef == null || contextRef.isBlank()) {
            return List.of();
        }
        byte[] bytes = blobStore.get(new BlobStore.BlobRef(contextRef));
        if (bytes == null) {
            LOG.debugf("Context blob %s not found — reviewing without context", contextRef);
            return List.of();
        }
        try {
            AssembledContext assembled = mapper.readValue(bytes, AssembledContext.class);
            return assembled.items() == null ? List.of() : assembled.items();
        } catch (IOException e) {
            LOG.warnf(e, "Unreadable context blob %s — reviewing without context", contextRef);
            return List.of();
        }
    }
}
