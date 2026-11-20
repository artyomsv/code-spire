import { describe, it, expect, vi } from 'vitest';
import { screen, fireEvent, waitFor } from '@testing-library/react';
import { renderWithQuery } from '../test/queryWrapper';
import SettingsLlmDefaultModel from './SettingsLlmDefaultModel';
import * as api from '../api';

const provider = (over: Partial<api.LlmProviderView> = {}): api.LlmProviderView => ({
  id: 'TEST-provider-1', name: 'TEST-provider', type: 'openai', baseUrl: 'https://llm.example.test/v1',
  temperature: 0.2, maxTokens: null, hasApiKey: true, enabled: true, isDefault: true,
  createdAt: '1970-01-01T00:00:00Z', lastCheckAt: null, lastCheckOk: null, lastCheckError: null, ...over,
});

const model = (over: Partial<api.LlmModelView> = {}): api.LlmModelView => ({
  id: 'TEST-model-id', type: 'openai', name: 'TEST-o', label: 'TEST openai', pricingMode: 'METERED',
  rates: { INPUT: 1, OUTPUT: 2 }, notBilled: [], outputTokenParam: 'MAX_TOKENS', supportsTemperature: true,
  reasoningEffort: null, extraParams: {}, enabled: true, createdAt: '1970-01-01T00:00:00Z', source: 'MANUAL', ...over,
});

const MODELS = [model(), model({ id: 'TEST-a-id', type: 'anthropic', name: 'TEST-a', label: 'TEST anthropic' })];

describe('SettingsLlmDefaultModel', () => {
  it('the default model select lists only the default provider\'s vendor', async () => {
    vi.spyOn(api, 'fetchLlmDefaultModel').mockResolvedValue({ provider: 'TEST-provider-1', model: null });
    renderWithQuery(<SettingsLlmDefaultModel providers={[provider()]} models={MODELS} />);
    const select = await screen.findByRole('combobox', { name: 'Default review model' });
    await waitFor(() => expect(select).toBeEnabled());

    fireEvent.click(select);

    expect(await screen.findByRole('option', { name: 'TEST openai (TEST-o)' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'TEST anthropic (TEST-a)' })).not.toBeInTheDocument();
  });

  it('the default model select is disabled without a default provider', async () => {
    vi.spyOn(api, 'fetchLlmDefaultModel').mockResolvedValue({ provider: null, model: null });
    renderWithQuery(<SettingsLlmDefaultModel providers={[provider({ isDefault: false })]} models={MODELS} />);

    const select = await screen.findByRole('combobox', { name: 'Default review model' });
    expect(select).toBeDisabled();
    expect(select).toHaveTextContent('Choose a default provider first');
  });

  it('choosing a model saves only the model', async () => {
    vi.spyOn(api, 'fetchLlmDefaultModel').mockResolvedValue({ provider: 'TEST-provider-1', model: null });
    const put = vi.spyOn(api, 'putLlmDefaultModel').mockResolvedValue({ provider: 'TEST-provider-1', model: 'TEST-o' });
    renderWithQuery(<SettingsLlmDefaultModel providers={[provider()]} models={MODELS} />);
    const select = await screen.findByRole('combobox', { name: 'Default review model' });
    await waitFor(() => expect(select).toBeEnabled());

    fireEvent.click(select);
    fireEvent.click(await screen.findByRole('option', { name: 'TEST openai (TEST-o)' }));

    await waitFor(() => expect(put).toHaveBeenCalledWith('TEST-o'));
  });

  it('a stored switched-off model stays selected and labelled', async () => {
    vi.spyOn(api, 'fetchLlmDefaultModel').mockResolvedValue({ provider: 'TEST-provider-1', model: 'TEST-off' });
    const models = [...MODELS, model({ id: 'TEST-off-id', name: 'TEST-off', label: 'TEST off', enabled: false })];
    renderWithQuery(<SettingsLlmDefaultModel providers={[provider()]} models={models} />);

    expect(await screen.findByRole('combobox', { name: 'Default review model' })).toHaveTextContent('TEST-off (switched off)');
  });
});

describe('SettingsLlmDefaultModel — a failed read', () => {
  it('says the read failed and offers a retry, instead of an empty disabled select', async () => {
    const read = vi.spyOn(api, 'fetchLlmDefaultModel').mockRejectedValue(new Error('TEST-settings down'));
    renderWithQuery(<SettingsLlmDefaultModel providers={[provider()]} models={MODELS} />);

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load the default review model: TEST-settings down');
    expect(screen.getByRole('combobox', { name: 'Default review model' })).toBeDisabled();
    read.mockResolvedValue({ provider: 'TEST-provider-1', model: 'TEST-o' });
    fireEvent.click(screen.getByRole('button', { name: 'Retry loading the default model' }));

    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
    expect(screen.getByRole('combobox', { name: 'Default review model' })).toHaveTextContent('TEST openai (TEST-o)');
  });
});
