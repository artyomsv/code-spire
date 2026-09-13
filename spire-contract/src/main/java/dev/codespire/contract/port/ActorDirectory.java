package dev.codespire.contract.port;

import dev.codespire.contract.scm.ResolvedActor;
import java.util.List;

/** Identity reads use exactly the configured account; no credential fallback. */
public interface ActorDirectory {
    enum Status { FOUND, SELECTION_REQUIRED, NOT_FOUND, AMBIGUOUS, UNAVAILABLE, UNSUPPORTED }
    record Result(Status status, List<ResolvedActor> actors, String detail) {
        public static Result found(ResolvedActor actor) { return new Result(Status.FOUND, List.of(actor), null); }
        public static Result failed(Status status, String detail) { return new Result(status, List.of(), detail); }
    }
    /** Exact handle on supporting forges; explicit candidates elsewhere. Scope is a repository namespace. */
    Result lookup(String handle, String scope);
    Result byId(String providerUserId);

    static String handle(String input) {
        if (input == null) return "";
        String value = input.strip();
        return value.startsWith("@") ? value.substring(1) : value;
    }

    static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Only fixed explanations cross the API boundary; adapter exceptions may contain remote bodies. */
    static Result failure(RuntimeException failure) {
        if (failure instanceof dev.codespire.contract.scm.ScmApiException api && api.status() == 404) {
            return Result.failed(Status.NOT_FOUND, "Person was not found or is not visible to this account.");
        }
        return Result.failed(Status.UNAVAILABLE, "The selected account cannot read the identity directory. Check its user-read permissions and retry.");
    }
}
