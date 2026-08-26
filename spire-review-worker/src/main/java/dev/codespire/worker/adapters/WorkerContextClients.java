package dev.codespire.worker.adapters;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.ActionCommand.GatherContext;
import dev.codespire.contract.context.ContextCredential;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.port.ContextProvider;
import dev.codespire.context.code.BitbucketSourceFileReader;
import dev.codespire.context.code.CodeContextConfig;
import dev.codespire.context.code.CodeContextProvider;
import dev.codespire.context.code.GitHubSourceFileReader;
import dev.codespire.context.code.GitLabSourceFileReader;
import dev.codespire.context.code.SourceFileReader;
import dev.codespire.context.confluence.ConfluenceConfig;
import dev.codespire.context.confluence.ConfluenceContextProvider;
import dev.codespire.context.confluence.ConfluenceLinks;
import dev.codespire.context.github.GitHubIssueConfig;
import dev.codespire.context.github.GitHubIssueContextProvider;
import dev.codespire.context.github.GitHubIssueRefs;
import dev.codespire.context.gitlab.GitLabIssueConfig;
import dev.codespire.context.gitlab.GitLabIssueContextProvider;
import dev.codespire.context.gitlab.GitLabIssueRefs;
import dev.codespire.context.jira.JiraConfig;
import dev.codespire.context.jira.JiraContextProvider;
import dev.codespire.context.jira.JiraTicketKeys;
import dev.codespire.encryption.EncryptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Per-command context-provider factory — the context analog of
 * {@link WorkerScmClients}. The credential rides each {@code GatherContext} as
 * opaque, KEK-encrypted ciphertext ({@code ActionCommand.contextCredential}) that
 * the orchestrator packed from the context-provider registry; the worker decrypts
 * it and builds a per-command provider, so one worker serves many workspaces.
 *
 * <p>Unlike SCM, context is OPTIONAL: no credential means no external source
 * configured, so this returns an empty provider list and the aggregator assembles
 * an empty context (the review still runs, just without ticket context). The
 * command carries EVERY enabled provider's credential, so the aggregator can match
 * a PR's references against all of them. Credential-less providers (repo rules,
 * RAG, memory) can later be {@code @All} CDI-injected here and merged in.
 */
@ApplicationScoped
public class WorkerContextClients {

    private static final com.fasterxml.jackson.core.type.TypeReference<List<ContextCredential>> CRED_LIST =
            new com.fasterxml.jackson.core.type.TypeReference<>() {
            };

    @Inject
    EncryptionService encryption;

    @Inject
    ObjectMapper mapper;

    /** The languages the {@code code} provider resolves imports for — see {@link WorkerCodeReferences#all()}. */
    @Inject
    WorkerCodeReferences codeReferences;

    public List<ContextProvider> forCommand(GatherContext command) {
        List<ContextProvider> providers = new java.util.ArrayList<>();
        for (ContextCredential cred : unpack(command)) {
            switch (cred.type()) {
                case "jira" -> providers.add(new JiraContextProvider(jiraConfig(cred), mapper));
                case "confluence" -> providers.add(new ConfluenceContextProvider(confluenceConfig(cred), mapper));
                case "github-issues" ->
                        providers.add(new GitHubIssueContextProvider(gitHubIssueConfig(cred), mapper));
                case "gitlab-issues" ->
                        providers.add(new GitLabIssueContextProvider(gitLabIssueConfig(cred), mapper));
                case "code" -> providers.add(new CodeContextProvider(readerFor(cred), codeReferences.all(),
                        CodeContextConfig.parsePathAllowList(cred.projectKeys())));
                default -> throw new IllegalStateException("Unsupported context provider type: " + cred.type());
            }
        }
        // Added unconditionally, outside the credential loop: repository rules need no credential and
        // no registry entry, so a team gets them without an operator configuring anything — including
        // on a deployment where no external context source is registered at all.
        providers.add(new RulesContextProvider());
        return providers;
    }

    private List<ContextCredential> unpack(GatherContext command) {
        String cipher = command.contextCredential();
        if (cipher == null || cipher.isBlank()) {
            return List.of();
        }
        String workspace = ReviewIds.parse(command.reviewId()).repo().workspace();
        try {
            return mapper.readValue(encryption.decryptString(cipher, ContextCredential.aad(workspace)), CRED_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to unpack context credentials for " + workspace, e);
        }
    }

    private static JiraConfig jiraConfig(ContextCredential cred) {
        return new JiraConfig(cred.baseUrl(), cred.authKind(), cred.username(), cred.secret(),
                JiraTicketKeys.parseProjectKeys(cred.projectKeys()));
    }

    private static ConfluenceConfig confluenceConfig(ContextCredential cred) {
        // projectKeys carries the optional space-key allow-list for Confluence (same generic registry column).
        return new ConfluenceConfig(cred.baseUrl(), cred.authKind(), cred.username(), cred.secret(),
                ConfluenceLinks.parseSpaceKeys(cred.projectKeys()));
    }

    private static GitHubIssueConfig gitHubIssueConfig(ContextCredential cred) {
        // projectKeys carries the optional owner/repo allow-list (same generic registry column).
        return new GitHubIssueConfig(cred.baseUrl(), cred.authKind(), cred.secret(),
                GitHubIssueRefs.parseRepoAllowList(cred.projectKeys()));
    }

    private static GitLabIssueConfig gitLabIssueConfig(ContextCredential cred) {
        // projectKeys carries the optional group/project allow-list (same generic registry column).
        return new GitLabIssueConfig(cred.baseUrl(), cred.authKind(), cred.secret(),
                GitLabIssueRefs.parseProjectAllowList(cred.projectKeys()));
    }

    /**
     * Picks the platform reader by the credential's {@code baseUrl} host. Unlike Jira/Confluence
     * (one platform each) or GitHub/GitLab issues (one registry type per platform), a single generic
     * {@code code} registry type covers all three raw-content APIs — task-13's Settings UI offers one
     * "Repository code" option, not three — so the host is the only signal available to tell them
     * apart; the orchestrator's {@code ContextKeyValidator} connectivity check for this same type has
     * to infer it independently, on the other side of the module boundary.
     *
     * <p>GitLab and Bitbucket both conventionally publish a host containing their own name; a
     * self-managed GitLab whose host does not (e.g. {@code git.acme.com}) falls through to the GitHub
     * reader instead, since a GitHub Enterprise Server hostname is the least predictable of the
     * three. This is a known limitation of having one generic type rather than an oversight.
     */
    private SourceFileReader readerFor(ContextCredential cred) {
        CodeContextConfig config = new CodeContextConfig(cred.baseUrl(), cred.authKind(), cred.secret(),
                CodeContextConfig.parsePathAllowList(cred.projectKeys()));
        String host = hostOf(cred.baseUrl());
        SourceFileReader reader;
        if (host.contains("gitlab")) {
            reader = new GitLabSourceFileReader(config);
        } else if (host.contains("bitbucket")) {
            reader = new BitbucketSourceFileReader(config);
        } else {
            reader = new GitHubSourceFileReader(config);
        }
        return new CircuitBreakingSourceFileReader(reader);
    }

    private static String hostOf(String baseUrl) {
        try {
            String host = URI.create(baseUrl.trim()).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
