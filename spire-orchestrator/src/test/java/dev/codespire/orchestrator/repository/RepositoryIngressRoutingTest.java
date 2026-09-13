package dev.codespire.orchestrator.repository;

import dev.codespire.contract.event.*;
import dev.codespire.contract.scm.*;
import dev.codespire.orchestrator.pipeline.IntegrationSaga;
import java.util.*;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryIngressRoutingTest {
    private final UUID id = UUID.randomUUID();
    private final RepoRef repo = new RepoRef("TEST-group", "TEST-repo");
    private RepositoryView selected = new RepositoryView(id, "gitlab", "https://forge.example.test",
            repo.workspace(), repo.slug(), true, 1, null, null);
    private final List<IntegrationEvent> reviews = new ArrayList<>();
    private final List<RepositoryDelivery> activities = new ArrayList<>();
    private final List<RepositoryDelivery> missing = new ArrayList<>();

    @Test void factoryActivityNeverEntersTheReviewLifecycle() {
        RepositoryDelivery reviewer = delivery(id, RepositoryEventKind.REVIEWER, opened());
        consumer().accept(reviewer);
        assertEquals(List.of(reviewer.event()), reviews);
        reviews.clear();
        RepositoryDelivery activity = delivery(id, RepositoryEventKind.FACTORY, opened());
        consumer().accept(activity);
        assertEquals(List.of(activity), activities);
        assertTrue(reviews.isEmpty());
    }

    @Test void aDisabledRepositoryCannotProcessAnAlreadyVerifiedDelivery() {
        consumer().accept(delivery(id, RepositoryEventKind.REVIEWER, opened()));
        assertEquals(1, reviews.size()); reviews.clear();
        selected = new RepositoryView(id, selected.scmType(), selected.forgeOrigin(), repo.workspace(), repo.slug(), false, 2, null, null);
        consumer().accept(delivery(id, RepositoryEventKind.REVIEWER, opened()));
        assertTrue(reviews.isEmpty());
    }

    @Test void anExplicitRepositoryIdCannotNameAnotherMatchingPath() {
        consumer().accept(delivery(id, RepositoryEventKind.REVIEWER, opened()));
        assertEquals(1, reviews.size()); reviews.clear();
        consumer().accept(delivery(UUID.randomUUID(), RepositoryEventKind.REVIEWER, opened()));
        assertTrue(reviews.isEmpty());
    }

    @Test void aFactoryHookCannotDeliverReviewerCommands() {
        var reply = reply(ReviewIds.reviewId(repo, 7));
        consumer().accept(delivery(id, RepositoryEventKind.REVIEWER, reply));
        assertEquals(List.of(reply), reviews); reviews.clear();
        consumer().accept(delivery(id, RepositoryEventKind.FACTORY, reply));
        assertTrue(reviews.isEmpty()); assertTrue(activities.isEmpty());
    }

    @Test void aReplyCannotNameAnotherReviewThanItsRepositoryCoordinates() {
        consumer().accept(delivery(id, RepositoryEventKind.REVIEWER, reply(ReviewIds.reviewId(repo, 7))));
        assertEquals(1, reviews.size()); reviews.clear();
        consumer().accept(delivery(id, RepositoryEventKind.REVIEWER, reply("review::TEST-other/TEST-repo#7")));
        assertTrue(reviews.isEmpty());
    }

    @Test void aMissingOriginIsAttentionEvenWhenThePathIsRegistered() {
        RepositoryDelivery known = delivery(id, RepositoryEventKind.REVIEWER, opened());
        consumer().accept(known); assertEquals(1, reviews.size()); reviews.clear();
        RepositoryDelivery unknown = new RepositoryDelivery(null, UUID.randomUUID(), 1, "gitlab", null,
                RepositoryEventKind.REVIEWER, "TEST-unknown-origin", opened());
        consumer().accept(unknown);
        assertEquals(List.of(unknown), missing); assertTrue(reviews.isEmpty());
    }

    private RepositoryDelivery delivery(UUID repository, RepositoryEventKind kind, IntegrationEvent event) {
        return new RepositoryDelivery(repository, null, 0, "gitlab", selected.forgeOrigin(), kind, "TEST-delivery", event);
    }
    private IntegrationEvent opened() {
        return new IntegrationEvent.PullRequestEventReceived(repo, 7, IntegrationEvent.PrAction.OPENED,
                "TEST", "", "TEST-feature", "main", "abc1234", Author.of("TEST-id", "TEST-human", ""),
                selected.forgeOrigin() + "/" + repo.full() + "/-/merge_requests/7", "gitlab");
    }
    private IntegrationEvent reply(String reviewId) {
        return new IntegrationEvent.AuthorReplied(repo, 7, reviewId, new ThreadRef("TEST-thread"), "TEST-comment",
                "TEST question", Author.of("TEST-id", "TEST-human", ""));
    }
    @SuppressWarnings("unchecked")
    private RepositoryIngressConsumer consumer() {
        RepositoryIngressConsumer consumer = new RepositoryIngressConsumer();
        consumer.repositories = new RepositoryRegistry() {
            @Override public Optional<RepositoryView> find(String type, String origin, String workspace, String slug) {
                assertEquals("gitlab", type); assertEquals(repo.workspace(), workspace); assertEquals(repo.slug(), slug);
                return Optional.of(selected);
            }
        };
        consumer.reviews = new IntegrationSaga() {
            @Override public void onRepository(IntegrationEvent event, UUID repository) {
                assertEquals(id, repository); reviews.add(event);
            }
        };
        consumer.unregistered = new UnregisteredRepositoryEvents() {
            @Override public void record(RepositoryDelivery delivery) { missing.add(delivery); }
        };
        consumer.activities = (Emitter<RepositoryDelivery>) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Emitter.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("send")) throw new AssertionError("Unexpected emitter operation");
                    Message<RepositoryDelivery> message = (Message<RepositoryDelivery>) args[0];
                    activities.add(message.getPayload());
                    assertEquals(id.toString(), message.getMetadata(io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata.class).orElseThrow().getKey());
                    message.ack().toCompletableFuture().join();
                    return null;
                });
        return consumer;
    }
}
