import { describe, expect, it } from 'vitest';
import type { LlmModelView } from '../api';
import { RATE_TYPES, unpricedTypesFor, type RateType } from '../llmPricing';
import { initialNotBilled, initialRates, notBilledPayload, validateRates } from './SettingsLlmModelRateFields';

/**
 * A token type has three states, and the form has to keep them apart (M3.5 part D).
 *
 * A rate is a price somebody read off a vendor's page. "The vendor does not bill this" is a person
 * asserting a zero. A blank field is nobody having said — and a run that reports that type stops the
 * item with an unknown cost, which is what happened to work item 36. The form used to call a blank
 * field "not billed", which is the confusion these tests exist to prevent coming back.
 */
const blankRates = () => Object.fromEntries(RATE_TYPES.map(type => [type, ''])) as Record<RateType, string>;
const noAssertions = () => Object.fromEntries(RATE_TYPES.map(type => [type, false])) as Record<RateType, boolean>;

const model = (rates: Partial<Record<RateType, number>>, notBilled: RateType[] = [],
               pricingMode: 'METERED' | 'UNMETERED' = 'METERED'): LlmModelView => ({
  id: 'TEST-model-id', type: 'openai', name: 'TEST-model', label: 'TEST model', pricingMode, rates, notBilled,
  outputTokenParam: 'MAX_TOKENS', supportsTemperature: true, reasoningEffort: null, extraParams: {},
  enabled: true, createdAt: '2026-09-16T00:00:00Z',
});

describe('the not-billed assertion', () => {
  it('round-trips from a saved model into the form', () => {
    expect(initialNotBilled(model({ INPUT: 100 }, ['CACHED_INPUT'])).CACHED_INPUT).toBe(true);
    expect(initialNotBilled(model({ INPUT: 100 }, ['CACHED_INPUT'])).REASONING).toBe(false);
    // And a saved assertion is not read back as a rate of zero.
    expect(initialRates(model({ INPUT: 100 }, ['CACHED_INPUT'])).CACHED_INPUT).toBe('');
  });

  it('is sent only for the optional types, and never under a self-hosted model', () => {
    const asserted = { ...noAssertions(), CACHED_INPUT: true, INPUT: true };
    expect(notBilledPayload('METERED', asserted)).toEqual(['CACHED_INPUT']);
    expect(notBilledPayload('UNMETERED', asserted)).toEqual([]);
  });

  it('refuses a type that carries both a rate and the assertion', () => {
    const rates = { ...blankRates(), INPUT: '2.50', OUTPUT: '10.00', CACHED_INPUT: '0.30' };
    expect(validateRates('METERED', rates, { ...noAssertions(), CACHED_INPUT: true }))
      .toContain('both a rate and "not billed"');
  });

  it('refuses an assertion on a type every vendor charges for', () => {
    const rates = { ...blankRates(), INPUT: '2.50', OUTPUT: '10.00' };
    expect(validateRates('METERED', rates, { ...noAssertions(), INPUT: true }))
      .toContain('charged by every vendor');
  });

  it('accepts an assertion in place of a rate for an optional type', () => {
    const rates = { ...blankRates(), INPUT: '2.50', OUTPUT: '10.00' };
    expect(validateRates('METERED', rates, { ...noAssertions(), CACHED_INPUT: true, REASONING: true })).toBeNull();
  });
});

describe('what a harness run needs priced', () => {
  const codex = ['INPUT', 'CACHED_INPUT', 'CACHE_WRITE', 'OUTPUT', 'REASONING'];

  it('names every reported type the model cannot price', () => {
    expect(unpricedTypesFor(model({ INPUT: 100, OUTPUT: 200 }), codex))
      .toEqual(['CACHED_INPUT', 'CACHE_WRITE', 'REASONING']);
  });

  it('counts an assertion as priced, because the operator said what it costs', () => {
    expect(unpricedTypesFor(model({ INPUT: 100, OUTPUT: 200, CACHED_INPUT: 50 }, ['CACHE_WRITE', 'REASONING']), codex))
      .toEqual([]);
  });

  /** The discriminating case: the same model, and only the harness's own list decides. */
  it('asks only about what this harness reports', () => {
    expect(unpricedTypesFor(model({ INPUT: 100, OUTPUT: 200 }), ['INPUT', 'OUTPUT'])).toEqual([]);
  });

  it('assumes the two every vendor reports when the server named none', () => {
    expect(unpricedTypesFor(model({ INPUT: 100 }), undefined)).toEqual(['OUTPUT']);
  });

  it('asks nothing of a self-hosted model, whose zero is asserted for the whole model', () => {
    expect(unpricedTypesFor(model({}, [], 'UNMETERED'), codex)).toEqual([]);
  });
});
