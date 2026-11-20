import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, fireEvent, waitFor, within } from '@testing-library/react';
import * as api from '../api';
import { llmProvider as provider, manualModel, renderLlmSettings as renderPage, stubLlmSettingsReads } from '../test/llmSettingsFixtures';

beforeEach(stubLlmSettingsReads);

describe('SettingsLlmProviders — default provider and model', () => {
  const openaiModel = manualModel({ id: 'TEST-o-id', name: 'TEST-o', label: 'TEST openai' });
  const anthropicModel = manualModel({ id: 'TEST-a-id', type: 'anthropic', name: 'TEST-a', label: 'TEST anthropic' });

  beforeEach(() => {
    vi.spyOn(api, 'fetchLlmModels').mockResolvedValue([openaiModel, anthropicModel]);
    vi.spyOn(api, 'checkLlmProvider').mockResolvedValue({ ok: true, detail: null });
  });

  it('the provider form has no model field', async () => {
    vi.spyOn(api, 'fetchLlmProviders').mockResolvedValue([]);
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Add provider' }));
    const form = (await screen.findByRole('heading', { name: 'Add LLM provider' })).closest('.modal') as HTMLElement;

    expect(within(form).queryByRole('combobox', { name: 'Model' })).not.toBeInTheDocument();
    expect(within(form).queryByRole('textbox', { name: 'Model' })).not.toBeInTheDocument();
    expect(within(form).getByLabelText('Use as the default provider')).toBeInTheDocument();
  });

  it('making another provider default asks for its model and sends both', async () => {
    vi.spyOn(api, 'fetchLlmProviders').mockResolvedValue([
      provider(),
      provider({ id: 'TEST-provider-2', name: 'TEST-anthropic', type: 'anthropic', isDefault: false }),
    ]);
    const setDefault = vi.spyOn(api, 'setDefaultLlmProvider').mockResolvedValue(provider());
    renderPage();
    const row = (await screen.findByText('TEST-anthropic')).closest('tr') as HTMLElement;
    await waitFor(() => expect(within(row).getByRole('button', { name: 'Set default' })).toBeEnabled());

    fireEvent.click(within(row).getByRole('button', { name: 'Set default' }));
    const dialog = await screen.findByRole('dialog', { name: /Make .TEST-anthropic. the default/ });
    expect(within(dialog).getByRole('button', { name: 'Make default' })).toBeDisabled();
    fireEvent.click(within(dialog).getByRole('combobox', { name: 'Default review model' }));
    expect(screen.queryByRole('option', { name: 'TEST openai (TEST-o)' })).not.toBeInTheDocument();
    fireEvent.click(await screen.findByRole('option', { name: 'TEST anthropic (TEST-a)' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Make default' }));

    await waitFor(() => expect(setDefault).toHaveBeenCalledWith('TEST-provider-2', 'TEST-a'));
  });
});

describe('SettingsLlmProviders — a new default provider', () => {
  const openaiModel = manualModel({ id: 'TEST-o-id', name: 'TEST-o', label: 'TEST openai' });

  beforeEach(() => {
    vi.spyOn(api, 'fetchLlmModels').mockResolvedValue([openaiModel]);
    vi.spyOn(api, 'checkLlmProvider').mockResolvedValue({ ok: true, detail: null });
  });

  async function openNewDefaultForm(): Promise<HTMLElement> {
    renderPage();
    await screen.findByText('TEST-provider');
    fireEvent.click(await screen.findByRole('button', { name: 'Add provider' }));
    const form = (await screen.findByRole('heading', { name: 'Add LLM provider' })).closest('.modal') as HTMLElement;
    fireEvent.click(within(form).getByLabelText('Use as the default provider'));
    return form;
  }

  it('replacing an enabled default requires its model before Save', async () => {
    vi.spyOn(api, 'fetchLlmProviders').mockResolvedValue([provider()]);
    const form = await openNewDefaultForm();

    expect(within(form).getByText(/Required: it replaces the current default model/)).toBeInTheDocument();
    expect(within(form).getByRole('button', { name: 'Add provider' })).toBeDisabled();
    fireEvent.click(within(form).getByRole('combobox', { name: 'Default review model' }));
    fireEvent.click(await screen.findByRole('option', { name: 'TEST openai (TEST-o)' }));
    expect(within(form).getByRole('button', { name: 'Add provider' })).toBeEnabled();
  });

  it('with no enabled default the model may be chosen later', async () => {
    vi.spyOn(api, 'fetchLlmProviders').mockResolvedValue([provider({ enabled: false })]);
    const form = await openNewDefaultForm();

    expect(within(form).getByText(/leave empty to choose it later/)).toBeInTheDocument();
    expect(within(form).getByRole('button', { name: 'Add provider' })).toBeEnabled();
  });
});

describe('SettingsLlmProviders — a failed default-model read', () => {
  it('says the default model failed to load while the providers still show', async () => {
    vi.spyOn(api, 'fetchLlmProviders').mockResolvedValue([provider()]);
    vi.spyOn(api, 'fetchLlmModels').mockResolvedValue([]);
    vi.spyOn(api, 'checkLlmProvider').mockResolvedValue({ ok: true, detail: null });
    vi.spyOn(api, 'fetchLlmDefaultModel').mockRejectedValue(new Error('TEST-settings down'));
    renderPage();

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load the default review model: TEST-settings down');
    expect(await screen.findByText('TEST-provider')).toBeInTheDocument();
  });
});
