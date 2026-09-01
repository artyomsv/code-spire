package dev.codespire.orchestrator.factory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.MachineAccountCredential;
import dev.codespire.contract.command.RunCommand;
import dev.codespire.encryption.EncryptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Wraps the two credentials a run command carries, so what rides {@code cs.run-commands} — and
 * what a failed delivery leaves in {@code dlq_entry.payload}, unencrypted and served by
 * {@code GET /api/dlq} — is Tink ciphertext rather than a machine account's push token and a model
 * key in the clear. The same keyset and the same shape as {@code WorkerCredentials.pack} on the
 * review path (ADR-015); the AAD binds each ciphertext to its run and its purpose, so a value cannot
 * be replayed under another run id or swapped between the two slots.
 */
@ApplicationScoped
public class RunCredentials {

    @Inject
    EncryptionService encryption;

    @Inject
    ObjectMapper mapper;

    /** The machine account's login rides inside the envelope with its token; the worker never guesses it. */
    public String packScm(String runId, String username, String secret) {
        try {
            String json = mapper.writeValueAsString(new MachineAccountCredential(username, secret));
            return encryption.encryptString(json, RunCommand.scmCredentialAad(runId));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to pack the machine-account credential for " + runId, e);
        }
    }

    /** Null in, null out: a harness with no credential carries none, and the worker treats null as absent. */
    public String packHarness(String runId, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        return encryption.encryptString(apiKey, RunCommand.harnessCredentialAad(runId));
    }
}
