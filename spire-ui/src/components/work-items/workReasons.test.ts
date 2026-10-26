import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { LAND_HANDOFF_REASONS, LAND_REASONS, landReason, workReason, workRefusal } from './workReasons';

/**
 * A stopped item has to say what to change. The pricing refusal carries its own payload — the token
 * types nobody priced — because "pricing" alone sent operators back to Settings to re-enter rates they
 * had already entered (M3.5 part D).
 */
describe('workReason', () => {
  it('names the token types a stopped build could not price', () => {
    const sentence = workReason('model_pricing_incomplete:CACHED_INPUT,REASONING');
    expect(sentence).toContain('Cached input, Reasoning');
    expect(sentence).toContain('Settings → LLM');
  });

  it('still answers for a reason that carries no payload', () => {
    expect(workReason('run_usage_unknown')).toContain('Run usage could not be established');
  });

  it('says something usable when the payload is empty', () => {
    expect(workReason('model_pricing_incomplete:')).toContain('cannot price what this harness reports');
  });

  it('passes an unknown reason through rather than inventing one', () => {
    expect(workReason('TEST-reason-nobody-wrote')).toBe('TEST-reason-nobody-wrote');
  });

  it('prefers the rule a refusal names over its coarse reason', () => {
    expect(workRefusal('single_step_plan_required', 'plan_step_count')).toContain('exactly one step');
    expect(workRefusal('single_step_plan_required', null)).toContain('one step and reference');
  });
});

describe('ticket after land (M4)', () => {
  it.each(['ticket_close_unsupported', 'ticket_rule_changed', 'comment_not_sent'])('%s has a sentence', reason => {
    expect(workReason(reason)).not.toBe(reason);
  });
});

describe('verify reasons (M4)', () => {
  it.each(['verify_failed', 'verify_unverified', 'verify_stopped_by_operator', 'check_failed', 'no_checks_declared', 'tool_missing',
    'timed_out', 'checkpoint_missing', 'verify_could_not_run', 'verify_command_invalid', 'verify_timeout_out_of_range'])('%s has a sentence', reason => {
    expect(workReason(reason)).not.toBe(reason);
  });
});

describe('land setup reasons (M4 land)', () => {
  it.each(['land_settings_changed', 'merge_method_invalid', 'ticket_rule_invalid', 'fix_attempts_invalid'])('%s has a sentence', reason => {
    expect(workReason(reason)).not.toBe(reason);
  });
});

describe('land reasons (M4)', () => {
  it.each(['landed', 'pr_closed_by_human', 'land_head_moved', 'land_checks_timeout', 'land_checks_not_code', 'land_blocked', 'land_pr_draft',
    'land_merge_refused', 'land_merge_method_not_allowed', 'land_fix_attempts_exhausted', 'land_item_limit_reached', 'land_fix_review_blocked',
    'land_checks_failed', 'land_conflict', 'land_branch_behind', 'land_fix_run_failed', 'land_fix_stopped', 'readmission_landed', 'readmission_land_pending',
    'branch_delete_failed', 'land_account_unavailable', 'land_read_failed', 'land_merge_unconfirmed', 'land_merge_unreadable',
    'merge_outcome_unknown', 'readmission_merge_unconfirmed'])('%s has a sentence', reason => {
    expect(workReason(reason)).not.toBe(reason);
  });
});

// M4 land slice 8: every reason land can show reads as a sentence, and a hand-off names what GitHub said.
describe('every land reason (M4 land)', () => {
  it('covers Retry land, Stop and every hand-off', () => {
    expect(LAND_REASONS).toEqual(expect.arrayContaining(['retry_land_required', 'retry_land_unavailable', 'retry_land_head_unavailable',
      'land_stopped_by_operator', ...LAND_HANDOFF_REASONS]));
  });
  it.each([...LAND_REASONS])('%s has a sentence', reason => {
    expect(workReason(reason)).not.toBe(reason);
  });
  // The screen's list is copy only; the server says which reasons hand an item over. Read the contract's set so a reason
  // added there without a sentence here fails (review of the branch).
  it('has a sentence for every hand-off reason the contract names', () => {
    const java = readFileSync(join(process.cwd(), '..', 'spire-contract', 'src', 'main', 'java', 'dev', 'codespire', 'contract', 'work', 'WorkItemLifecycle.java'), 'utf8');
    const declaration = /LAND_HANDOFF_REASONS\s*=\s*java\.util\.Set\.of\(([^)]*)\)/.exec(java);
    expect(declaration, 'WorkItemLifecycle.LAND_HANDOFF_REASONS is declared').not.toBeNull();
    const contract = [...(declaration?.[1] ?? '').matchAll(/"([a-z_]+)"/g)].map(match => match[1]);
    expect(contract.length).toBeGreaterThan(0);
    expect([...LAND_HANDOFF_REASONS].sort()).toEqual([...contract].sort());
    for (const reason of contract) expect(workReason(reason), reason).not.toBe(reason);
  });
  it('quotes what GitHub said beside a hand-off', () => {
    const sentence = landReason('land_blocked', 'TEST-At least 1 approving review is required');
    expect(sentence).toContain('GitHub requires something only a person can give');
    expect(sentence).toContain('TEST-At least 1 approving review is required');
    expect(landReason('land_blocked', null)).toBe(workReason('land_blocked'));
  });
});

describe('preparation setting reasons (M4 slice 3)', () => {
  it.each(['preparation_invalid'])('every new reason has a sentence: %s', reason => {
    expect(workReason(reason)).not.toBe(reason);
  });

  // The item's refusal talks about the item, not about the build setup.
  it('keeps the item refusal about the item', () => {
    expect(workReason('preparation_unavailable')).toContain('under way or finished');
    expect(workReason('preparation_unavailable')).not.toContain('specifications and plans');
  });
});

describe('readmit and regenerate reasons (M4 slice 3, A7)', () => {
  it.each(['regenerated', 'regenerate_unavailable', 'preparation_generated'])('every new reason has a sentence: %s', reason => {
    expect(workReason(reason)).not.toBe(reason);
  });
});

describe('draft reasons (M4 slice 3)', () => {
  it.each(['specification_to_write', 'plan_to_write'])('every new reason has a sentence: %s', reason => {
    expect(workReason(reason)).not.toBe(reason);
  });

  // The factory writes these itself; the sentence must not send the reader to prepare the task by hand.
  it('says the factory writes the draft', () => {
    expect(workReason('specification_to_write')).toContain('factory');
    expect(workReason('plan_to_write')).toContain('factory');
  });
});

describe('specification run reasons (M4 slice 3)', () => {
  it.each(['spec_invalid', 'draft_run_failed', 'specification_written', 'draft_binding_mismatch'])('%s has a sentence', reason => {
    expect(workReason(reason)).not.toBe(reason);
  });
});

describe('plan run and revision reasons (M4 slice 3)', () => {
  it.each(['plan_invalid', 'plan_written', 'draft_setup_changed', 'revision_limit_reached', 'revise_unavailable'])(
    '%s has a sentence', reason => {
      expect(workReason(reason)).not.toBe(reason);
    });
});

describe('plan step reasons (M4 slice 3)', () => {
  it.each(['step_run_failed', 'step_checkpoint_lost', 'plan_produced_no_change'])('%s has a sentence', reason => {
    expect(workReason(reason)).not.toBe(reason);
  });
  it('no longer says a plan of steps cannot be built', () => {
    expect(workReason('plan_steps_unavailable')).toBe('plan_steps_unavailable');
  });
});
