package dev.codespire.contract.work;

import dev.codespire.worksource.WorkIssueLocation;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Sole work domain-event decider. Executors never manufacture phase completion. */
public final class WorkItemLifecycle {
    private WorkItemLifecycle() {}

    public static WorkItemEvent reconcile(String id, UUID source, UUID repository, WorkIssueLocation issue,
                                          long policyRevision, WorkItemEvent.Authority authority, WorkPolicy.Selection policy, WorkItemEvent previous) {
        if(previous!=null && previous.policyRevision()==policyRevision && previous.authority().equals(authority)
                && previous.policy().equals(policy) && previous.issue().equals(issue))return previous;
        if (previous != null && (java.util.Set.of("suspended","retired").contains(previous.workflowStatus()) || previous.preparation() != null || previous.progress().attemptId() != null || previous.gate() != null || previous.generation() > 1))
            return previous.decision(policyRevision,authority,policy,previous.phase(),previous.workflowStatus(),previous.reason(),
                    "POLICY_OBSERVED",previous.gate(),previous.progress());
        String intake = policy.effective().getOrDefault(WorkPolicy.Phase.INTAKE, "off");
        String status = "not_eligible";
        String reason = policy.reason();
        String phase = "intake";
        if (policy.selected() != null && "auto".equals(intake)) {
            phase = "spec";
            String spec = policy.effective().getOrDefault(WorkPolicy.Phase.SPEC, "off");
            status = switch (spec) {
                case "auto" -> "awaiting_input";
                case "approve" -> "capability_unavailable";
                default -> "stopped";
            };
            reason = switch (spec) {
                case "auto" -> "specification_required";
                case "approve" -> "spec_approval_unavailable";
                default -> "spec_off";
            };
        } else if (policy.selected() != null && "approve".equals(intake)) {
            status = "capability_unavailable";
            reason = "intake_approval_unavailable";
        } else if (policy.selected() != null) reason = "intake_off";
        return new WorkItemEvent(id, source, repository, issue, previous == null ? 1 : previous.generation(),
                policyRevision, previous != null && previous.admittedProfile() != null ? previous.admittedProfile()
                        : policy.selected(), previous != null && previous.admittedProfile() != null ? previous.admittedModes()
                        : policy.effective(), authority, policy, phase, status, reason,
                previous != null && previous.admittedProfile() != null ? previous.admittedLimits() : policy.limits(), "POLICY_OBSERVED")
                .controlled(previous==null?null:previous.control());
    }

    public static boolean clampChanged(WorkItemEvent previous, WorkItemEvent next) {
        if (!"policy_clamped".equals(next.policy().reason())) return false;
        if (previous == null || !"policy_clamped".equals(previous.policy().reason())) return true;
        return !java.util.Objects.equals(previous.policy().selected(), next.policy().selected())
                || !java.util.Objects.equals(previous.policy().ceiling(), next.policy().ceiling())
                || !previous.policy().effective().equals(next.policy().effective())
                || !previous.policy().limits().equals(next.policy().limits());
    }

