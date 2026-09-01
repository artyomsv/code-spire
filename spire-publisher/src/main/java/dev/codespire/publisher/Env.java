package dev.codespire.publisher;

import java.util.Map;

/** Environment reads shared by the two entrypoints. No defaults for anything an operator decides. */
final class Env {

    private Env() {
    }

    /**
     * @throws IllegalStateException naming the variable — never its value, which may be a secret
     */
    static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required and was not set");
        }
        return value;
    }
}
