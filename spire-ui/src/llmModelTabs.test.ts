import { describe, it, expect } from 'vitest';
import { chosenTab, modelTabs, rowsOfTab } from './llmModelTabs';
import type { HarnessCredentialView } from './api';
import { llmProvider } from './test/llmSettingsFixtures';

const PROVIDERS = [
  llmProvider({ id: 'TEST-p1', name: 'TEST-b-openai', type: 'openai', isDefault: false }),
  llmProvider({ id: 'TEST-p2', name: 'TEST-anthropic', type: 'anthropic', isDefault: true }),
  llmProvider({ id: 'TEST-p3', name: 'TEST-a-openai', type: 'openai', isDefault: false }),
];

const row = (type: string, name: string, label: string, manual: boolean) => ({ type, name, label, manual: manual ? {} : null });

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
    const rows = [row('openai', 'TEST-b', 'beta', false), row('anthropic', 'TEST-a', 'Alpha', false),
      row('openai', 'TEST-a', 'Alpha', false), row('openai', 'TEST-a', 'alpha', true), row('gemini', 'TEST-g', 'Gamma', false)];

    expect(rowsOfTab(rows, 'openai')).toEqual([row('openai', 'TEST-a', 'alpha', true),
      row('openai', 'TEST-a', 'Alpha', false), row('openai', 'TEST-b', 'beta', false)]);
  });

  it('keeps a manual model beside its catalog row when the manual label sorts elsewhere', () => {
    const rows = [row('anthropic', 'TEST-opus-5-5', 'TEST Opus 5.5', false), row('anthropic', 'TEST-opus-4-8', 'TEST Opus 4.8', true),
      row('anthropic', 'TEST-opus-5-5', 'TEST Opus  5.5', true)];

    expect(rowsOfTab(rows, 'anthropic').map((r) => `${r.name}:${r.manual ? 'manual' : 'catalog'}`)).toEqual([
      'TEST-opus-4-8:manual', 'TEST-opus-5-5:manual', 'TEST-opus-5-5:catalog']);
  });
});

const harnessKey = (type: string): HarnessCredentialView => ({
  id: `TEST-key-${type}`, label: 'TEST-key', type, baseUrl: 'https://example.invalid', enabled: true,
  rateLimitedUntil: null, rejectedAt: null, lastUsedAt: null,
});

describe('modelTabs with harness keys', () => {
  const OPENAI = [llmProvider({ id: 'TEST-p1', name: 'TEST-openai', type: 'openai', isDefault: true })];

  it('adds a tab for a vendor used only through harness keys', () => {
    expect(modelTabs(OPENAI, [harnessKey('anthropic')])).toEqual([
      { type: 'openai', label: 'TEST-openai' },
      { type: 'anthropic', label: 'anthropic (harness keys)' },
    ]);
  });

  it('adds no second tab for a vendor that has an API provider, and none for an unknown kind', () => {
    expect(modelTabs(OPENAI, [harnessKey('openai'), harnessKey('TEST-unknown')])).toEqual([
      { type: 'openai', label: 'TEST-openai' },
    ]);
  });
});
