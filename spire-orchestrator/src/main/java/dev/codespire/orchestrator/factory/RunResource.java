package dev.codespire.orchestrator.factory;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunIds;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.caps.SpendGate;
import dev.codespire.orchestrator.llm.LlmProviderConfig;
import dev.codespire.orchestrator.llm.LlmProviderRegistry;
import dev.codespire.orchestrator.provider.ScmProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
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
import java.util.regex.Pattern;

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
 */
@Path("/api/runs")
@RolesAllowed("spire-admin")
@Produces(MediaType.APPLICATION_JSON)
public class RunResource {

    /** A single path segment's charset — the same guard the manual-register endpoint applies. */
    private static final Pattern SLUG = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?");

    /**
     * A FULL object id, not an abbreviation. The publisher hands this to JGit's ObjectId.fromString,
     * which accepts exactly 40 hex characters; a 7-character prefix passed every check here, was
     * cloned and run against, and then failed every bundle as BUNDLE_UNREADABLE — after the agent
     * had been paid for. SHA-256 repositories (64) are not what the publisher's git library speaks.
     */
    private static final Pattern COMMIT = Pattern.compile("[0-9a-fA-F]{40}");

    /** A model name as the vendors spell them ({@code gpt-5.6}, {@code claude-opus-5}, {@code org/model:tag}). */
    private static final Pattern MODEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");

    /** Generous for a work item; a bound at all is what matters (see the prompt check). */
    private static final int MAX_PROMPT_CHARS = 64 * 1024;

    private static final Logger LOG = Logger.getLogger(RunResource.class);

    /** M0: one attempt per subject. A re-run is a later milestone's decision, not a default. */
    private static final int FIRST_ATTEMPT = 1;

    private static final String DEFAULT_BASE_BRANCH = "main";

    /** The branch is {@code spire/<subject>}; git refuses these two shapes, so refuse them here. */
    private static final String REF_DOTDOT = "..";

    private static final String REF_LOCK_SUFFIX = ".lock";

    /** One segment of a branch name; the whole-name rules live in {@link #refName}. */
    private static final Pattern REF_SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");

    private static final int MAX_REF_CHARS = 255;

    /** Stored on the row, which a viewer can read; the broker exception itself goes to the log. */
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
        if (req == null) {
            throw badRequest("a request body is required");
        }
        ScmType scmType = ScmType.fromProviderType(req.providerType())
                .orElseThrow(() -> badRequest("unknown providerType: " + req.providerType()));
        String workspace = namespace(req.workspace());
        String slug = segment(req.slug(), "slug");
        String baseCommit = required(req.baseCommit(), "baseCommit");
        if (!COMMIT.matcher(baseCommit).matches()) {
            throw badRequest("baseCommit must be a full 40-character hex commit id");
        }
        String prompt = required(req.prompt(), "prompt");
        if (prompt.length() > MAX_PROMPT_CHARS) {
            // The prompt rides every command copy, the DLQ row and the agent's environment; an
            // unbounded one is a stored-payload problem before it is a model-context problem.
            throw badRequest("prompt exceeds " + MAX_PROMPT_CHARS + " characters");
        }
        String harness = required(req.harness(), "harness");
        String agentImage = config.agentImage().get(harness);
        if (agentImage == null) {
            throw badRequest("no agent image is configured for harness '" + harness
                    + "'; this deployment configures " + config.agentImage().keySet()
                    + " (spire.factory.agent-image.<harness>)");
        }
        String model = required(req.model(), "model");
        if (!MODEL.matcher(model).matches()) {
            // Reaches the harness's argv as `--model <value>`; HarnessInvocation refuses a
            // flag-shaped value too, but after the row is written and the command is on the bus.
            throw badRequest("model is not a valid model name");
        }
        String baseBranch = req.baseBranch() == null || req.baseBranch().isBlank()
                ? DEFAULT_BASE_BRANCH : refName(req.baseBranch(), "baseBranch");
        String subject = subject(req.subject(), baseCommit);

        ScmProvider account = machineAccounts.resolve(scmType, workspace)
                .orElseThrow(() -> conflict("No FACTORY-role provider is registered for "
                        + scmType.providerType() + "/" + workspace + ". Register the machine "
                        + "account under Settings -> Providers with role FACTORY (ADR-037). "
                        + "The factory never pushes as the review bot."));
        LlmProviderConfig llm = harnessCredentialSource(req.llmProviderId(), harness);

        RepoRef repo = new RepoRef(workspace, slug);
        String runId = RunIds.of(scmType, workspace, slug, subject, FIRST_ATTEMPT);
        String branch = "spire/" + subject;

        // The deployment-wide caps (ADR-025) apply here as they do to a review: a run is a paid model
        // call, and V40 makes its spend count toward the same rolling window — so without this gate
        // runs would consume the cap the reviews respect while never respecting it themselves. Checked
        // BEFORE the row is written: a refused run is not a run, and a queued row for it would be a
        // 201-shaped promise. A ledger the gate could not read fails open, as it does for reviews.
        SpendGate.Decision cap = spendGate.decide();
        if (cap.refused()) {
            throw new ClientErrorException(Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .entity("Run not dispatched: " + cap.refusal().detail()
                            + ". Capacity returns as older usage ages out, or raise the cap in Settings -> General.")
                    .build());
        }