    public static WorkItemEvent fold(List<WorkItemEvent> events) {
        return events.isEmpty() ? null : events.getLast();
    }
    public static WorkItemEvent enter(WorkItemEvent item,WorkItemEvent previous,long historySize,boolean approved,java.time.Instant now,boolean available,UUID decisionId) {
        return enter(item,previous,historySize,approved,now,available,decisionId,false);
    }
    /**
     * @param landFix whether this generation's latest land fix is still in progress ({@link #landFixInProgress}). The
     *     decider sees no history, so the caller derives it under the item lock (A9). During a fix the build and deliver
     *     gates are skipped, because the new land approval is the one human check (operator, 2026-10-21), and a build
     *     keeps the pull request it fixes. Verify and land keep their gates.
     */
    public static WorkItemEvent enter(WorkItemEvent item,WorkItemEvent previous,long historySize,boolean approved,java.time.Instant now,boolean available,UUID decisionId,boolean landFix) {
        if(item.policy().selected()==null)return state(item,"not_eligible",item.policy().reason(),"WORK_ITEM_REFUSED",item.gate(),item.progress().reserve(false));
        if("complete".equals(item.phase()))return state(item,"completed","all_phases_completed","WORK_ITEM_COMPLETED",item.gate(),item.progress().reserve(false));
        String mode=item.policy().effective().getOrDefault(WorkPolicy.Phase.valueOf(item.phase().toUpperCase(Locale.ROOT)),"off");
        if("off".equals(mode))return state(item,"not_eligible",item.phase()+"_off","WORK_ITEM_REFUSED",item.gate(),item.progress().reserve(false));
        if(item.progress().usageUnknown())return state(item,"awaiting_input","run_usage_unknown","WORK_ITEM_REFUSED",item.gate(),item.progress().reserve(false));
        if(!item.progress().within(item.policy().limits(),item.phase()))return state(item,"stopped","policy_cap_reached","WORK_ITEM_REFUSED",item.gate(),item.progress().reserve(false));
        boolean fixSkipsGate=landFix && java.util.Set.of("build","deliver").contains(item.phase());
        if("approve".equals(mode) && !approved && !fixSkipsGate) {
            long eventRevision=historySize+1+(WorkItemLifecycle.clampChanged(previous,item)?1:0);
            WorkGate gate=new WorkGate(decisionId,1,"OPEN",item.phase(),item.generation(),eventRevision,item.policyRevision(),item.authority(),WorkGate.artifactOf(item),
                    now,now.plusSeconds(item.policy().limits().gateTtlSeconds()),null,null,null,null);
            return state(item,"waiting_approval","approval_required","GATE_OPENED",gate,item.progress().reserve(true));
        }
        if("intake".equals(item.phase()))return enter(item.decision(item.policyRevision(),item.authority(),item.policy(),"spec",item.workflowStatus(),item.reason(),item.milestone(),item.gate(),item.progress()),previous,historySize,false,now,available,decisionId,landFix);
        if(item.preparation()!=null && java.util.Set.of("spec","plan").contains(item.phase()))
            return state(item,"awaiting_input",item.phase().equals("spec")?"specification_supplied":"plan_supplied","ARTIFACT_ACCEPTED",item.gate(),item.progress().reserve(false));
        if(!available)return state(item,"capability_unavailable",item.phase()+"_capability_unavailable","CAPABILITY_UNAVAILABLE",item.gate(),item.progress().reserve(false));
        WorkProgress started=landFix && "build".equals(item.phase())?item.progress().startKeepingExecution(decisionId,item.phase(),now)
                :item.progress().start(decisionId,item.phase(),now);
        return state(item,"active","phase_started","PHASE_STARTED",item.gate(),started);
    }

    /** The refusals a land fix answers (spec §7.5): they rewind to build instead of handing the item over. */
    public static final java.util.Set<String> LAND_FIX_REASONS=java.util.Set.of("land_checks_failed","land_conflict","land_branch_behind");
    /** The fixes that spend an attempt; a branch behind its base is merged up for free (§7.5). */
    public static final java.util.Set<String> LAND_FIX_COUNTED=java.util.Set.of("land_checks_failed","land_conflict");
    /** What ends a land fix, beside the next land gate: the merge, a hand-off, a close, or a new generation. */
    private static final java.util.Set<String> LAND_FIX_ENDS=java.util.Set.of("WORK_ITEM_LANDED","LAND_HANDED","PULL_REQUEST_CLOSED","READMITTED");

    /**
     * Land was refused for a reason code can fix (spec §7.5): the item rewinds to build on the same pull request. The
     * execution, and with it the pull request, is kept, and the progress still names the land attempt, which is how
     * the fix's effect row is found again. Entering build is the caller's next decision.
     */
    public static WorkItemEvent landFixRequested(WorkItemEvent item,String reason) {
        if(!LAND_FIX_REASONS.contains(reason))throw new IllegalArgumentException("Not a land fix reason "+reason);
        if(item.progress().execution()==null || item.progress().execution().pullRequest()==null)
            throw new IllegalStateException("Only a delivered pull request can be fixed");
        // The land attempt is over: the next land gate starts another, and only one attempt per phase may be started.
        WorkProgress progress="land".equals(item.progress().attemptPhase())?item.progress().finish(0,0,0):item.progress().reserve(false);
        return item.decision(item.policyRevision(),item.authority(),item.policy(),"build","awaiting_input",reason,"LAND_FIX_REQUESTED",null,progress);
    }
    /** Whether the current generation's latest land fix has not ended yet. A verify gate inside the fix does not end it. */
    public static boolean landFixInProgress(List<WorkItemEvent> history) {
        if(history.isEmpty())return false;
        long generation=history.getLast().generation();
        boolean fixing=false;
        for(WorkItemEvent event:history) {
            if(event.generation()!=generation)continue;
            if("LAND_FIX_REQUESTED".equals(event.milestone()))fixing=true;
            else if(LAND_FIX_ENDS.contains(event.milestone())
                    || "GATE_OPENED".equals(event.milestone()) && event.gate()!=null && "land".equals(event.gate().phase()))fixing=false;
        }
        return fixing;
    }

