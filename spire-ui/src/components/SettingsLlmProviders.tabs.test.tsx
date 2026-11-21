import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act } from 'react';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import { createQueryClient } from '../queryClient';
import { invalidateLive } from '../liveCache';
import * as api from '../api';
import { catalogRow, catalogView } from '../test/catalogFixtures';
import { llmProvider, manualModel, renderLlmSettings, stubLlmSettingsReads } from '../test/llmSettingsFixtures';
import SettingsLlmProviders from './SettingsLlmProviders';

const PROVIDERS = [
  llmProvider({ id: 'TEST-p1', name: 'TEST-openai-a', type: 'openai', isDefault: true }),
  llmProvider({ id: 'TEST-p2', name: 'TEST-openai-b', type: 'openai', isDefault: false }),
  llmProvider({ id: 'TEST-p3', name: 'TEST-anthropic', type: 'anthropic', isDefault: false }),
];

const MODELS = [
  manualModel({ id: 'TEST-m1', name: 'TEST-o-b', label: 'beta openai' }),
  manualModel({ id: 'TEST-m2', name: 'TEST-o-a', label: 'Alpha openai' }),
  manualModel({ id: 'TEST-m3', name: 'TEST-g', label: 'TEST gemini', type: 'gemini' }),
];

beforeEach(() => {
  stubLlmSettingsReads();
  vi.spyOn(api, 'fetchLlmProviders').mockResolvedValue(PROVIDERS);
  vi.spyOn(api, 'fetchLlmModels').mockResolvedValue(MODELS);
  vi.spyOn(api, 'fetchLlmCatalog').mockResolvedValue(catalogView({ rows: [catalogRow()] }));
  vi.spyOn(api, 'checkLlmProvider').mockResolvedValue({ ok: true, detail: null });
});

const tabList = async () => screen.findByRole('tablist', { name: 'Models by provider' });
/** The first cell of each row of the models table (the one holding this model), in order. */
const modelNames = (shown: string) => within(screen.getByText(shown).closest('table') as HTMLElement).getAllByRole('row')
  .slice(1).map((r) => within(r).getAllByRole('cell')[0].textContent);

describe('SettingsLlmProviders — models by provider', () => {
  it('makes one tab per registered vendor, the default provider\'s first and selected', async () => {
    renderLlmSettings();
    const tabs = within(await tabList()).getAllByRole('tab');

    expect(tabs.map((tab) => tab.textContent)).toEqual(['TEST-openai-a / TEST-openai-b', 'TEST-anthropic']);
    expect(tabs[0]).toHaveAttribute('aria-selected', 'true');
  });

  it('a tab lists only its vendor\'s models, by label; a vendor with no provider is not shown', async () => {
    renderLlmSettings();
    await tabList();
    await screen.findByText('TEST-o-a');

    expect(modelNames('TEST-o-a')).toEqual([expect.stringContaining('Alpha openai'), expect.stringContaining('beta openai')]);
    expect(screen.queryByText('TEST-g')).not.toBeInTheDocument();
    fireEvent.click(within(await tabList()).getByRole('tab', { name: 'TEST-anthropic' }));
    expect(await screen.findByRole('button', { name: 'Catalog model TEST-cat-a' })).toBeInTheDocument();
    expect(screen.queryByText('TEST-o-a')).not.toBeInTheDocument();
  });

  it('with no provider at all, says to register one instead of showing tabs', async () => {
    vi.mocked(api.fetchLlmProviders).mockResolvedValue([]);
    renderLlmSettings();

    expect(await screen.findByText(/Register a provider first/)).toBeInTheDocument();
    expect(screen.queryByRole('tablist', { name: 'Models by provider' })).not.toBeInTheDocument();
  });

  it('keeps the chosen tab across a re-read', async () => {
    const client = createQueryClient();
    render(<MemoryRouter><QueryClientProvider client={client}><SettingsLlmProviders /></QueryClientProvider></MemoryRouter>);
    fireEvent.click(within(await tabList()).getByRole('tab', { name: 'TEST-anthropic' }));

    await act(async () => { await invalidateLive(client, api.llmProvidersKey); });

    await waitFor(() => expect(within(screen.getByRole('tablist', { name: 'Models by provider' }))
      .getByRole('tab', { name: 'TEST-anthropic' })).toHaveAttribute('aria-selected', 'true'));
    expect(vi.mocked(api.fetchLlmProviders).mock.calls.length).toBeGreaterThan(1);
  });

  it('Add model starts with the chosen tab\'s vendor', async () => {
    renderLlmSettings();
    fireEvent.click(within(await tabList()).getByRole('tab', { name: 'TEST-anthropic' }));

    fireEvent.click(screen.getByRole('button', { name: /add model/i }));

    const dialog = await screen.findByRole('dialog', { name: 'Add model' });
    expect(within(dialog).getByRole('combobox', { name: 'Type' })).toHaveTextContent('anthropic');
  });
});

