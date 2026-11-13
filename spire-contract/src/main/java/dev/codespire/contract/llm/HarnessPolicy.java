package dev.codespire.contract.llm;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What the harnesses it lists may be paid with and used for: which key vendor pays each one, whether a subscription
 * seat may, and whether it may build (harness reviews 3b).
 *
 * <p>It restricts listed harnesses only. An unlisted name keeps the behaviour it had before this class (any key kind,
 * subscription and builds allowed), and that is safe: a name with no adapter is refused by the worker
 * ({@code HarnessRegistry.forName}, {@code BAD_COMMAND}) before any container starts.
 *
 * <p>It lives in the contract, like {@link HarnessTokenReport}, because the orchestrator decides payment and has no
 * dependency on the harness tier, so it cannot ask an adapter.
 */
public final class HarnessPolicy {

    /** The vendor of the API key ({@code harness_credential.type}) that pays each listed harness. */
    private static final Map<String, String> KEY_KIND = Map.of(
            "codex", "openai",
            "claude_code", "anthropic");

    // NOT ALLOWED — kept for history only. Anthropic's terms forbid a third-party product to route requests
    // through a Claude subscription or to hold claude.ai tokens:
    // https://code.claude.com/docs/en/legal-and-compliance (read 2026-11-08). Claude Code pays with an API key.
    private static final Set<String> NO_SUBSCRIPTION = Set.of("claude_code");

    /** Claude Code reviews only; building with it is not offered yet. */
    private static final Set<String> NO_BUILDS = Set.of("claude_code");

    /** The refusal every door gives a subscription for a harness that may not use one. */
    public static final String SUBSCRIPTION_NOT_ALLOWED = "subscription_not_allowed";

    /** The refusal every build, fix and factory dispatch gives a harness that may only review. */
    public static final String HARNESS_REVIEWS_ONLY = "harness_reviews_only";

    private HarnessPolicy() {}

    /** The key vendor that pays this harness, or empty when this class does not constrain it. */
    public static Optional<String> keyKind(String harness) {
        return Optional.ofNullable(harness).map(HarnessPolicy::normalised).map(KEY_KIND::get);
    }

    /** Whether a subscription seat may pay this harness. True for an unlisted name. */
    public static boolean allowsSubscription(String harness) {
        return harness == null || !NO_SUBSCRIPTION.contains(normalised(harness));
    }

    /** Whether this harness may run a build. True for an unlisted name. */
    public static boolean allowsBuilds(String harness) {
        return harness == null || !NO_BUILDS.contains(normalised(harness));
    }

    /**
     * The name as the worker resolves it: {@code HarnessRegistry.forName} ignores case, so an agent-image key spelled
     * {@code Claude_Code} runs Claude Code and must meet the same rules as {@code claude_code}.
     */
    private static String normalised(String harness) {
        return harness.trim().toLowerCase(Locale.ROOT);
    }
}
