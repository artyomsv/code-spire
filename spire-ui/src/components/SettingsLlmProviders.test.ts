import { describe, expect, it } from 'vitest';
import type { LlmModelView } from '../api';
import { byExpenseDesc, defaultBaseUrl, profileHint } from './SettingsLlmProviders';

describe('defaultBaseUrl', () => {
  it('returns the base URL for each supported type', () => {
    expect(defaultBaseUrl('openai')).toBe('https://api.openai.com/v1');
    expect(defaultBaseUrl('anthropic')).toBe('https://api.anthropic.com/v1');
    expect(defaultBaseUrl('gemini')).toBe('https://generativelanguage.googleapis.com/v1beta');
  });

  it('returns empty for an unknown type', () => {
    expect(defaultBaseUrl('cohere')).toBe('');
    expect(defaultBaseUrl('')).toBe('');
  });
});

describe('byExpenseDesc', () => {
  const model = (label: string, input: number, output: number): LlmModelView => ({
    id: label,
    type: 'openai',
    name: label,
    label,
    pricingMode: 'METERED',
    rates: { INPUT: input, OUTPUT: output },
    outputTokenParam: 'MAX_TOKENS',
    supportsTemperature: true,
    reasoningEffort: null,
    extraParams: {},
    enabled: true,
    createdAt: '2026-07-07T00:00:00Z',
  });

  it('orders most-expensive-first by summed rates', () => {
    const cheap = model('mini', 15_000, 60_000); // 75k total
    const mid = model('4o', 250_000, 1_000_000); // 1.25M total
    const dear = model('o1', 1_500_000, 6_000_000); // 7.5M total
    expect(byExpenseDesc([cheap, dear, mid]).map((m) => m.label)).toEqual(['o1', '4o', 'mini']);
  });

  it('does not mutate the input array', () => {
    const list = [model('a', 1, 1), model('b', 9, 9)];
    byExpenseDesc(list);
    expect(list.map((m) => m.label)).toEqual(['a', 'b']);
  });

  /**
   * An UNMETERED model carries no rates at all (the validator refuses one that does), so its
   * summed rate is 0 — the same value a never-priced model would sum to, and correctly the
   * cheapest thing in the list.
   */
  it('sorts an UNMETERED model last, since its empty rates sum to zero', () => {
    const priced = model('priced', 100, 200);
    const free: LlmModelView = { ...model('free', 0, 0), pricingMode: 'UNMETERED', rates: {} };
    expect(byExpenseDesc([free, priced]).map((m) => m.label)).toEqual(['priced', 'free']);
  });
});

describe('profileHint', () => {
  const base = (): LlmModelView => ({
    id: 'x',
    type: 'openai',
    name: 'x',
    label: 'x',
    pricingMode: 'METERED',
    rates: {},
    outputTokenParam: 'MAX_TOKENS',
    supportsTemperature: true,
    reasoningEffort: null,
    extraParams: {},
    enabled: true,
    createdAt: '2026-07-07T00:00:00Z',
  });

  it('is empty for the classic chat dialect', () => {
    expect(profileHint(base())).toBe('');
  });

  it('summarizes a reasoning model', () => {
    expect(
      profileHint({ ...base(), outputTokenParam: 'MAX_COMPLETION_TOKENS', supportsTemperature: false, reasoningEffort: 'medium' }),
    ).toBe('max_completion_tokens · no temp · effort: medium');
  });

  it('notes when no output cap is sent', () => {
    expect(profileHint({ ...base(), outputTokenParam: 'NONE' })).toBe('no cap');
  });
});
