package dev.codespire.orchestrator.llm.catalog;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * A response body read to at most {@code cap} bytes: past the cap the subscription is cancelled and the
 * body is complete, so an endless body cannot hold memory or the read. The caller tells a body that hit
 * the cap apart by its length.
 */
final class CappedBody implements HttpResponse.BodySubscriber<byte[]> {

    private final int cap;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;

    CappedBody(int cap) {
        this.cap = cap;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
        return result;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
        for (ByteBuffer buffer : buffers) {
            int take = Math.min(buffer.remaining(), cap - bytes.size());
            byte[] chunk = new byte[take];
            buffer.get(chunk);
            bytes.write(chunk, 0, take);
        }
        if (bytes.size() >= cap) {
            subscription.cancel();
            result.complete(bytes.toByteArray());
        }
    }

    @Override
    public void onError(Throwable failure) {
        result.completeExceptionally(failure);
    }

    @Override
    public void onComplete() {
        result.complete(bytes.toByteArray());
    }
}
