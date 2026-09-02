package dev.codespire.scm.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.port.OperatorOAuth;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.OAuthApp;
import dev.codespire.http.FormTokenClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GitHub's half of the operator sign-in (FR-11).
 *
 * <p>The two hosted bases genuinely differ — sign-in answers on {@code github.com} and the API on
 * {@code api.github.com} — which is why {@link OAuthApp} carries both. A GitHub Enterprise install
 * serves sign-in from its own host and the API from {@code /api/v3} beneath it, and no rule derives
 * one from the other reliably, so an operator states both rather than this class guessing.
 *
 * <p>Identity comes from {@link GitHubDiffSource#whoami()} rather than a second parser here. That is
 * the method a provider registration already uses, so the id stored for an operator is the same id
 * the ingress records as a pull request's author — which is the only thing that makes the link
 * match any rows at all.
 */
public class GitHubOperatorOAuth implements OperatorOAuth {

    static final String HOSTED_WEB = "https://github.com";
    static final String HOSTED_API = "https://api.github.com";
    /** Enough to read the account's own profile, and nothing else — no repository access is asked for. */
    private static final String SCOPE = "read:user";

    private final ObjectMapper mapper;
    private final FormTokenClient tokens;

    public GitHubOperatorOAuth(ObjectMapper mapper) {
        this.mapper = mapper;
        this.tokens = new FormTokenClient(mapper);
    }

    @Override
    public ScmType type() {
        return ScmType.GITHUB;
    }

    @Override
    public String authorizeUrl(OAuthApp app, String state, String redirectUri) {
        return app.webBaseOr(HOSTED_WEB) + "/login/oauth/authorize"
                + "?client_id=" + enc(app.clientId())
                + "&redirect_uri=" + enc(redirectUri)
                + "&scope=" + enc(SCOPE)
                + "&state=" + enc(state);
    }

    @Override
    public Author identify(OAuthApp app, String code, String redirectUri) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", app.clientId());
        form.put("client_secret", app.clientSecret());
        form.put("code", code);
        form.put("redirect_uri", redirectUri);

        String token = tokens.accessToken(app.webBaseOr(HOSTED_WEB) + "/login/oauth/access_token",
                form, null, GitHubApiException::new);
        GitHubConfig config = new GitHubConfig(app.apiBaseOr(HOSTED_API), token, "unused-read-only");
        return new GitHubDiffSource(new GitHubClient(config, mapper)).whoami();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
