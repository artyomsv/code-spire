import { describe, expect, it } from 'vitest';
import type { LlmModelView } from '../api';
import { byExpenseDesc, defaultBaseUrl } from './SettingsLlmProviders';

describe('defaultBaseUrl', () => {
  it('returns the OpenAI base URL for openai', () => {
    expect(defaultBaseUrl('openai')).toBe('https://api.openai.com/v1');
  });

  it('returns empty for an unknown type', () => {
    expect(defaultBaseUrl('anthropic')).toBe('');
    expect(defaultBaseUrl('')).toBe('');
  });
});

describe('byExpenseDesc', () => {
  const model = (label: string, input: number, output: number): LlmModelView => ({
    id: label,
    type: 'openai',
    name: label,
    label,
    inputPriceMillicentsPerMillion: input,
    outputPriceMillicentsPerMillion: output,
    enabled: true,
    createdAt: '2026-07-07T00:00:00Z',
  });

  it('orders most-expensive-first by combined price', () => {
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
});
