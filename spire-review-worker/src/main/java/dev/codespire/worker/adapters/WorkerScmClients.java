package dev.codespire.worker.adapters;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.scm.ScmCredential;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.scm.bitbucket.BitbucketCloudClient;
import dev.codespire.scm.bitbucket.BitbucketCloudCommentSink;
import dev.codespire.scm.bitbucket.BitbucketCloudConfig;
import dev.codespire.scm.bitbucket.BitbucketCloudDiffSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Per-command SCM client factory (ADR-015). The credential the worker needs is
 * NOT read from .env — it rides each command as opaque, KEK-encrypted ciphertext
 * ({@code ActionCommand.scmCredential}) that the orchestrator packed from the
 * provider registry. The worker (a KEK holder in active mode) decrypts it and
 * builds a per-command Bitbucket client, so one worker serves many workspaces.
 *
 * <p>{@code spire.scm.provider=stub} forces the stub adapters for the local
 * end-to-end demo (SMOKE-TEST Mode A), ignoring any command credential. A
 * missing credential in bitbucket-cloud mode also falls back to stub — a safety
 * net; the orchestrator never emits an uncredentialed command in active mode.
 */
@ApplicationScoped
public class WorkerScmClients {

    /** DiffSource + CommentSink built for a single command. */
    public record Clients(DiffSource diff, CommentSink comments) {
    }

    @ConfigProperty(name = "spire.scm.provider")
    String providerMode; // stub | bitbucket-cloud

    @Inject
    EncryptionService encryption;

    @Inject
    ObjectMapper mapper;

    private final Clients stub = new Clients(new StubScm.StubDiffSource(), new StubScm.LoggingCommentSink());

    public Clients forCommand(ActionCommand command) {
        if ("stub".equals(providerMode)) {
            return stub;
        }
        ScmCredential cred = unpack(command);
        if (cred == null) {
            return stub; // no credential — active mode never emits these; safe fallback
        }
        BitbucketCloudClient client = new BitbucketCloudClient(configOf(cred), mapper);
        return new Clients(new BitbucketCloudDiffSource(client), new BitbucketCloudCommentSink(client));
    }

    private ScmCredential unpack(ActionCommand command) {
        String cipher = command.scmCredential();
        if (cipher == null || cipher.isBlank()) {
            return null;
        }
        String workspace = ReviewIds.parse(command.reviewId()).repo().workspace();
        try {
            return mapper.readValue(encryption.decryptString(cipher, ScmCredential.aad(workspace)), ScmCredential.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to unpack worker credential for " + workspace, e);
        }
    }

    private static BitbucketCloudConfig configOf(ScmCredential cred) {
        // The worker holds no webhook secret — a placeholder satisfies the config
        // invariant (least privilege). Bearer when authKind=bearer, else Basic.
        String botAccountId = cred.botAccountId() == null || cred.botAccountId().isBlank()
                ? "unset" : cred.botAccountId();
        return "bearer".equalsIgnoreCase(cred.authKind())
                ? new BitbucketCloudConfig(cred.baseUrl(), null, null, cred.secret(), "unused-by-worker", botAccountId)
                : new BitbucketCloudConfig(cred.baseUrl(), cred.username(), cred.secret(), "unused-by-worker", botAccountId);
    }
}