        // Recorded BEFORE dispatch, so a run can never exist on the bus without a row. A retried
        // request for a run that failed to dispatch re-arms it; any other existing row is a run
        // that is queued, running or already finished, and dispatching over it would be dropped by
        // the worker's claim as a redelivery -- a 201 for a run that never runs. So: 409, naming it.
        if (!projection.queued(runId, harness, model, baseBranch, baseCommit, branch, account.botUsername())) {
            String status = projection.find(runId).map(FactoryRunProjection.RunView::status).orElse("unknown");
            throw conflict("Run " + runId + " already exists (status " + status + "). M0 runs each subject "
                    + "once; pass a different subject to run again.");
        }

        // Both credentials ride the bus as Tink ciphertext bound to this run (ADR-015's rule, which
        // the review path already keeps). The raw values went on the command once: every other
        // credential on the bus was ciphertext, this one was a write token, and a dead-lettered
        // command lands in dlq_entry.payload — a plain TEXT column with no TTL — exactly as sent.
        RunCommand.ExecuteRun command = new RunCommand.ExecuteRun(runId, repo,
                FactoryCloneUrls.cloneUrl(scmType, account.baseUrl(), repo),
                baseBranch, baseCommit, branch, prompt, harness, model, agentImage,
                List.of(), config.wallClockSeconds(),
                runCredentials.packScm(runId, account.botUsername(), account.secret()),
                runCredentials.packHarness(runId, llm.apiKey()));
        try {
            emitter.dispatch(command);
        } catch (IllegalStateException e) {
            // The row stays and says why. Deleting it would leave a run the broker may well have
            // accepted (an ack timeout proves nothing) with no record; the retry re-arms this row.
            // The stored detail is a fixed sentence: the row is viewer-readable through GET, and a
            // broker exception names hosts and internals. The exception itself goes to the log.
            LOG.errorf(e, "run %s was recorded but the broker did not acknowledge its dispatch", runId);
            projection.dispatchFailed(runId, DISPATCH_FAILED_DETAIL);
            throw new ServerErrorException(Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("Run " + runId + " was recorded but not dispatched: " + e.getMessage()
                            + ". Retry the same request once the broker is reachable; it re-arms this run.")
                    .build(), e);
        }

        return Response.status(Response.Status.CREATED).entity(Map.of("runId", runId)).build();
    }

    /**
     * The subject names the run and its branch. Validated like a path segment, plus the two shapes
     * git refuses in a ref, so a bad name is a 400 here rather than a publisher that refuses to
     * start after the agent has already run and been paid for.
     */
    private static String subject(String raw, String baseCommit) {
        if (raw == null || raw.isBlank()) {
            return "manual-" + baseCommit.substring(0, 7);
        }
        String subject = segment(raw, "subject");
        if (subject.contains(REF_DOTDOT) || subject.endsWith(REF_LOCK_SUFFIX)) {
            throw badRequest("subject must be usable as a branch name: no '..' and no '.lock' suffix");
        }
        return subject;
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
                throw badRequest("llmProviderId must be a UUID");
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

    /** A 400 whose body says why — the bare exception's message never reaches the client. */
    private static BadRequestException badRequest(String message) {
        return new BadRequestException(
                Response.status(Response.Status.BAD_REQUEST).entity(message).build());
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

    /**
     * A workspace is one segment on GitHub and Bitbucket and may be several on GitLab
     * ({@code group/subgroup}). Each segment is validated on its own; the joined form is what the
     * run id carries, and {@code RunIds} splits on the LAST slash for exactly this reason. Validating
     * the whole value as one segment made a nested GitLab namespace undispatchable while the GET
     * route's own comment said it was supported.
     */
    private static String namespace(String value) {
        String v = required(value, "workspace");
        if (v.startsWith("/") || v.endsWith("/")) {
            throw badRequest("workspace must not start or end with '/'");
        }
        for (String part : v.split("/", -1)) {
            if (!SLUG.matcher(part).matches()) {
                throw badRequest("workspace is not a valid repository namespace");
            }
        }
        return v;
    }

    private static String segment(String value, String field) {
        String v = required(value, field);
        if (!SLUG.matcher(v).matches()) {
            throw badRequest(field + " is not a valid repository segment");
        }
        return v;
    }

    /**
     * A branch name git would accept: slash-separated segments, none empty, none starting with a
     * dot or a hyphen, no {@code ..} and no {@code .lock} suffix. The publisher clones this, so a
     * bad name is a 400 here rather than an init container that fails after the row is written.
     */
    private static String refName(String value, String field) {
        if (value.length() > MAX_REF_CHARS || value.startsWith("/") || value.endsWith("/")
                || value.contains(REF_DOTDOT) || value.endsWith(REF_LOCK_SUFFIX)) {
            throw badRequest(field + " is not a valid branch name");
        }
        for (String part : value.split("/", -1)) {
            if (part.isEmpty() || part.startsWith(".") || part.startsWith("-") || !REF_SEGMENT.matcher(part).matches()) {
                throw badRequest(field + " is not a valid branch name");
            }
        }
        return value;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw badRequest(field + " is required");
        }
        return value.strip();
    }
}
