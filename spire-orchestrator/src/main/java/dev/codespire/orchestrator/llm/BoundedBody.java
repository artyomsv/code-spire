package dev.codespire.orchestrator.llm;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * A response body read up to a byte limit, then cancelled.
 *
 * <p>Cancelling the subscription is what releases the exchange: the client stops reading the body and
 * closes or returns the connection, so a large or never-ending body costs no more than the limit and
 * never holds a connection open. A limit of 0 cancels at once, for a body nobody needs. A body that
 * ends early, or breaks, completes with what arrived: the result is only a hint for classification.
 */
final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {

    private final int limit;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;

    BoundedBody(int limit) {
        this.limit = limit;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        if (limit == 0) {
            finish();
            return;
        }
        subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(List<ByteBuffer> items) {
        for (ByteBuffer item : items) {
            if (result.isDone()) {
                return;
            }
            int take = Math.min(item.remaining(), limit - bytes.size());
            byte[] chunk = new byte[take];
            item.get(chunk);
            bytes.write(chunk, 0, take);
            if (bytes.size() >= limit) {
                finish();
            }
        }
    }

    @Override
    public void onError(Throwable failure) {
        result.complete(bytes.toByteArray());
    }

    @Override
    public void onComplete() {
        result.complete(bytes.toByteArray());
    }

    @Override
    public CompletionStage<byte[]> getBody() {
        return result;
    }

    private void finish() {
        subscription.cancel();
        result.complete(bytes.toByteArray());
    }
}
