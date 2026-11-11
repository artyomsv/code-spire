package dev.codespire.harness.claudecode;

import dev.codespire.harness.CredentialRoute;
import dev.codespire.harness.FailureCause;
import dev.codespire.harness.HarnessAdapter;
import dev.codespire.harness.HarnessCapabilities;
import dev.codespire.harness.HarnessInvocation;
import dev.codespire.harness.HarnessType;
import dev.codespire.harness.PromptDelivery;
import dev.codespire.harness.RunEvent;
import dev.codespire.harness.RunEventSummary;
import dev.codespire.harness.TerminalOutcome;
import dev.codespire.harness.UsageReport;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Drives Anthropic's Claude Code CLI non-interactively, for review runs only (harness reviews 3b, ADR-050).
 *
 * <p>Claude Code runs only in one shape, and this adapter refuses every other before a container exists (the launch
 * reports it as {@code BAD_COMMAND}): behind the unit's model proxy, so the real Anthropic key never reaches the agent;
 * without the repository's guidance, because a review's working directory is the pull request's head; and paid with an
 * API key, never a Claude subscription. The orchestrator refuses builds, fixes and subscriptions earlier, with a named
 * reason; this is the second lock, placed where the command is built.
 *
 * <p>Every flag and every event shape was measured against Claude Code 2.1.319 on 2026-11-11 (Task 3's record).
 */
public final class ClaudeCodeAdapter implements HarnessAdapter {

    /** One-shot, newline-delimited JSON events, no permission prompts; the prompt is read from stdin. */
    static final List<String> RUN_FLAGS = List.of("-p", "--output-format", "stream-json", "--verbose",
            "--dangerously-skip-permissions");

    /**
     * On every run. The most restrictive set measured to load nothing from the reviewed branch while the review still
     * worked: with no flags the positive control fired every canary ({@code CLAUDE.md} and its {@code @import},
     * {@code CLAUDE.local.md}, {@code .claude/} rules, skills, agents, commands, settings with hooks, {@code env} and
     * {@code apiKeyHelper}, and {@code .mcp.json}), and a {@code settings.local.json} {@code env} entry redirected the
     * agent away from the model proxy; with these, none fired and the proxy saw every call. {@code --bare} is what
     * stops {@code CLAUDE.md} being found, so there is no separate memory switch.
     */
    static final List<String> GUARDED_FLAGS = List.of("--bare", "--safe-mode", "--strict-mcp-config",
            "--disable-slash-commands", "--setting-sources", "user");

    static final String PROXY_REQUIRED = "credential_proxy_required";

    static final String REVIEWS_ONLY = "harness_reviews_only";

    /** The catalogue offers this arm no thinking level, so a level is refused as the orchestrator refuses it. */
    static final String EFFORT_NOT_OFFERED = "effort_not_offered";

    @Override
    public HarnessType type() {
        return HarnessType.CLAUDE_CODE;
    }

    /** {@code claude -p} is one-shot: no session to steer or resume, and the stream is not a structured answer. */
    @Override
    public HarnessCapabilities capabilities() {
        return new HarnessCapabilities(true, true, false, false, false);
    }

    /** Stdin, so a prompt that begins with a hyphen is never read as a flag and stays out of argv. */
    @Override
    public PromptDelivery promptDelivery() {
        return PromptDelivery.STDIN;
    }

    /**
     * A shell line, as Codex's, so the workspace is the working directory; {@code exec} replaces the shell, and the two
     * interpolations are quoted and refused if they could close the quote. The prompt arrives on the harness's stdin,
     * which the entrypoint redirects into the whole command.
     */
    @Override
    public List<String> command(HarnessInvocation invocation) {
        refuseUnlessReviewShaped(invocation);
        if (invocation.reasoningEffort() != null) {
            throw new IllegalStateException(EFFORT_NOT_OFFERED);
        }
        String script = "cd " + quoted(invocation.workspacePath(), "workspace path")
                + " && exec claude " + String.join(" ", RUN_FLAGS) + " " + String.join(" ", GUARDED_FLAGS)
                + " --model " + quoted(invocation.model(), "model");
        return List.of("sh", "-c", script);
    }

    @Override
    public Map<String, String> environment(HarnessInvocation invocation) {
        refuseUnlessReviewShaped(invocation);
        return ClaudeCodeModelProxy.environment(invocation);
    }

    @Override
    public Optional<CredentialRoute> credentialRoute(HarnessInvocation invocation) {
        return ClaudeCodeModelProxy.route(invocation);
    }

    /** The model API only: measured, a guarded run called {@code POST /v1/messages} and nothing else. */
    @Override
    public Set<String> egressHosts(HarnessInvocation invocation) {
        return Set.of("api.anthropic.com");
    }

    @Override
    public Optional<RunEvent> parse(String line) {
        return parseAll(line).stream().findFirst();
    }

    @Override
    public List<RunEvent> parseAll(String line) {
        return ClaudeCodeEvents.of(line);
    }

    /**
     * The result line decides first: an error result fails as {@code PROVIDER_ERROR} whatever the exit code, since
     * one was measured with exit 1 and nothing stops another arriving with 0. With no result and no output the model
     * said nothing. Otherwise the exit code decides, as for Codex.
     */
    @Override
    public TerminalOutcome classify(int exitCode, RunEventSummary seen) {
        Optional<String> result = lastResult(seen);
        if (result.isPresent() && result.orElseThrow().startsWith(ClaudeCodeEvents.ERROR_PREFIX)) {
            return TerminalOutcome.failure(FailureCause.PROVIDER_ERROR,
                    "claude reported " + result.orElseThrow() + ", exit " + exitCode);
        }
        if (result.isEmpty() && !seen.sawAnyOutput()) {
            return TerminalOutcome.failure(FailureCause.NO_MODEL_RESPONSE, "exit " + exitCode + ", no result, no output");
        }
        if (exitCode == 0) {
            return TerminalOutcome.success("claude -p completed");
        }
        if (!seen.sawAnyOutput()) {
            return TerminalOutcome.failure(FailureCause.NO_MODEL_RESPONSE, "exit " + exitCode + ", no output");
        }
        return TerminalOutcome.failure(FailureCause.HARNESS_EXIT_NONZERO, "exit " + exitCode);
    }

    /** The result line's usage covers the whole run, so the last one is the run's; none is unknown, never zero. */
    @Override
    public UsageReport usage(RunEventSummary seen) {
        UsageReport latest = UsageReport.unknown();
        for (RunEvent event : seen.events()) {
            if (event instanceof RunEvent.Usage usage) {
                latest = usage.report();
            }
        }
        return latest;
    }

    private static Optional<String> lastResult(RunEventSummary seen) {
        String detail = null;
        for (RunEvent event : seen.events()) {
            if (event instanceof RunEvent.StateChange state && ClaudeCodeEvents.RESULT.equals(state.state())) {
                detail = state.detail();
            }
        }
        return Optional.ofNullable(detail);
    }

    /** A sign-in first, then a run whose agent would hold the key, then one that would load the repository's guidance. */
    private static void refuseUnlessReviewShaped(HarnessInvocation invocation) {
        ClaudeSubscription.refuseIfSignIn(invocation.credentials());
        if (!invocation.credentialProxied()) {
            throw new IllegalStateException(PROXY_REQUIRED);
        }
        if (invocation.projectGuidance() == HarnessInvocation.ProjectGuidance.LOADED) {
            throw new IllegalStateException(REVIEWS_ONLY);
        }
    }

    /** A value for the shell line, in single quotes, refused rather than escaped if it could close them. */
    static String quoted(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " is required");
        }
        if (value.indexOf('\'') >= 0 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(what + " must not contain a quote or a control character");
        }
        return "'" + value + "'";
    }
}
