package dev.codespire.scm.bitbucket;

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
 * Bitbucket Cloud's half of the operator sign-in (FR-11).
 *
 * <p>Two differences from the other two adapters, both required by the platform. The client
 * credentials go in an HTTP Basic header rather than the form body — Bitbucket refuses them in the
 * body. And the consumer's callback is configured on the consumer itself, so {@code redirect_uri} is
 * sent for the exchange but the authorize URL relies on what the consumer already holds.
 *
 * <p>Identity comes from {@link BitbucketCloudDiffSource#whoami()}, so the stored id is the
 * {@code account_id} the ingress records — the long {@code 557058:...} form, which no operator could
 * reasonably have typed and which is the whole reason this flow exists.
 */
public class BitbucketOperatorOAuth implements OperatorOAuth {

    static final String HOSTED_WEB = "https://bitbucket.org";
    /** With the version segment: the client appends a path to this, exactly as the registry base does. */
    static final String HOSTED_API = "https://api.bitbucket.org/2.0";

    private final ObjectMapper mapper;
    private final FormTokenClient tokens;

    public BitbucketOperatorOAuth(ObjectMapper mapper) {
        this.mapper = mapper;
        this.tokens = new FormTokenClient(mapper);
    }

    @Override
    public ScmType type() {
        return ScmType.BITBUCKET_CLOUD;
    }

    @Override
    public String authorizeUrl(OAuthApp app, String state, String redirectUri) {
        return app.webBaseOr(HOSTED_WEB) + "/site/oauth2/authorize"
                + "?client_id=" + enc(app.clientId())
                + "&response_type=code"
                + "&state=" + enc(state);
    }

    @Override
    public Author identify(OAuthApp app, String code, String redirectUri) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);

        String token = tokens.accessToken(app.webBaseOr(HOSTED_WEB) + "/site/oauth2/access_token",
                form, app.clientId() + ":" + app.clientSecret(), BitbucketApiException::new);
        BitbucketCloudConfig config =
                new BitbucketCloudConfig(app.apiBaseOr(HOSTED_API), null, null, token, "unused-read-only");
        return new BitbucketCloudDiffSource(new BitbucketCloudClient(config, mapper)).whoami();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
