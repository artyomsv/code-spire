package dev.codespire.orchestrator.factory;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunEventRecord;
import dev.codespire.contract.event.RunIds;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.caps.SpendGate;
import dev.codespire.orchestrator.llm.LlmModelPricer;
import dev.codespire.orchestrator.pipeline.BrokerAckFailure;
import dev.codespire.orchestrator.provider.ScmProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

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

    /**
     * Stored on the row for an uncertain dispatch, and deliberately not the phrasing above.
     *
     * <p>"Retry the same request" is the wrong instruction here and the expensive one: the record may
     * already be on the topic, so a retry is how a second agent ends up on the branch.
     *
     * <p>Per-run rather than a constant, because it names the endpoint. There is no factory UI, so
     * {@code GET /api/runs/{id}} is the operator's actual surface, and this is the only one of the
     * four messages about this condition that survives a page reload — the 503, the 409 and the
     * attention row are all transient. A detail ending "resolve it explicitly" with no address left
     * the durable one as the least useful.
     *
     * <p>Phrased as the consequence rather than as an order. "Do NOT retry" is an imperative on a
     * state row, and it stops being true the day a resolution UI or a reconciler exists.
     */
    static String uncertainDetail(String runId) {
        return "the command was dispatched and never acknowledged; whether it is running is unknown."
                + " A retry would publish a second command. If it started, its result will resolve this"
                + " row; otherwise POST {\"neverRan\": true} to " + resolutionPath(runId);
    }

    /** Named once, so the four messages about this condition cannot address it differently. */
    static String resolutionPath(String runId) {
        return "/api/runs/" + runId + "/dispatch-resolution";
    }

    /** A transcript page, never the whole stream: the per-run cap is ten thousand events. */
    private static final int DEFAULT_TRANSCRIPT_PAGE = 200;

    private static final int MAX_TRANSCRIPT_PAGE = 2_000;

    @Inject
    MachineAccounts machineAccounts;

    @Inject
    FactoryRunProjection projection;

    @Inject
    RunEventProjection transcripts;

    @Inject
    RunCommandEmitter emitter;

    @Inject
    FactoryConfig config;

    @Inject
    HarnessCredentialPool pool;

    @Inject
    SpendGate spendGate;

    @Inject
    LlmModelPricer pricer;

    @Inject
    RunCredentials runCredentials;

    /**
     * @param llmProviderId no longer accepted, and refused rather than ignored. The harness key now
     *                      comes from the rotating pool; a request that pinned one would defeat the
     *                      rotation, and honouring it silently would be worse. Kept on the record so
     *                      an existing caller gets a 400 saying what changed, rather than a run
     *                      dispatched with a credential it did not choose.
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
        refusePinnedProvider(req.llmProviderId());
        refuseAnUnpriceableModel(in.model());
        refuseOverTheSpendCap();
        // LAST of the checks, because selecting is a WRITE: it stamps last_used_at and so consumes a
        // rotation slot. Placed above these two it did that for every request they then refused, and
        // it holds a decrypted key from here on, so there is no reason to take it across work that
        // may throw.
        //
        // The `queued` 409 below still runs after it, and that one cannot be moved: the row write
        // needs the member's id. So a duplicate-subject request does still stamp a tiebreaker. That
        // costs one rotation position and no key, which is the residue rather than a fix, and it is
        // said here rather than left to be rediscovered.
        HarnessCredentialPool.PoolMember credential = harnessCredential();

        RepoRef repo = new RepoRef(in.workspace(), in.slug());
        String runId = RunIds.of(in.scmType(), in.workspace(), in.slug(), in.subject(), FIRST_ATTEMPT);
        String branch = DispatchRequestParser.RUN_BRANCH_PREFIX + in.subject();
        // Both credentials ride the bus as Tink ciphertext bound to this run (ADR-015's rule, which
        // the review path already keeps); a dead-lettered command lands in dlq_entry.payload as sent.
        RunCommand.ExecuteRun command = new RunCommand.ExecuteRun(runId, repo,
                FactoryCloneUrls.cloneUrl(in.scmType(), account.baseUrl(), repo),
                in.baseBranch(), in.baseCommit(), branch, in.prompt(), in.harness(), in.model(), in.agentImage(),
                List.of(), config.wallClockSeconds(),
                runCredentials.packScm(runId, account.botUsername(), account.secret()),
                runCredentials.packHarness(runId, credential.apiKey()));

        // Recorded BEFORE dispatch, so a run can never exist on the bus without a row. A retried
        // request for a run that failed to dispatch re-arms it; any other existing row is a run
        // that is queued, running or already finished, and dispatching over it would be dropped by
        // the worker's claim as a redelivery — a 201 for a run that never runs. So: 409, naming it.
        if (!projection.queued(new FactoryRunProjection.QueuedRun(runId, in.harness(), in.model(),
                in.baseBranch(), in.baseCommit(), branch, account.botUsername(), credential.id()))) {
            throw conflict(alreadyExists(runId));
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
                        + "account under Settings -> Providers with role FACTORY (ADR-038). "
                        + "The factory never pushes as the review bot."));
        if (account.botUsername() == null || account.botUsername().isBlank()) {
            throw conflict("The FACTORY-role provider for " + in.scmType().providerType() + "/" + in.workspace()
                    + " has no resolved login. Re-save it with a token the forge can identify, or set the "
                    + "bot username by hand: the login is what the push is authenticated as.");
        }
        return account;
    }

    /**
     * A run whose model cannot be priced is refused BEFORE it spends, exactly as a review is.
     *
     * <p>This is the half that protects the cap rather than reporting on it. Pricing is
     * post-hoc — the charge is written when the run is already over — so this is the last point
     * at which an unpriceable run can still be REFUSED rather than merely noticed afterwards.
     * Without it a deployment can spend without limit on a model nobody gave rates to, and every
     * one of those charges lands as UNKNOWN, which SUM() skips: the money cap would be looking at
     * a total that omits precisely the runs it cannot price.
     *
     * <p>A run is refused rather than recorded and skipped, because unlike a review nothing else
     * has happened yet: there is no diff already fetched and no context already assembled, so a
     * refusal here costs nothing and leaves no row to explain.
     */
    private void refuseAnUnpriceableModel(String model) {
        if (pricer.isPriceable(model)) {
            return;
        }
        throw conflict("Run not dispatched: model '" + model + "' has no usable pricing. Set input"
                + " and output rates in Settings -> LLM -> Models, or mark it UNMETERED if it is"
                + " self-hosted. A run that cannot be priced cannot be counted against the spend cap.");
    }

    /**
     * The deployment-wide caps (ADR-025) apply to a run as to a review: it is a paid model call, and
     * V42 makes its spend count toward the same rolling window. Checked BEFORE the row is written —
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

    /**
     * Publish the command, and record honestly which kind of failure a failure was (FR-F10).
     *
     * <p>The two branches are not shades of the same thing. A rejection the client calls
     * non-retriable happened before anything reached a partition, so the run definitely did not start
     * and re-arming the row is free. An acknowledgement that never came says nothing about the
     * record: the producer may still be retrying, the append may already have happened, and the
     * operator's identical retry would then put a second agent on the same branch with the model
     * paid twice.
     *
     * <p>The whole path used to take the first reading for both, which is the optimistic one. It now
     * fails closed — ambiguity becomes a state an operator resolves, and reality usually resolves it
     * first, because a record that did land produces a {@code RunStarted} that reopens the row.
     */
    private void dispatch(String runId, RunCommand.ExecuteRun command) {
        try {
            emitter.dispatch(command);
        } catch (IllegalStateException e) {
            // Caught at IllegalStateException, not at BrokerAckFailure, and the difference matters:
            // narrowing it let any other publish fault escape as a 500 with the row left `queued`,
            // so a run nobody will start would sit looking as though it were about to. Anything that
            // is not a classified ack failure counts as AMBIGUOUS, because a fault we cannot read
            // tells us nothing about whether the record left — which is the whole rule here.
            if (e instanceof BrokerAckFailure ack && !ack.mayHaveLanded()) {
                throw recordDefiniteMiss(runId, e);
            }
            throw recordUncertainDispatch(runId, e);
        }
    }

    /**
     * The record never reached a partition, so the run definitely did not start.
     *
     * <p>The row stays and says why — deleting it would leave no record of the attempt at all — and
     * this shape IS re-armable, so the operator's identical retry starts the run.
     */
    private ServerErrorException recordDefiniteMiss(String runId, IllegalStateException cause) {
        LOG.errorf(cause, "run %s was recorded but the broker refused its dispatch outright", runId);
        projection.dispatchFailed(runId, DISPATCH_FAILED_DETAIL);
        return new ServerErrorException(Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity("Run " + runId + " was recorded but not dispatched: " + cause.getMessage()
                        + ". Retry the same request once the broker is reachable; it re-arms this run.")
                .build(), cause);
    }

    /**
     * Nobody knows whether the record landed, so nothing is retried until somebody does.
     *
     * <p><b>Says "dispatched", never "published".</b> This branch also takes every fault the ack
     * helper could not classify — a terminated channel or a full emitter buffer throws before a
     * record is offered to the producer at all — so asserting the command is on the topic would send
     * an operator to grep for a record that may never have been serialized. The wording has to be
     * true of every input to the branch, which is the property the branch was built on.
     */
    private ServerErrorException recordUncertainDispatch(String runId, IllegalStateException cause) {
        LOG.errorf(cause, "run %s was recorded and its dispatch attempted, but no acknowledgement came"
                + " back; whether it is running is unknown until its result arrives or an operator says", runId);
        projection.dispatchUncertain(runId, uncertainDetail(runId));
        return new ServerErrorException(Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity("Run " + runId + " was dispatched and never acknowledged: " + cause.getMessage()
                        + ". It may or may not be running, so it is NOT retried automatically."
                        + " If it started, its own result will resolve this; otherwise resolve it"
                        + " at " + resolutionPath(runId) + ".")
                .build(), cause);
    }

    /**
     * The operator's finding.
     *
     * <p>A record with a boxed {@code Boolean} rather than a map, because the absent-vs-false
     * tri-state is load-bearing here: {@code null} is "you did not answer", and the two real answers
     * do opposite things. A primitive would make the unanswered case default to {@code false}, which
     * is the answer that permanently forbids the retry. Cancel and steer stay on maps — their fields
     * are optional strings with real defaults, so nothing is decided by absence.
     */
    public record DispatchResolution(Boolean neverRan) {
    }

    /**
     * An operator says what became of an unacknowledged dispatch (FR-F10).
     *
     * <p>Only for the runs reality did not resolve. A record that landed produces a
     * {@code RunStarted}, which reopens the row on its own — so this exists for the case where
     * nothing ever arrives and somebody has to look at the topic, the worker's logs, or the forge.
     *
     * <p>{@code neverRan} is the whole decision, and the two answers differ in whether the run may
     * be started again. Saying it never ran writes the re-armable failed shape, so an identical
     * retry of the original request starts it through the path that already exists. Saying it ran
     * closes the row terminally, because publishing again for a run that really happened is the
     * duplicate this state was created to prevent.
     */
    @POST
    @Path("/{runId:.+}/dispatch-resolution")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response resolveDispatch(@PathParam("runId") String runId, DispatchResolution body) {
        projection.find(runId).orElseThrow(() -> new NotFoundException("no such run: " + runId));
        Boolean neverRan = body == null ? null : body.neverRan();
        if (neverRan == null) {
            throw badRequest(
                    "Send {\"neverRan\": true} if no run was started, or {\"neverRan\": false} if one was."
                            + " There is no safe default: one answer allows a retry and the other forbids it.");
        }
        boolean resolved = neverRan
                ? projection.resolveAsNeverRan(runId)
                : projection.resolveAsStarted(runId);
        if (!resolved) {
            // Re-read, because the refusal means the row moved AFTER the lookup above — which is the
            // headline race this whole feature exists for, a RunStarted arriving while the operator
            // was deciding. Reporting the status read before the change made the 409 contradict
            // itself: "run … is dispatch_uncertain, not awaiting a dispatch resolution".
            String now = projection.find(runId)
                    .map(FactoryRunProjection.RunView::status).orElse("gone");
            throw conflict("run " + runId + " is " + now + ", not awaiting a dispatch resolution."
                    + " A run whose result arrived resolved itself.");
        }
        LOG.warnf("run %s: an operator resolved its uncertain dispatch as %s", runId,
                neverRan ? "never started" : "started");
        return Response.noContent().build();
    }

    /**
     * The key this run calls the model with, taken from the pool and never from the reviewer.
     *
     * <p>This used to fall back to the deployment's DEFAULT LLM provider — the same key the review
     * pipeline uses. That key then went into a container running an untrusted model on an untrusted
     * work item at full shell access, where a prompt-injected agent can read its own environment. One
     * exfiltration disabled reviews and runs together, and the resulting spend spike was
     * indistinguishable in the ledger from legitimate factory use.
     *
     * <p>So there is no fallback. An unconfigured pool is a refusal naming what to configure, which is
     * the same call {@link #machineAccount} already makes for the push identity: the factory never
     * borrows the reviewer's identity, in either direction.
     *
     * <p>Each refusal says which of the three states the pool is in, because they need three
     * different actions — wait, replace a key, or configure one at all.
     */
    private HarnessCredentialPool.PoolMember harnessCredential() {
        HarnessCredentialPool.Selection selection;
        try {
            selection = pool.select();
        } catch (IllegalStateException e) {
            // A read fault, not an empty pool. The pool throws rather than answering Empty
            // precisely to preserve that distinction, and nothing downstream of a bare 500 says
            // it -- so an operator would go and add keys they already have.
            throw new ServerErrorException(Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("The harness credential pool could not be read, so no key could be"
                            + " chosen. Nothing was dispatched and nothing was spent. This is a"
                            + " database fault, not a missing credential -- do not add keys in"
                            + " response to it.")
                    .build(), e);
        }
        return switch (selection) {
            case HarnessCredentialPool.Selection.Chosen chosen -> chosen.member();
            case HarnessCredentialPool.Selection.Resting resting -> throw conflict(
                    "No harness credential is available. Capacity returns at "
                            + resting.capacityReturnsAt() + "; the pool recovers on its own, so retry"
                            + " then or add another credential under Settings -> Harness credentials."
                            + (resting.rejected() == 0 ? ""
                                    : " " + resting.rejected() + " of them were refused outright and"
                                            + " will NOT come back without a new key."));
            case HarnessCredentialPool.Selection.AllRejected rejected -> throw conflict(
                    "All " + rejected.count() + " harness credential(s) were refused by their provider. "
                            + "Nothing recovers on its own: rotating onto a refused key spends a request "
                            + "per run to rediscover it is dead. Replace the keys, or clear one you have "
                            + "fixed, under Settings -> Harness credentials.");
            case HarnessCredentialPool.Selection.Empty ignored -> throw conflict(
                    "No harness credential is configured, so there is no key for this run to call the"
                            + " model with. Add one under Settings -> Harness credentials. The factory"
                            + " deliberately does NOT borrow the reviewer's key: it goes into a sandbox"
                            + " that runs an untrusted work item at full access.");
        };
    }

    /**
     * The request no longer chooses the key, so a request that tries to is refused rather than
     * quietly overruled.
     *
     * <p>{@code llmProviderId} named an LLM provider whose key the harness would run with. The pool
     * rotates now, and honouring a pin would defeat the rotation that exists to survive exhaustion.
     * Ignoring the field silently would be worse: the caller would believe they had pinned a key.
     */
    private static void refusePinnedProvider(String llmProviderId) {
        if (llmProviderId != null && !llmProviderId.isBlank()) {
            throw DispatchRequestParser.badRequest("llmProviderId is no longer accepted: the harness "
                    + "credential comes from the rotating pool under Settings -> Harness credentials, "
                    + "never from an LLM provider the reviewer also uses.");
        }
    }

    /**
     * Why a queued row was not written. A run whose dispatch was never acknowledged is re-armed
     * only by the identical request — the first command may be the one that runs — so a retry
     * with different parameters is told to retry as sent or start a new subject.
     */
    private String alreadyExists(String runId) {
        FactoryRunProjection.RunView existing = projection.find(runId).orElse(null);
        if (existing != null && FactoryRunProjection.DISPATCH_UNCERTAIN.equals(existing.status())) {
            // The fail-closed answer. Re-arming here is exactly the duplicate the uncertain state
            // exists to prevent: the first command may be on the topic, so an identical retry --
            // which is the RIGHT answer for a dispatch that definitely failed -- would put a second
            // agent on the same branch and pay for the model twice.
            return "Run " + runId + " was published but never acknowledged, so whether it is running is "
                    + "unknown. It is deliberately NOT retried. If it started, its own result will "
                    + "resolve this row; otherwise resolve it at POST " + resolutionPath(runId)
                    + " and then retry.";
        }
        if (existing != null && FactoryRunProjection.FAILED.equals(existing.status())
                && FactoryRunProjection.DISPATCH_FAILED.equals(existing.failureCause())) {
            return "Run " + runId + " was recorded but its dispatch was never acknowledged, and this request "
                    + "differs from the original. The original may already be running; retry the identical "
                    + "request to re-arm it, or use a new subject.";
        }
        String status = existing == null ? "unknown" : existing.status();
        return "Run " + runId + " already exists (status " + status + "). M0 runs each subject once; "
                + "pass a different subject to run again.";
    }

    /**
     * A 400 whose reason reaches the caller.
     *
     * <p>{@code new BadRequestException(message)} puts the text on the exception, not in the
     * response, so the client reads an empty body and learns nothing about what to send instead.
     */
    private static ClientErrorException badRequest(String message) {
        return new ClientErrorException(
                Response.status(Response.Status.BAD_REQUEST).entity(message).build());
    }

    private static ClientErrorException conflict(String message) {
        return new ClientErrorException(
                Response.status(Response.Status.CONFLICT).entity(message).build());
    }

    /**
     * Stop a run (FR-F6).
     *
     * <p>Without this the worker's control listener had no producer at all, so cancel was reachable
     * only by hand-producing to the topic — a consumer shipped for an operator control an operator
     * could not use.
     *
     * <p><b>The refusal happens HERE, and that is why it can be honest.</b> Every replica reads every
     * control record, so a listener cannot tell "not running on me" from "not running anywhere" and
     * must pass over both quietly. This can see the row: an unknown run is 404 and a finished one is
     * 409, answered synchronously, instead of an operator watching a timeline that never changes.
     *
     * <p>202, not 204: the broker has acknowledged the command, and the run stopping is what happens
     * afterwards. A 2xx here means the instruction was accepted for delivery, never that the sandbox
     * is already down.
     */
    @POST
    @Path("/{runId:.+}/cancel")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response cancel(@PathParam("runId") String runId, Map<String, Object> body) {
        requireLive(runId, "cancelled");
        String reason = text(body, "reason", "an operator cancelled this run");
        emitter.control(new RunCommand.CancelRun(runId, reason));
        LOG.infof("run %s: a cancel was published to the control topic", runId);
        return Response.accepted().build();
    }

    /**
     * Send a running agent a further instruction (FR-F6).
     *
     * <p>Accepted here and refused downstream when the harness cannot carry it: the capability is the
     * HARNESS's fact and this endpoint does not know which harness a run is using without reading the
     * row, while the worker holds it already. The refusal reaches the operator on the run's
     * transcript, which is why that line is written even when nothing is delivered.
     *
     * <p>No shipped harness declares steering today, so an accepted steer will be refused on the
     * transcript rather than delivered. That is worth knowing before wondering why nothing happened.
     */
    @POST
    @Path("/{runId:.+}/steer")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response steer(@PathParam("runId") String runId, Map<String, Object> body) {
        requireLive(runId, "steered");
        String instruction = text(body, "instruction", "");
        // Constructed here so a blank or oversized instruction is a 400 the caller can read, rather
        // than a record the worker's deserializer rejects into silence: the compact constructor's
        // guards fire on the consumer side otherwise, where nobody can be told.
        RunCommand.SteerRun command;
        try {
            command = new RunCommand.SteerRun(runId, instruction);
        } catch (IllegalArgumentException e) {
            throw badRequest(e.getMessage());
        }
        emitter.control(command);
        LOG.infof("run %s: a steer was published to the control topic", runId);
        return Response.accepted().build();
    }

    /**
     * The run exists and has not finished, or the caller is told which of those is false.
     *
     * @param verb what the caller was trying to do, so the 409 names it
     */
    private void requireLive(String runId, String verb) {
        FactoryRunProjection.RunView run = projection.find(runId)
                .orElseThrow(() -> new NotFoundException("no such run: " + runId));
        if (!FactoryRunProjection.QUEUED.equals(run.status())
                && !FactoryRunProjection.RUNNING.equals(run.status())
                // An uncertain dispatch may be executing RIGHT NOW — that is the entire premise of
                // the state — so it is exactly when a stop is wanted, and refusing here left it as
                // the only live state with no lever on live spend. A `queued` run, where nothing can
                // possibly be running yet, accepted one. A control record for a run nobody is running
                // is passed over quietly by every replica, so allowing this costs nothing.
                && !FactoryRunProjection.DISPATCH_UNCERTAIN.equals(run.status())) {
            throw conflict("run " + runId + " is " + run.status() + " and cannot be " + verb
                    + "; only a queued, running or unresolved run accepts control.");
        }
    }

    /** A string field from a small JSON body, defaulted rather than demanded. */
    private static String text(Map<String, Object> body, String field, String fallback) {
        Object value = body == null ? null : body.get(field);
        return value instanceof String s && !s.isBlank() ? s : fallback;
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

    /**
     * A run's transcript (FR-F5), newest bounded page.
     *
     * <p>Declared before the detail route below because that route's {@code .+} is greedy. JAX-RS
     * ranks candidates by literal character count, so this one should win regardless — but "should"
     * is not a property to rest a route on, and a test pins it.
     *
     * <p>Viewer-readable like the run detail it belongs to. The transcript quotes source, and so
     * does every finding a viewer already reads; what stays admin-only is configuration.
     */
    @GET
    @Path("/{runId:.+}/transcript")
    @RolesAllowed({"spire-viewer", "spire-admin"})
    public List<RunEventRecord> transcript(@PathParam("runId") String runId,
                                           @QueryParam("limit") Integer limit) {
        projection.find(runId).orElseThrow(() -> new NotFoundException("no such run: " + runId));
        return transcripts.newestPage(runId, boundedLimit(limit));
    }

    /**
     * A page, never the whole stream. The per-run cap is ten thousand events, and a reader that
     * asked for all of them would hold all of them in one response.
     */
    private static int boundedLimit(Integer requested) {
        int asked = requested == null ? DEFAULT_TRANSCRIPT_PAGE : requested;
        return Math.max(1, Math.min(asked, MAX_TRANSCRIPT_PAGE));
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