describe('SettingsLlmProviders — a vendor used only through harness keys', () => {
  const harnessKey = (type: string): api.HarnessCredentialView => ({
    id: `TEST-key-${type}`, label: 'TEST-key', type, baseUrl: 'https://example.invalid', enabled: true,
    rateLimitedUntil: null, rejectedAt: null, lastUsedAt: null,
  });

  beforeEach(() => {
    vi.mocked(api.fetchLlmProviders).mockResolvedValue([PROVIDERS[0]]);
    vi.mocked(api.fetchLlmModels).mockResolvedValue([
      manualModel({ id: 'TEST-m4', name: 'TEST-anth', label: 'TEST anthropic manual', type: 'anthropic' }),
    ]);
  });

  it('gets its own tab, where its manual model keeps Edit and Delete', async () => {
    vi.spyOn(api, 'fetchHarnessCredentials').mockResolvedValue([harnessKey('anthropic')]);
    renderLlmSettings();

    fireEvent.click(within(await tabList()).getByRole('tab', { name: 'anthropic (harness keys)' }));

    const row = (await screen.findByText('TEST-anth')).closest('tr') as HTMLElement;
    await waitFor(() => expect(within(row).getByRole('button', { name: 'Edit' })).toBeEnabled());
    expect(within(row).getByRole('button', { name: 'Delete' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /add model/i }));
    const dialog = await screen.findByRole('dialog', { name: 'Add model' });
    expect(within(dialog).getByRole('combobox', { name: 'Type' })).toHaveTextContent('anthropic');
  });

  it('a vendor with neither an API provider nor a harness key stays hidden', async () => {
    vi.spyOn(api, 'fetchHarnessCredentials').mockResolvedValue([]);
    renderLlmSettings();

    const tabs = within(await tabList()).getAllByRole('tab');
    expect(tabs.map((tab) => tab.textContent)).toEqual(['TEST-openai-a']);
  });

  it('a failed read of the harness keys still shows the API-provider tabs, says so, and retries', async () => {
    const keys = vi.spyOn(api, 'fetchHarnessCredentials').mockRejectedValue(new Error('TEST-keys down'));
    renderLlmSettings();

    const tabs = within(await tabList()).getAllByRole('tab');
    expect(tabs.map((tab) => tab.textContent)).toEqual(['TEST-openai-a']);
    const notice = await screen.findByText(/Harness keys could not be loaded/);
    expect(notice).toHaveTextContent('TEST-keys down');
    expect(notice).toHaveTextContent('models of vendors used only through them are hidden');

    keys.mockResolvedValue([harnessKey('anthropic')]);
    fireEvent.click(screen.getByRole('button', { name: 'Retry loading the harness keys' }));

    fireEvent.click(await within(await tabList()).findByRole('tab', { name: 'anthropic (harness keys)' }));
    const row = (await screen.findByText('TEST-anth')).closest('tr') as HTMLElement;
    await waitFor(() => expect(within(row).getByRole('button', { name: 'Edit' })).toBeEnabled());
    expect(within(row).getByRole('button', { name: 'Delete' })).toBeInTheDocument();
    expect(screen.queryByText(/Harness keys could not be loaded/)).not.toBeInTheDocument();
  });
});