    /** The reasons an open verify gate carries when it asks about a result rather than permission to run. */
    public static final java.util.Set<String> VERIFY_RESULT_REASONS=java.util.Set.of("verify_failed","verify_unverified");

    /**
     * Failed or unverified checks open a gate in every verify mode but off, auto included (spec §4.2, ADR-046):
     * only a human accepts a result that is not passed. It is not PHASE_FAILED, because verify fails a step,
     * never a work item (FR-F20).
     */
    public static WorkItemEvent verifyResultGate(WorkItemEvent item,WorkItemEvent previous,long historySize,java.time.Instant now,UUID decisionId,WorkVerification result) {
        if(result.passed())throw new IllegalArgumentException("A passed verification completes the phase");
        if(!"verify".equals(item.phase()))throw new IllegalStateException("A result gate belongs to verify, not "+item.phase());
        if("off".equals(item.policy().effective().getOrDefault(WorkPolicy.Phase.VERIFY,"off")))throw new IllegalStateException("Verify is off for this item");
        long eventRevision=historySize+1+(clampChanged(previous,item)?1:0);
        WorkGate gate=new WorkGate(decisionId,1,"OPEN","verify",item.generation(),eventRevision,item.policyRevision(),item.authority(),WorkGate.artifactOf(item),
                now,now.plusSeconds(item.policy().limits().gateTtlSeconds()),null,null,null,null);
        String reason=result.outcome()==WorkVerification.Outcome.FAILED?"verify_failed":"verify_unverified";
        return state(item,"waiting_approval",reason,"GATE_OPENED",gate,item.progress().reserve(true));
    }

    /**
     * Why land handed an item to the operator (spec §7.6). The item waits at land / awaiting_input with no gate:
     * Retry land opens a fresh one, and readmission is refused (§7.9). A failed check, a conflict and a branch behind
     * its base are not here: they request a land fix ({@link #LAND_FIX_REASONS}).
     */
    public static final java.util.Set<String> LAND_HANDOFF_REASONS=java.util.Set.of("land_head_moved","land_checks_timeout","land_checks_not_code",
            "land_blocked","land_pr_draft","land_merge_refused","land_merge_method_not_allowed","land_fix_attempts_exhausted","land_item_limit_reached",
            "land_fix_review_blocked","land_fix_run_failed","land_fix_stopped","land_account_unavailable","land_read_failed");

