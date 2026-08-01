package dev.codespire.contract;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.event.DomainEvent;
// RecordCommand is imported for the javadoc on ROOTS that explains why it is excluded.
import dev.codespire.contract.event.IntegrationEvent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ADR-013 contract-compat gate, second slice: a snapshot of every wire type's shape.
 *
 * <p>{@code WireFormatRoundTripTest} proves the polymorphic format works, but it does so on three
 * hand-picked types — it cannot notice that a field was renamed on the twelve it does not sample.
 * The event store replays persisted JSON, so a rename, a removal, or a reordered record component
 * is a breaking change to data already written, and it is silent: the code compiles, the tests pass,
 * and the damage only appears when an old event is read back.
 *
 * <p>This snapshots structure rather than instances deliberately. A golden-JSON-per-event gate needs
 * a constructed instance of all forty-odd types and grows a maintenance burden that invites deletion;
 * reflection over the sealed hierarchies covers every type at zero marginal cost per type, and covers
 * new ones the day they are added.
 *
 * <p><b>When this fails</b> the diff tells you what changed. Adding a nullable component is
 * backward-compatible (old JSON simply omits it — {@code IntegrationEvent.providerType} was added
 * exactly that way). Renaming or removing one is NOT: bump {@code eventVersion} and write an
 * upcaster, then update the golden file in the same commit so the change is visible in review.
 */
class ContractSchemaSnapshotTest {

    private static final String GOLDEN = "/contract-schema.txt";

    /**
     * The sealed roots that cross a topic or land in the event store.
     *
     * <p>{@link RecordCommand} is deliberately absent: sagas hand it to the decider in-process and it
     * is never serialized, so its shape is not a compatibility surface. Including it would make this
     * gate fire on changes that break nothing, which is how a gate earns a reflexive "just update the
     * golden" and stops being read.
     */
    private static final List<Class<?>> ROOTS =
            List.of(IntegrationEvent.class, DomainEvent.class, ActionCommand.class);

    /**
     * The roots that MUST carry {@code @JsonSubTypes}, named explicitly rather than discovered.
     *
     * <p>{@link DomainEvent} is deliberately absent — it is named by simple name through the
     * orchestrator's {@code EventTypes} registry, guarded there by {@code EventTypesCoverageTest}.
     */
    private static final List<Class<?>> KAFKA_ROOTS =
            List.of(IntegrationEvent.class, ActionCommand.class);

    @Test
    void wireShapeMatchesTheRecordedSnapshot() throws IOException {
        String actual = renderSchema();
        String expected = readGolden();

        assertEquals(expected, actual, """
                The wire contract changed. Adding a NULLABLE component is backward-compatible — old \
                JSON omits it and it deserializes as null. Renaming, removing, or retyping one is \
                not: persisted events replay through these types, so bump eventVersion and write an \
                upcaster (ADR-013) before updating src/test/resources/contract-schema.txt.""");
    }

    /**
     * Guards the guard: reflection that silently found nothing would make the snapshot an empty file
     * that matches forever. Every root must be sealed and non-empty, and every permitted subtype a
     * record — the assumption the renderer is built on.
     */
    @Test
    void everyRootContributesRecordSubtypes() {
        for (Class<?> root : ROOTS) {
            Class<?>[] permitted = root.getPermittedSubclasses();
            assertNotNull(permitted, root.getSimpleName() + " is not sealed");
            assertTrue(permitted.length > 0, root.getSimpleName() + " permits no subtypes");
            for (Class<?> subtype : permitted) {
                assertTrue(subtype.isRecord(),
                        subtype.getName() + " is not a record — the renderer only understands records");
            }
        }
    }

    /**
     * The discriminator is the most breaking name of all: it is written into every persisted event, so
     * a subtype missing one cannot be read back at all.
     *
     * <p>This iterates {@link #KAFKA_ROOTS} rather than skipping roots that happen to carry no
     * annotation. The earlier shape did the latter, which meant removing {@code @JsonSubTypes}
     * entirely — the exact change that breaks every topic and the event store — made this test pass
     * by finding nothing to check. A guard that cannot fail is worse than no guard, because it
     * reads like coverage.
     */
    @Test
    void everyKafkaSubtypeHasAJsonDiscriminator() {
        for (Class<?> root : KAFKA_ROOTS) {
            Map<String, String> names = discriminators(root);
            assertFalse(names.isEmpty(), root.getSimpleName() + " carries no @JsonSubTypes at all — "
                    + "every record of this hierarchy would go on the wire without its discriminator "
                    + "and could not be read back");
            for (Class<?> subtype : root.getPermittedSubclasses()) {
                assertTrue(names.containsKey(subtype.getName()),
                        subtype.getSimpleName() + " has no @JsonSubTypes entry on " + root.getSimpleName());
            }
        }
    }

    private static String renderSchema() {
        List<String> lines = new ArrayList<>();
        for (Class<?> root : ROOTS) {
            Map<String, String> names = discriminators(root);
            List<String> subtypes = new ArrayList<>();
            for (Class<?> subtype : root.getPermittedSubclasses()) {
                // No @JsonSubTypes means the simple name IS the identity — how EventTypes keys the
                // event store. Either way the snapshot records the name persisted alongside the data.
                subtypes.add(render(subtype, names.getOrDefault(subtype.getName(), subtype.getSimpleName())));
            }
            // Sorted, so the snapshot does not churn when a subtype is declared in a different place.
            subtypes.sort(String::compareTo);
            lines.add("# " + root.getSimpleName());
            lines.addAll(subtypes);
            lines.add("");
        }
        return String.join("\n", lines);
    }

    /**
     * Component ORDER is preserved, not sorted: it is part of the record's canonical constructor, and
     * swapping two same-typed components is a silent wire break that a sorted list would hide.
     */
    private static String render(Class<?> subtype, String discriminator) {
        String components = java.util.Arrays.stream(subtype.getRecordComponents())
                .map(ContractSchemaSnapshotTest::render)
                .collect(Collectors.joining(", "));
        return discriminator + "(" + components + ")";
    }

    private static String render(RecordComponent component) {
        return component.getName() + ": " + component.getGenericType().getTypeName();
    }

    private static Map<String, String> discriminators(Class<?> root) {
        Map<String, String> names = new HashMap<>();
        JsonSubTypes annotation = root.getAnnotation(JsonSubTypes.class);
        if (annotation == null) {
            return names;
        }
        for (JsonSubTypes.Type type : annotation.value()) {
            names.put(type.value().getName(), type.name());
        }
        return names;
    }

    private static String readGolden() throws IOException {
        try (InputStream in = ContractSchemaSnapshotTest.class.getResourceAsStream(GOLDEN)) {
            assertNotNull(in, "missing golden file src/test/resources" + GOLDEN);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
