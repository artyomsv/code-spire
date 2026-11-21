package dev.codespire.orchestrator.attention;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A TEST {@code llm_provider} row for the attention suites, written with SQL. A default provider gets the suite's model
 * as the default review model, as the dashboard writes the two together.
 */
record TestLlmProvider(String name, String type, boolean enabled, boolean isDefault) {

    static TestLlmProvider enabledDefault(String name) {
        return new TestLlmProvider(name, "openai", true, true);
    }

    static TestLlmProvider enabledNotDefault(String name) {
        return new TestLlmProvider(name, "openai", true, false);
    }

    static TestLlmProvider disabledDefault(String name) {
        return new TestLlmProvider(name, "openai", false, true);
    }

    /** The same row, of another vendor. */
    TestLlmProvider ofType(String vendor) {
        return new TestLlmProvider(name, vendor, enabled, isDefault);
    }

    /** The INSERT of this row and, when it is the default, the upsert making {@code defaultModel} the default model. */
    List<String> statements(String defaultModel) {
        List<String> statements = new ArrayList<>();
        statements.add("INSERT INTO llm_provider (id, name, type, base_url, api_key, temperature, enabled, is_default) "
                + "VALUES ('" + UUID.randomUUID() + "', '" + name + "', '" + type + "', "
                + "'https://llm.example.invalid', 'TEST-KEY', 0.0, " + enabled + ", " + isDefault + ")");
        if (isDefault) {
            statements.add("INSERT INTO app_setting (key, value) VALUES ('llm.default-model', '" + defaultModel + "')"
                    + " ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value");
        }
        return statements;
    }
}
