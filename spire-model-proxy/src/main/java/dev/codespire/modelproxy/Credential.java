package dev.codespire.modelproxy;

import dev.codespire.modelproxy.ProxyRoute.Injection;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The run's real credential and how it goes on a request: a key, or a seat's access token and its account id.
 *
 * @param account the seat's account id, or null for a key
 */
record Credential(Injection injection, String secret, String account) {

    Credential {
        Objects.requireNonNull(injection, "injection");
        Objects.requireNonNull(secret, "secret");
        if (injection == Injection.CHATGPT_SEAT) {
            Objects.requireNonNull(account, "account");
        }
    }

    /** The credential the environment holds for {@code route}, both values checked for presence. */
    static Credential fromEnvironment(Map<String, String> env, ProxyRoute route) {
        String account = route.injection() == Injection.CHATGPT_SEAT
                ? ProxyRoute.required(env, ProxyRoute.ACCOUNT)
                : null;
        return new Credential(route.injection(), ProxyRoute.required(env, ProxyRoute.SECRET), account);
    }

    /** What must never travel back to the agent: the secret and, for a seat, the account id, as bytes. */
    List<byte[]> echoes() {
        List<byte[]> echoes = new ArrayList<>();
        echoes.add(secret.getBytes(StandardCharsets.UTF_8));
        if (account != null) {
            echoes.add(account.getBytes(StandardCharsets.UTF_8));
        }
        return echoes;
    }

    /** Whether {@code text} carries either value. */
    boolean appearsIn(String text) {
        return text.contains(secret) || account != null && text.contains(account);
    }

    /** Never prints a value: this record holds the run's credential. */
    @Override
    public String toString() {
        return "Credential[injection=" + injection + ", secret=***, account=***]";
    }
}
