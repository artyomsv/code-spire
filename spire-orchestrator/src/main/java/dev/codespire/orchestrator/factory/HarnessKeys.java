package dev.codespire.orchestrator.factory;

import dev.codespire.contract.llm.HarnessPolicy;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Which API key pays a harness (harness reviews 3b). A harness is paid only with a key of its own vendor
 * ({@link HarnessPolicy#keyKind}, matched to {@code harness_credential.type}), because a key of another vendor is
 * refused by the harness's endpoint and would be marked dead for it. So an empty pool is per key kind: keys of
 * another vendor may exist, and the refusal names the kind it looked for.
 */
final class HarnessKeys {

    private HarnessKeys() {
    }

    /** Selects from the pool a key of the harness's own vendor. */
    static HarnessCredentialPool.Selection select(HarnessCredentialPool pool, String harness) {
        return pool.select(HarnessPolicy.keyKind(harness));
    }

    /**
     * Runs a statement whose two parameters are the key kind ({@code CAST(? AS VARCHAR) IS NULL OR type = ...}).
     * Empty binds null, which matches every API key, as before key kinds were read.
     */
    static ResultSet queryOfKind(PreparedStatement ps, Optional<String> keyKind) throws SQLException {
        String kind = keyKind.orElse(null);
        ps.setString(1, kind);
        ps.setString(2, kind);
        return ps.executeQuery();
    }

    /**
     * Why an empty pool has no key for this harness, as a clause: lower case, no full stop.
     *
     * @param run what was to call the model, such as {@code "a fix run"}
     */
    static String noKey(String harness, String run) {
        return HarnessPolicy.keyKind(harness)
                .map(kind -> "no usable `" + kind + "` API key is configured for the " + harness
                        + " harness, so there is no key for " + run + " to call the model with; a key of"
                        + " another vendor does not pay it")
                .orElse("no harness credential is configured, so there is no key for " + run
                        + " to call the model with");
    }

    /** {@link #noKey} as a sentence: capitalised, with a full stop. */
    static String noKeySentence(String harness, String run) {
        String clause = noKey(harness, run);
        return Character.toUpperCase(clause.charAt(0)) + clause.substring(1) + ".";
    }
}
