package dev.codespire.orchestrator.factory;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunIds;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.caps.SpendGate;
import dev.codespire.orchestrator.llm.LlmProviderConfig;
import dev.codespire.orchestrator.llm.LlmProviderRegistry;
import dev.codespire.orchestrator.provider.ScmProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Dispatches a factory run.
 *
 * <p>Admin only: a run spends money, and that is the first of ADR-022's three rules — the same rule
 * that makes re-run and DLQ replay admin-only.
 *
 * <p>Never falls back to the reviewer's credential. With no FACTORY-role registration for the
 * workspace this answers 409 naming what is missing, because the alternative is a branch pushed as
 * the review bot — whose pull requests the reviewer's own author allowlist then skips, so the run
 * would produce work nobody reviews and nobody is told about.
 *
 * <p>Order matters in {@link #dispatch}: the row is written LAST, after everything the command needs
 * has been built. A throw between the row and the dispatch would leave a queued row that nothing
 * re-arms — and the 409 on an existing row would then refuse that subject for ever.
 */
@Path("/api/runs")
@RolesAllowed("spire-admin")
@Produces(MediaType.APPLICATION_JSON)
public class RunResource {

    private static final Logger LOG = Logger.getLogger(RunResource.class);

    /** M0: one attempt per subject. A re-run is a later milestone's decision, not a default. */
    private static final int FIRST_ATTEMPT = 1;

    /** Stored on the row, which a viewer reads; the broker's own exception text goes to the log. */
    static final String DISPATCH_FAILED_DETAIL = "the broker did not acknowledge the command; retry the same request";

    @Inject
    MachineAccounts machineAccounts;

    @Inject
    FactoryRunProjection projection;

    @Inject
    RunCommandEmitter emitter;

    @Inject
    FactoryConfig config;

    @Inject
    LlmProviderRegistry llmProviders;

    @Inject
    SpendGate spendGate;

    @Inject
    RunCredentials runCredentials;

    /**
     * @param llmProviderId the registered LLM provider whose key the harness runs with; blank means
     *                      the deployment's default. The key itself never travels in a request.
     */
    public record DispatchRequest(String workspace, String slug, String providerType, String baseBranch,
                                  String baseCommit, String prompt, String harness, String model,
                                  String subject, String llmProviderId) {
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response dispatch(DispatchRequest req) {
        DispatchRequestParser.Parsed in = DispatchRequestParser.parse(req, config);
        ScmProvider account = machineAccount(in);
        LlmProviderConfig llm = harnessCredentialSource(req.llmProviderId(), in.harness());
        refuseOverTheSpendCap();

        RepoRef repo = new RepoRef(in.workspace(), in.slug());
        String runId = RunIds.of(in.scmType(), in.workspace(), in.slug(), in.subject(), FIRST_ATTEMPT);
        String branch = "spire/" + in.subject();
        // Both credentials ride the bus as Tink ciphertext bound to this run (ADR-015's rule, which
        // the review path already keeps); a dead-lettered command lands in dlq_entry.payload as sent.
        RunCommand.ExecuteRun command = new RunCommand.ExecuteRun(runId, repo,
                FactoryCloneUrls.cloneUrl(in.scmType(), account.baseUrl(), repo),
                in.baseBranch(), in.baseCommit(), branch, in.prompt(), in.harness(), in.model(), in.agentImage(),
                List.of(), config.wallClockSeconds(),
                runCredentials.packScm(runId, account.botUsername(), account.secret()),
                runCredentials.packHarness(runId, llm.apiKey()));

        // Recorded BEFORE dispatch, so a run can never exist on the bus without a row. A retried
        // request for a run that failed to dispatch re-arms it; any other existing row is a run
        // that is queued, running or already finished, and dispatching over it would be dropped by
        // the worker's claim as a redelivery — a 201 for a run that never runs. So: 409, naming it.
        if (!projection.queued(new FactoryRunProjection.QueuedRun(runId, in.harness(), in.model(),
                in.baseBranch(), in.baseCommit(), branch, account.botUsername()))) {
            String status = projection.find(runId).map(FactoryRunProjection.RunView::status).orElse("unknown");
            throw conflict("Run " + runId + " already exists (status " + status + "). M0 runs each subject "
                    + "once; pass a different subject to run again.");
        }
        dispatch(runId, command);
        return Response.status(Response.Status.CREATED).entity(Map.of("runId", runId)).build();
    }

    /**
     * The FACTORY-role account for the workspace, with a resolved login. The login is what the forge
     * authenticates the push as and what every commit is authored by; the registry stores a blank
     * one as null, and packing a null login was a 500 AFTER the row existed — a subject burned.
     */
    private ScmProvider machineAccount(DispatchRequestParser.Parsed in) {
        ScmProvider account = machineAccounts.resolve(in.scmType(), in.workspace())
                .orElseThrow(() -> conflict("No FACTORY-role provider is registered for "
                        + in.scmType().providerType() + "/" + in.workspace() + ". Register the machine "
                        + "account under Settings -> Providers with role FACTORY (ADR-037). "
                        + "The factory never pushes as the review bot."));
        if (account.botUsername() == null || account.botUsername().isBlank()) {
            throw conflict("The FACTORY-role provider for " + in.scmType().providerType() + "/" + in.workspace()
                    + " has no resolved login. Re-save it with a token the forge can identify, or set the "
                    + "bot username by hand: the login is what the push is authenticated as.");
        }
        return account;
    }

    /**
     * The deployment-wide caps (ADR-025) apply to a run as to a review: it is a paid model call, and
     * V40 makes its spend count toward the same rolling window. Checked BEFORE the row is written —
     * a refused run is not a run. A ledger the gate cannot read fails open, as it does for reviews.
     */
    private void refuseOverTheSpendCap() {
        SpendGate.Decision cap = spendGate.decide();
        if (cap.refused()) {
            throw new ClientErrorException(Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .entity("Run not dispatched: " + cap.refusal().detail()
                            + ". Capacity returns as older usage ages out, or raise the cap in Settings -> General.")
                    .build());
        }
    }

    private void dispatch(String runId, RunCommand.ExecuteRun command) {
        try {
            emitter.dispatch(command);
        } catch (IllegalStateException e) {
            // The row stays and says why. Deleting it would leave a run the broker may well have
            // accepted (an ack timeout proves nothing) with no record; the retry re-arms this row.
            LOG.errorf(e, "run %s was recorded but the broker did not acknowledge its dispatch", runId);
            projection.dispatchFailed(runId, DISPATCH_FAILED_DETAIL);
            throw new ServerErrorException(Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("Run " + runId + " was recorded but not dispatched: " + e.getMessage()
                            + ". Retry the same request once the broker is reachable; it re-arms this run.")
                    .build(), e);
        }
    }

    /**
     * The harness runs with a key from the LLM provider registry — the named provider, else the
     * deployment's default — because that registry is where operator keys live, encrypted, and a
     * request body is not. 409 when neither exists: dispatching anyway would start and pay for a
     * container that fails at its first model call. Whether the key suits the arm (an OpenAI key
     * for codex) is the operator's to know at M0; the message says so.
     */
    private LlmProviderConfig harnessCredentialSource(String llmProviderId, String harness) {
        Optional<LlmProviderConfig> found;
        if (llmProviderId == null || llmProviderId.isBlank()) {
            found = llmProviders.resolveDefault();
        } else {
            UUID id;
            try {
                id = UUID.fromString(llmProviderId);
            } catch (IllegalArgumentException e) {
                throw DispatchRequestParser.badRequest("llmProviderId must be a UUID");
            }
            found = llmProviders.resolveById(id);
        }
        return found.orElseThrow(() -> conflict("No LLM provider supplies the harness credential: set a "
                + "default LLM provider under Settings -> LLM, or pass llmProviderId. Its key must be "
                + "one the '" + harness + "' harness accepts."));
    }

    private static ClientErrorException conflict(String message) {
        return new ClientErrorException(
                Response.status(Response.Status.CONFLICT).entity(message).build());
    }

    /** Clears the run's attention row (a gate refusal). Both roles: reading a refusal spends nothing. */
    @POST
    @Path("/{runId:.+}/attention-ack")
    @RolesAllowed({"spire-viewer", "spire-admin"})
    public Response acknowledgeAttention(@PathParam("runId") String runId) {
        if (!projection.acknowledgeAttention(runId)) {
            throw new NotFoundException("no such run: " + runId);
        }
        return Response.noContent().build();
    }

    @GET
    // A run id embeds the repository (`run::github:acme/app:subject:1`) and a GitLab workspace can
    // itself be `group/subgroup`, so the id spans several path segments; the regex keeps them all.
    @Path("/{runId:.+}")
    @RolesAllowed({"spire-viewer", "spire-admin"})
    public FactoryRunProjection.RunView get(@PathParam("runId") String runId) {
        return projection.find(runId).orElseThrow(() -> new NotFoundException("no such run: " + runId));
    }
}
