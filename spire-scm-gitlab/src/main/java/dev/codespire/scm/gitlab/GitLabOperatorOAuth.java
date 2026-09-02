package dev.codespire.scm.gitlab;

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
 * GitLab's half of the operator sign-in (FR-11).
 *
 * <p>One host serves both sign-in and API, so a self-hosted instance needs only the web base set and
 * the API base is taken from it. Identity comes from {@link GitLabDiffSource#whoami()} — the same
 * numeric id the ingress records as a merge request's author.
 */
public class GitLabOperatorOAuth implements OperatorOAuth {

    static final String HOSTED = "https://gitlab.com";
    /** The account's own profile only; {@code api} or {@code read_repository} are never requested. */
    private static final String SCOPE = "read_user";

    private final ObjectMapper mapper;
    private final FormTokenClient tokens;

    public GitLabOperatorOAuth(ObjectMapper mapper) {
        this.mapper = mapper;
        this.tokens = new FormTokenClient(mapper);
    }

    @Override
    public ScmType type() {
        return ScmType.GITLAB;
    }

    @Override
    public String authorizeUrl(OAuthApp app, String state, String redirectUri) {
        return webBase(app) + "/oauth/authorize"
                + "?client_id=" + enc(app.clientId())
                + "&redirect_uri=" + enc(redirectUri)
                + "&response_type=code"
                + "&scope=" + enc(SCOPE)
                + "&state=" + enc(state);
    }

    @Override
    public Author identify(OAuthApp app, String code, String redirectUri) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", app.clientId());
        form.put("client_secret", app.clientSecret());
        form.put("code", code);
        form.put("grant_type", "authorization_code");
        form.put("redirect_uri", redirectUri);

        String token = tokens.accessToken(webBase(app) + "/oauth/token", form, null, GitLabApiException::new);
        return new GitLabDiffSource(new GitLabClient(new GitLabConfig(apiBase(app), token), mapper)).whoami();
    }

    /** Where the sign-in and token endpoints live: the instance root, with no API prefix. */
    private static String webBase(OAuthApp app) {
        return app.webBaseOr(HOSTED);
    }

    /**
     * Where {@code /user} lives, which is the instance root plus {@code /api/v4}.
     *
     * <p>The two bases share a HOST but not a path, so an operator who fills in only the sign-in URL
     * still needs the prefix appended. Deriving it from the web base rather than from {@link #HOSTED}
     * is the part that matters: a self-hosted operator who left the API field blank would otherwise
     * be identified against gitlab.com, and the sign-in itself would have succeeded — so they would
     * be linked to whoever holds that name there, with nothing on screen looking wrong.
     */
    private static String apiBase(OAuthApp app) {
        return app.apiBaseOr(webBase(app) + "/api/v4");
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
