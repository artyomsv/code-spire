package dev.codespire.runtime.k8s;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Legal Kubernetes object names and labels for one run unit. A run id holds colons and slashes and can be long,
 * so every name carries a 16-hex digest of it instead. The full run id appears only in an annotation.
 */
final class UnitNames {

    static final String UNIT_LABEL = "dev.codespire/unit";
    static final String ROLE_LABEL = "dev.codespire/role";
    static final String RUN_ID_ANNOTATION = "dev.codespire/run-id";

    /** Every environment Secret a unit can have, in a fixed order. */
    static final List<String> ENVIRONMENT_ROLES =
            List.of("fetch", "agent", "publisher", "model", "egress", "fetchproxy");

    private UnitNames() {
    }

    static String digest(String runId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(runId.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always present", e);
        }
    }

    static String anchor(String runId) {
        return "unit-" + digest(runId);
    }

    static String unitPod(String runId) {
        return anchor(runId) + "-unit";
    }

    static String proxyPod(String runId) {
        return anchor(runId) + "-proxy";
    }

    static String unitPolicy(String runId) {
        return anchor(runId) + "-unit-policy";
    }

    static String proxyPolicy(String runId) {
        return anchor(runId) + "-proxy-policy";
    }

    static String environmentSecret(String runId, String role) {
        if (!ENVIRONMENT_ROLES.contains(role)) {
            throw new IllegalArgumentException("not an environment role: " + role);
        }
        return anchor(runId) + "-env-" + role;
    }

    static String inputsSecret(String runId) {
        return anchor(runId) + "-inputs";
    }

    static List<String> secretNames(String runId) {
        List<String> names = new ArrayList<>();
        for (String role : ENVIRONMENT_ROLES) {
            names.add(environmentSecret(runId, role));
        }
        names.add(inputsSecret(runId));
        return List.copyOf(names);
    }

    static Map<String, String> labels(String runId, String role) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(UNIT_LABEL, digest(runId));
        labels.put(ROLE_LABEL, role);
        return Map.copyOf(labels);
    }
}
