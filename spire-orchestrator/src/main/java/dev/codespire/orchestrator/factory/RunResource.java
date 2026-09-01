package dev.codespire.orchestrator.factory;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunIds;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.RepoRef;
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

import java.util.List;
import java.util.Map;
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

    private static final Pattern COMMIT = Pattern.compile("[0-9a-fA-F]{7,64}");

    /** M0: one attempt per subject. A re-run is a later milestone's decision, not a default. */
    private static final int FIRST_ATTEMPT = 1;

    private static final String DEFAULT_BASE_BRANCH = "main";

    /** The branch is {@code spire/<subject>}; git refuses these two shapes, so refuse them here. */
    private static final String REF_DOTDOT = "..";

    private static final String REF_LOCK_SUFFIX = ".lock";

    @Inject
    MachineAccounts machineAccounts;

    @Inject
    FactoryRunProjection projection;

    @Inject
    RunCommandEmitter emitter;

    @Inject
    FactoryConfig config;

    public record DispatchRequest(String workspace, String slug, String providerType, String baseBranch,
                                  String baseCommit, String prompt, String harness, String model,
                                  String subject) {
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response dispatch(DispatchRequest req) {
        if (req == null) {
            throw badRequest("a request body is required");
        }
        ScmType scmType = ScmType.fromProviderType(req.providerType())
                .orElseThrow(() -> badRequest("unknown providerType: " + req.providerType()));
        String workspace = segment(req.workspace(), "workspace");
        String slug = segment(req.slug(), "slug");
        String baseCommit = required(req.baseCommit(), "baseCommit");
        if (!COMMIT.matcher(baseCommit).matches()) {
            throw badRequest("baseCommit must be a hex commit id");
        }
        String prompt = required(req.prompt(), "prompt");
        String harness = required(req.harness(), "harness");
        String agentImage = config.agentImage().get(harness);
        if (agentImage == null) {
            throw badRequest("no agent image is configured for harness '" + harness
                    + "'; this deployment configures " + config.agentImage().keySet()
                    + " (spire.factory.agent-image.<harness>)");
        }
        String model = required(req.model(), "model");
        String baseBranch = req.baseBranch() == null || req.baseBranch().isBlank()
                ? DEFAULT_BASE_BRANCH : req.baseBranch();
        String subject = subject(req.subject(), baseCommit);

        ScmProvider account = machineAccounts.resolve(scmType, workspace)
                .orElseThrow(() -> new ClientErrorException(Response.status(Response.Status.CONFLICT)
                        .entity("No FACTORY-role provider is registered for "
                                + scmType.providerType() + "/" + workspace + ". Register the machine "
                                + "account under Settings -> Providers with role FACTORY (ADR-037). "
                                + "The factory never pushes as the review bot.")
                        .build()));

        RepoRef repo = new RepoRef(workspace, slug);
        String runId = RunIds.of(scmType, workspace, slug, subject, FIRST_ATTEMPT);
        String branch = "spire/" + subject;

        // Recorded BEFORE dispatch, so a run can never exist on the bus without a row — and the
        // insert is idempotent, so a retried request for the same subject changes nothing.
        projection.queued(runId, harness, model, baseBranch, baseCommit, branch, account.botUsername());

        // M0 packs the machine account's raw token. KEK wrapping (ADR-030) lands with the credential
        // pool; the field name already says what it will carry so the call sites need no change.
        RunCommand.ExecuteRun command = new RunCommand.ExecuteRun(runId, repo,
                FactoryCloneUrls.cloneUrl(scmType, account.baseUrl(), repo),
                baseBranch, baseCommit, branch, prompt, harness, model, agentImage,
                List.of(), config.wallClockSeconds(), account.secret(), null);
        try {
            emitter.dispatch(command);
        } catch (IllegalStateException e) {
            // The row stays and says why. Deleting it would leave a run the broker may well have
            // accepted (an ack timeout proves nothing) with no record; the retry re-arms this row.
            projection.dispatchFailed(runId, e.getMessage());
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

    /** A 400 whose body says why — the bare exception's message never reaches the client. */
    private static BadRequestException badRequest(String message) {
        return new BadRequestException(
                Response.status(Response.Status.BAD_REQUEST).entity(message).build());
    }

    @GET
    // A run id embeds the repository (`run::github:acme/app:subject:1`) and a GitLab workspace can
    // itself be `group/subgroup`, so the id spans several path segments; the regex keeps them all.
    @Path("/{runId:.+}")
    @RolesAllowed({"spire-viewer", "spire-admin"})
    public FactoryRunProjection.RunView get(@PathParam("runId") String runId) {
        return projection.find(runId).orElseThrow(() -> new NotFoundException("no such run: " + runId));
    }

    private static String segment(String value, String field) {
        String v = required(value, field);
        if (!SLUG.matcher(v).matches()) {
            throw badRequest(field + " is not a valid repository segment");
        }
        return v;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw badRequest(field + " is required");
        }
        return value.strip();
    }
}
