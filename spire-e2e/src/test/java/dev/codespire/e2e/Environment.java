package dev.codespire.e2e;

import dev.codespire.e2e.gitlab.GitLabDriver;
import dev.codespire.e2e.spire.SpireDriver;
import dev.codespire.e2e.support.Stack;

import java.util.List;
import java.util.Map;

/**
 * The setup phase, and itself a test: if provider or webhook registration regresses, nothing
 * downstream can start, and this is where that shows rather than as a scenario timing out.
 *
 * <p>Ordered, and each step asserts its own result. GitLab's outbound guard in particular fails
 * silently from our side — the symptom is the bot going quiet, indistinguishable from a legitimate
 * policy decline, because nothing reaches us to log.
 */
public final class Environment {

    public static final String BOT_USERNAME = "e2e-bot";

    public static final String HUMAN_USERNAME = "e2e-human";

    /** Fixed and obviously synthetic, so a run is reproducible and nothing is scraped from a log. */
    private static final String BOT_TOKEN = "TEST-e2e-bot-token-00000000000000";

    private static final String HUMAN_TOKEN = "TEST-e2e-human-token-000000000000";

    private static final String SCM_PROVIDER = "e2e-gitlab";

    private static final String LLM_PROVIDER = "e2e-llm-mock";

    private static final String MODEL = "e2e-mock-model";

    private final String workspace;

    private final String slug;

    private final long projectId;

    private final String webhookKey;

    private final GitLabDriver bot;

    private final GitLabDriver human;

    private final SpireDriver spire;

    private Environment(String workspace, String slug, long projectId, String webhookKey,
                        GitLabDriver bot, GitLabDriver human, SpireDriver spire) {
        this.workspace = workspace;
        this.slug = slug;
        this.projectId = projectId;
        this.webhookKey = webhookKey;
        this.bot = bot;
        this.human = human;
        this.spire = spire;
    }

    public static Environment provision(String projectPrefix) {
        Stack.requireUp();

        // One Rails call for every account and token. Two users, and therefore two tokens: the
        // self-loop guard means the bot must not answer its own comments, so a one-user setup makes
        // every conversation scenario assert nothing.
        Map<String, Long> ids = GitLabDriver.bootstrap(
                List.of(GitLabDriver.ADMIN_USERNAME),
                List.of(BOT_USERNAME, HUMAN_USERNAME),
                Map.of(GitLabDriver.ADMIN_USERNAME, GitLabDriver.ADMIN_TOKEN,
                        BOT_USERNAME, BOT_TOKEN,
                        HUMAN_USERNAME, HUMAN_TOKEN));

        GitLabDriver admin = GitLabDriver.asAdmin();
        allowLocalWebhooks(admin);

        // Also cleans up after runs that crashed before their own cleanup.
        admin.deleteProjectsNamed(projectPrefix);

        String slug = projectPrefix + "-" + System.currentTimeMillis();
        long projectId = admin.createProject(slug);
        admin.addMember(projectId, ids.get(BOT_USERNAME));
        admin.addMember(projectId, ids.get(HUMAN_USERNAME));
        String workspace = admin.get("/projects/" + projectId).get("namespace").get("path").asText();

        SpireDriver spire = new SpireDriver();
        // Idempotent: a second run against the same stack would otherwise hit the registry's unique
        // constraint, which surfaces as a bare 500 rather than a 409.
        spire.resetRegistries(SCM_PROVIDER, LLM_PROVIDER, MODEL, workspace + "/" + slug);

        spire.registerScmProvider(SCM_PROVIDER, "http://gitlab/api/v4", workspace, BOT_TOKEN);
        // Catalogued BEFORE the provider names it: ADR-023's guard refuses a provider whose model is
        // not priceable, so the reverse order fails at registration.
        spire.catalogueUnmeteredModel(MODEL, "E2E mock model");
        spire.registerLlmProvider(LLM_PROVIDER, "http://llm-mock:8080/v1", MODEL);

        SpireDriver.Webhook hook = spire.registerWebhook("gitlab", workspace + "/" + slug);
        // The service name and CONTAINER port, not the published host port: GitLab reaches the
        // dashboard's nginx across the compose network, and nginx is what routes /webhooks.
        admin.createWebhook(projectId, "http://ui:8080/webhooks/gitlab/" + hook.key(), hook.secret());

        spire.setReviewMode("active");

        return new Environment(workspace, slug, projectId, hook.key(),
                GitLabDriver.as(BOT_TOKEN), GitLabDriver.as(HUMAN_TOKEN), spire);
    }

    /**
     * Asserted, not assumed. GitLab refuses webhook deliveries to private networks by default, and a
     * setting that did not take produces no error anywhere on our side.
     */
    private static void allowLocalWebhooks(GitLabDriver admin) {
        admin.allowLocalWebhooks();
        boolean allowed = admin.get("/application/settings")
                .get("allow_local_requests_from_web_hooks_and_services").asBoolean();
        if (!allowed) {
            throw new IllegalStateException("GitLab still refuses private-network webhook targets, so "
                    + "every delivery would be dropped at its end with no trace on ours.");
        }
    }

    public String workspace() {
        return workspace;
    }

    public String slug() {
        return slug;
    }

    public long projectId() {
        return projectId;
    }

    public String webhookKey() {
        return webhookKey;
    }

    public GitLabDriver bot() {
        return bot;
    }

    public GitLabDriver human() {
        return human;
    }

    public SpireDriver spire() {
        return spire;
    }

    /** The id ReviewIds.reviewId builds, which every read model row is keyed by. */
    public String reviewId(long mrIid) {
        return "review::" + workspace + "/" + slug + "#" + mrIid;
    }
}
