package dev.codespire.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.LineRange;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.Severity;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.DiffRefs;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ADR-013 contract-compat gate, first slice: every wire type round-trips
 * through the polymorphic JSON format. A breaking change to the sealed
 * hierarchies fails here before it reaches a topic.
 */
class WireFormatRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void integrationEventRoundTripsWithTypeDiscriminator() throws Exception {
        IntegrationEvent original = new IntegrationEvent.PullRequestEventReceived(
                new RepoRef("sandbox", "demo-repo"), 42, IntegrationEvent.PrAction.OPENED,
                "title", "description", "feature/x", "main",
                DiffRefs.headOnly("abc123def456"),
                Author.of("acc-1", "jdoe", "J. Doe"), "https://example.invalid/pr/42");

        // Root-level polymorphism needs writerFor — exactly what the Kafka
        // serializers do; a bare writeValueAsString(obj) drops the type field.
        String json = mapper.writerFor(IntegrationEvent.class).writeValueAsString(original);
        assertTrue(json.contains("\"type\":\"PullRequestEventReceived\""), json);

        IntegrationEvent back = mapper.readValue(json, IntegrationEvent.class);
        assertEquals(original, back);
    }

    @Test
    void resultEventWithNestedFindingsRoundTrips() throws Exception {
        IntegrationEvent original = new IntegrationEvent.ReviewGenerated(
                "review::sandbox/demo-repo#42", 42, "abc123",
                new ReviewResult(
                        List.of(new Finding("src/App.java", new LineRange(3, 4), Severity.MAJOR, "msg", null)),
                        "summary", new ModelUsage("m", 10, 5, 0)));
        IntegrationEvent back = mapper.readValue(
                mapper.writerFor(IntegrationEvent.class).writeValueAsString(original), IntegrationEvent.class);
        assertEquals(original, back);
    }

    @Test
    void actionCommandRoundTripsWithTypeDiscriminator() throws Exception {
        ActionCommand original = new ActionCommand.GenerateReview(
                "review::sandbox/demo-repo#42", new RepoRef("sandbox", "demo-repo"),
                42, "abc123", null, 1, null, "cred-ciphertext");

        String json = mapper.writerFor(ActionCommand.class).writeValueAsString(original);
        assertTrue(json.contains("\"type\":\"GenerateReview\""), json);

        ActionCommand back = mapper.readValue(json, ActionCommand.class);
        assertEquals(original, back);
    }

    @Test
    void everyIntegrationEventSubtypeIsRegisteredForSerialization() throws Exception {
        // A subtype missing from @JsonSubTypes would serialize but fail to
        // deserialize — catch the registration gap for the whole hierarchy.
        for (Class<?> subtype : IntegrationEvent.class.getPermittedSubclasses()) {
            var registered = IntegrationEvent.class.getAnnotation(com.fasterxml.jackson.annotation.JsonSubTypes.class);
            boolean found = java.util.Arrays.stream(registered.value())
                    .anyMatch(t -> t.value().equals(subtype));
            assertTrue(found, subtype.getSimpleName() + " is not registered in @JsonSubTypes");
        }
        for (Class<?> subtype : ActionCommand.class.getPermittedSubclasses()) {
            var registered = ActionCommand.class.getAnnotation(com.fasterxml.jackson.annotation.JsonSubTypes.class);
            boolean found = java.util.Arrays.stream(registered.value())
                    .anyMatch(t -> t.value().equals(subtype));
            assertTrue(found, subtype.getSimpleName() + " is not registered in @JsonSubTypes");
        }
    }
}
