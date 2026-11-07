package dev.codespire.orchestrator.llm;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * A response body kept up to a byte limit, and finished the moment it has the limit.
 *
 * <p>Finishing early is what lets the caller go on without the rest of the body: the response is
 * delivered as soon as this completes, and the caller then shuts its client down, which closes the
 * connection and ends whatever the body still had to send. A limit of 0 finishes at once and asks for
 * nothing, for a body nobody needs. Bytes past the limit are never kept. A body that ends early, or
 * breaks, finishes with what arrived: the result is only a hint for classification.
 *
 * <p>It does not cancel its subscription. Cancelling from here, before or during the body, made the
 * JDK client fail or hang the exchange instead of delivering the response; closing the client is the
 * reliable way to stop a body.
 */
final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {

    private final int limit;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();

    BoundedBody(int limit) {
        this.limit = limit;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        if (limit == 0) {
            result.complete(new byte[0]);
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
                result.complete(bytes.toByteArray());
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
}
