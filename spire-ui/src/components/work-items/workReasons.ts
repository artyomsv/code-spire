/**
 * One place for the words the factory puts on screen for a machine reason.
 *
 * The screens and the API client both need them: a reason is the coarse contract the backend keys
 * on, and the operator needs a sentence. Refusals that name a rule carry a detail as well — one
 * reason covers five plan rules, so "single_step_plan_required" alone cannot say what to change.
 */
const REASONS = new Map(Object.entries({
  specification_required: 'A specification is required before work can continue.',
  artifacts_registered: 'The prepared task references were registered.',
  specification_supplied: 'The registered specification was fetched and validated.',
  plan_supplied: 'The registered single-step plan was fetched and validated.',
  artifacts_changed: 'The tracker artifacts changed. Register their current versions before continuing.',
  artifacts_unavailable: 'The tracker artifacts could not be read. Check the references and source account.',
  single_step_plan_required: 'The plan must contain one step and reference this specification version.',
  artifacts_changed_requires_new_decision: 'The artifacts changed after approval opened. Register their current versions for a new decision.',
  publication_hold_unavailable: 'Build execution is waiting for publication hold support.',
  policy_changed_before_delivery: 'Policy changed before delivery completed. Delivery has stopped.',
  publication_permit_expired: 'No publication outcome was observed before permission expired. Delivery has stopped.',
  publication_dispatch_uncertain: 'Publication was not acknowledged. Its outcome is still being checked.',
  publication_dispatch_missed: 'Publication could not be dispatched. The current policy will be checked before retrying.',
  publication_failed: 'The publisher did not report a successful delivery.',
  held_run_not_ready: 'The build has no observed checkpoint ready for publication.',
  verified_build_required: 'Delivery requires verification of this build.',
  verified_checkpoint_not_observed: 'Verification of the retained build could not be confirmed.',
  draft_pr_unsupported: 'This forge cannot create the requested draft pull request.',
  requested_pr_state_not_observed: 'The forge did not confirm the requested pull request state.',
  proposal_outcome_unknown: 'The pull request response was lost. The forge is being checked for an existing request.',
  delivered_pr_required: 'Review is waiting for a delivered pull request.',
  review_result_pending: 'Waiting for a completed review of this build.',
  review_head_not_observed: 'The review and its posted result do not both cover this build.',
  review_pr_not_open: 'The reviewed pull request is closed or archived.',
  review_evidence_unreadable: 'The recorded review evidence could not be read. Review remains waiting.',
  review_blockers_open: 'The review still has unresolved blockers.',
  policy_changed_before_dispatch: 'Policy changed before build dispatch. Re-admit under the current policy.',
  build_configuration_unavailable: 'Build settings, credentials or deployment limits prevented dispatch.',
  dispatch_uncertain: 'Build dispatch was not acknowledged. The run may have started; its result will resolve this state.',
  run_usage_unknown: 'Run usage could not be established. Further work is blocked until its spending can be accounted for.',
  actor_not_allowed: 'The person who applied this label is not on the source allowlist.',
  label_unattributed: 'The current label applier could not be confirmed.',
  actor_id_missing: 'The tracker did not identify the person who applied this label.',
  no_eligible_label: 'No current label is eligible to select a profile.',
  ceiling_missing: 'Configure a repository policy ceiling before admitting work.',
  policy_selected: 'The current labels selected this profile.',
  policy_clamped: 'The effective policy is restricted by another label, the admission version, or the ceiling.',
  intake_off: 'The effective policy does not allow intake.',
  spec_off: 'The effective policy stops before specification.',
  plan_off: 'The current policy stops before planning.',
  build_off: 'The current policy stops before building.',
  verify_off: 'The current policy stops before verification.',
  deliver_off: 'The current policy stops before delivery.',
  review_off: 'The current policy stops before review.',
  land_off: 'The current policy stops before merging.',
  spec_capability_unavailable: 'Specification generation is not available.',
  plan_capability_unavailable: 'Plan generation is not available.',
  build_capability_unavailable: 'Build execution is not available.',
  verify_capability_unavailable: 'Verification is not available.',
  deliver_capability_unavailable: 'Delivery is not available.',
  review_capability_unavailable: 'Review execution is not available.',
  land_capability_unavailable: 'Automatic merging is not available.',
  source_unavailable: 'The work source or its serving account is unavailable.',
  phase_started: 'This phase has started.',
  phase_completed: 'This phase completed.',
  phase_failed: 'This phase failed.',
  all_phases_completed: 'All workflow phases completed.',
  policy_cap_reached: 'A current policy limit has been reached.',
  approval_required: 'An operator decision is required before this phase can start.',
  gate_expired: 'The approval expired. Explicit re-admission is required.',
  gate_rejected: 'An operator rejected this phase.',
  policy_changed_requires_new_decision: 'The policy changed after this approval opened. Re-admit to request a new decision.',
  labels_changed_requires_new_decision: 'The current labels changed after this approval opened. Re-admit to request a new decision.',
  intake_approval_unavailable: 'Intake needs approval, but this approval capability is not available yet.',
  spec_approval_unavailable: 'Specification needs approval, but this approval capability is not available yet.',
}));

export function workReason(reason: string) { return REASONS.get(reason) ?? reason; }

/** The rule that refused a registration. The backend sends these beside the coarse reason. */
const DETAILS = new Map(Object.entries({
  plan_not_json: 'The plan ticket body is not valid JSON. Copy the format again, and keep the instruction on a single line.',
  plan_schema_version: 'The plan must be a JSON object with "schemaVersion": 1.',
  plan_specification_mismatch: 'The plan names a different specification version. Read the specification version again, then copy its digest into the plan.',
  plan_step_count: 'The plan must contain exactly one step.',
  plan_step_fields: 'The plan step needs an id and an instruction, and neither may be blank.',
  specification_changed: 'The specification ticket changed after it was checked. Check the references again.',
  plan_changed: 'The plan ticket changed after it was checked. Check the references again.',
}));

/** What to tell the operator about a refusal: the rule when the backend named one, else the reason. */
export function workRefusal(reason: string, detail: string | null): string {
  return (detail && DETAILS.get(detail)) ?? REASONS.get(reason) ?? reason;
}
