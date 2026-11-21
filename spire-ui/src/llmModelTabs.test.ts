import { describe, it, expect } from 'vitest';
import { chosenTab, modelTabs, rowsOfTab } from './llmModelTabs';
import { llmProvider } from './test/llmSettingsFixtures';

const PROVIDERS = [
  llmProvider({ id: 'TEST-p1', name: 'TEST-b-openai', type: 'openai', isDefault: false }),
  llmProvider({ id: 'TEST-p2', name: 'TEST-anthropic', type: 'anthropic', isDefault: true }),
  llmProvider({ id: 'TEST-p3', name: 'TEST-a-openai', type: 'openai', isDefault: false }),
];

const row = (type: string, label: string, manual: boolean) => ({ type, label, manual: manual ? {} : null });

describe('modelTabs', () => {
  it('makes one tab per vendor, joining the names of providers that share it', () => {
    expect(modelTabs(PROVIDERS).map((tab) => tab.label)).toContain('TEST-b-openai / TEST-a-openai');
    expect(modelTabs(PROVIDERS)).toHaveLength(2);
  });

  it('puts the default provider\'s vendor first, then the rest by label', () => {
    expect(modelTabs(PROVIDERS).map((tab) => tab.type)).toEqual(['anthropic', 'openai']);
    const noDefault = PROVIDERS.map((p) => ({ ...p, isDefault: false }));
    expect(modelTabs(noDefault).map((tab) => tab.type)).toEqual(['anthropic', 'openai']);
  });

  it('has no tab without a provider', () => {
    expect(modelTabs([])).toEqual([]);
    expect(chosenTab([], 'openai')).toBeNull();
  });
});

describe('chosenTab', () => {
  it('keeps the wanted tab, and falls back to the first when its vendor is gone', () => {
    const tabs = modelTabs(PROVIDERS);
    expect(chosenTab(tabs, 'openai')?.type).toBe('openai');
    expect(chosenTab(tabs, 'gemini')?.type).toBe('anthropic');
    expect(chosenTab(tabs, null)?.type).toBe('anthropic');
  });
});

describe('rowsOfTab', () => {
  it('lists only the vendor\'s rows, by label without case, a manual model before its catalog row', () => {
    const rows = [row('openai', 'beta', false), row('anthropic', 'Alpha', false), row('openai', 'Alpha', false),
      row('openai', 'alpha', true), row('gemini', 'Gamma', false)];

    expect(rowsOfTab(rows, 'openai')).toEqual([row('openai', 'alpha', true), row('openai', 'Alpha', false),
      row('openai', 'beta', false)]);
  });
});