    /**
     * The merge completes the item at once (§7.7): the ticket and the branch delete never hold completion. Only
     * the land attempt finishes here; a fix build or verify still running keeps its attempt open, so its late
     * result is accounted rather than read as already applied (§7.8).
     */
    public static WorkItemEvent landed(WorkItemEvent item,WorkLanding landing) {
        WorkExecution execution=item.progress().execution();
        if(execution==null)throw new IllegalStateException("Only a delivered execution can land");
        WorkProgress current="land".equals(item.progress().attemptPhase())?item.progress().finish(0,0,0):item.progress().reserve(false);
        WorkProgress progress=current.withExecution(execution.landed(landing));
        return item.decision(item.policyRevision(),item.authority(),item.policy(),"complete","completed","landed","WORK_ITEM_LANDED",superseded(item.gate(),"landed"),progress);
    }
    /**
     * An approval resolved before the hand-off is never reused, so the item carries no gate. The attempt the hand-off
     * interrupts (land, or a fix's review) is finished: a later land or review start in this generation would otherwise
     * meet it still started, and only one attempt per phase may be.
     */
    public static WorkItemEvent landHanded(WorkItemEvent item,String reason) {
        if(!LAND_HANDOFF_REASONS.contains(reason))throw new IllegalArgumentException("Unknown land hand-off reason "+reason);
        WorkProgress progress="started".equals(item.progress().attemptState())?item.progress().finish(0,0,0):item.progress().reserve(false);
        return item.decision(item.policyRevision(),item.authority(),item.policy(),"land","awaiting_input",reason,"LAND_HANDED",null,progress);
    }
    /** Where a stopped land fix would otherwise wait; with its pull request open, readmit and recheck both refuse each. */
    private static final java.util.Set<String> FIX_STOP_STATUSES=java.util.Set.of("stopped","not_eligible","failed","capability_unavailable");
    /**
     * A land fix that stopped before its next land gate is handed to the operator, because its pull request is still
     * open: readmit would build a second one beside it. Unless the fix's own head was reviewed, the item goes back to
     * the execution the fix started from, the head that was verified, reviewed and approved, so nothing the fix built
     * unpushed or pushed unreviewed reaches a land gate. Null when {@code stopped} stops no fix in progress; the stop itself stays in history before the hand-off.
     *
     * @param before the history {@code stopped} is appended to
     */
    public static WorkItemEvent landFixStopped(List<WorkItemEvent> before,WorkItemEvent stopped) {
        if(!landFixInProgress(before) || LAND_FIX_ENDS.contains(stopped.milestone()))return null;
        boolean stops=FIX_STOP_STATUSES.contains(stopped.workflowStatus())
                || "awaiting_input".equals(stopped.workflowStatus()) && "run_usage_unknown".equals(stopped.reason());
        if(!stops)return null;
        WorkItemEvent requested=before.reversed().stream().filter(event->"LAND_FIX_REQUESTED".equals(event.milestone())).findFirst().orElseThrow();
        WorkExecution current=stopped.progress().execution();
        // A fix delivered and reviewed already stands on its own head, as when entering land is refused after its review.
        WorkExecution kept=current!=null && current.reviewId()!=null?current:requested.progress().execution();
        return landHanded(state(stopped,stopped.workflowStatus(),stopped.reason(),stopped.milestone(),stopped.gate(),stopped.progress().withExecution(kept)),"land_fix_stopped");
    }
    /**
     * Retry land (spec §7.6, A3): a fresh land gate on the execution's head, the one that was verified and reviewed, never
     * on whatever the pull request holds now; the caller refuses the retry when the two differ. The item carries the
     * current policy revision before this is called, so the gate binds it. An approval resolved before the hand-off is
     * not reused: the gate is new.
     */
    public static WorkItemEvent retryLandGate(WorkItemEvent item,WorkItemEvent previous,long historySize,java.time.Instant now,UUID decisionId) {
        requireHanded(item);
        WorkExecution execution=item.progress().execution();
        if(execution==null || execution.pullRequest()==null)throw new IllegalStateException("Only a delivered pull request can land");
        long eventRevision=historySize+1+(clampChanged(previous,item)?1:0);
        WorkGate gate=new WorkGate(decisionId,1,"OPEN","land",item.generation(),eventRevision,item.policyRevision(),item.authority(),execution.head(),
                now,now.plusSeconds(item.policy().limits().gateTtlSeconds()),null,null,null,null);
        return state(item,"waiting_approval","approval_required","LAND_RETRY_REQUESTED",gate,item.progress().reserve(true));
    }
    /** Stop on a handed item (§7.6): it ends there. The pull request is left as it is, so the execution is kept. */
    public static WorkItemEvent landStopped(WorkItemEvent item) {
        requireHanded(item);
        return state(item,"stopped","land_stopped_by_operator","LAND_STOPPED",null,item.progress().reserve(false));
    }
    /** Whether land handed this item to the operator, where Retry land and Stop are the only ways on. */
    public static boolean landHandedOver(WorkItemEvent item) {
        return "land".equals(item.phase()) && "awaiting_input".equals(item.workflowStatus()) && LAND_HANDOFF_REASONS.contains(item.reason());
    }
    private static void requireHanded(WorkItemEvent item) {
        if(!landHandedOver(item))throw new IllegalStateException("Only an item land handed over can retry or stop land, not "
                +item.phase()+" / "+item.workflowStatus()+" / "+item.reason());
    }
    public static WorkItemEvent pullRequestClosed(WorkItemEvent item) {
        return state(item,"stopped","pr_closed_by_human","PULL_REQUEST_CLOSED",superseded(item.gate(),"pr_closed"),item.progress().reserve(false));
    }
    /** An open gate on a terminal item would later expire and overwrite why it ended, so the fact supersedes it. */
    private static WorkGate superseded(WorkGate gate,String fact) {
        return gate!=null && "OPEN".equals(gate.state())?gate.resolve("SUPERSEDED",null,"system",fact,null):gate;
    }
    /** Whether this generation recorded the merge of that pull request; a later fact does not hide it. */
    public static boolean landedIn(List<WorkItemEvent> history,long generation,long pullRequest) {
        return history.stream().anyMatch(event->event.generation()==generation && "WORK_ITEM_LANDED".equals(event.milestone())
                && event.progress().execution()!=null && event.progress().execution().landing()!=null
                && event.progress().execution().landing().pullRequest().number()==pullRequest);
    }

    public static WorkItemEvent state(WorkItemEvent item,String status,String reason,String milestone,WorkGate gate,WorkProgress progress) {
        return item.decision(item.policyRevision(),item.authority(),item.policy(),item.phase(),status,reason,milestone,gate,progress);
    }
}
