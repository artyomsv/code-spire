import { describe, expect, it } from 'vitest';
import { workReason, workRefusal } from './workReasons';

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
