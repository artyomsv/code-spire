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
