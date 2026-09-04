package dev.codespire.orchestrator.factory;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code STATUSES} lists every status, and this DERIVES the answer rather than trusting the list.
 *
 * <p>The set exists so the runs endpoint can refuse an unknown filter value. A status added to the
 * projection and forgotten here would be silently unfilterable — an operator asking for it would get
 * a 400 saying it does not exist, about a status their own rows are in.
 *
 * <p>Derived by reflection, in the shape {@code DockerTestsAreSerialisedTest} already uses in this
 * repository: it scans for the thing rather than reading a declaration that claims to describe it. A
 * test that simply asserted a count, or listed the same nine names again, would be the declaration
 * twice over and would agree with itself while both were wrong.
 */
class FactoryRunStatusesAreCompleteTest {

    /**
     * Constants that are NOT statuses, named individually so adding one is a deliberate act.
     *
     * <p>{@code DISPATCH_FAILED} is a {@code failure_cause}, not a status — it sits among the status
     * constants because it is written beside them, and reflection cannot tell the difference. This is
     * the allowlist that keeps the derivation honest; it is short and it should stay short.
     */
    private static final Set<String> NOT_A_STATUS = Set.of("DISPATCH_FAILED");

    @Test
    void everyStatusConstantIsListedAsFilterable() {
        Set<String> declared = new TreeSet<>();
        for (Field field : FactoryRunProjection.class.getDeclaredFields()) {
            if (!isStatusConstant(field)) {
                continue;
            }
            declared.add(read(field));
        }

        assertTrue(declared.size() >= 9,
                "the derivation found almost nothing, so it is measuring the wrong thing: " + declared);
        assertEquals(declared, new TreeSet<>(FactoryRunProjection.STATUSES),
                "a status the projection writes but STATUSES omits is unfilterable, and one STATUSES "
                        + "names but nothing writes is a filter that can only ever answer empty");
    }

    /**
     * A status constant is a {@code static final String} whose NAME is not on the allowlist.
     *
     * <p>Keyed on the name rather than on the value, because two constants could legitimately share a
     * value and because a value-based skip would silently start matching a future status that happened
     * to be spelled the same.
     */
    private static boolean isStatusConstant(Field field) {
        return field.getType() == String.class
                && Modifier.isStatic(field.getModifiers())
                && Modifier.isFinal(field.getModifiers())
                && !NOT_A_STATUS.contains(field.getName())
                // The projection also holds SQL and detail strings; a status is lower_snake_case and
                // those are sentences or upper-case causes.
                && field.getName().equals(field.getName().toUpperCase(java.util.Locale.ROOT))
                && isLowerSnakeCase(read(field));
    }

    private static boolean isLowerSnakeCase(String value) {
        return value != null && !value.isBlank() && value.matches("[a-z][a-z_]*");
    }

    private static String read(Field field) {
        try {
            field.setAccessible(true);
            return (String) field.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("could not read " + field.getName(), e);
        }
    }
}
