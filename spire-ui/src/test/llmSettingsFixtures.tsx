import { render, screen } from '@testing-library/react';
import { vi } from 'vitest';
import { MemoryRouter } from 'react-router';
import SettingsLlmProviders from '../components/SettingsLlmProviders';
import * as api from '../api';
import { queryWrapper } from './queryWrapper';
import { catalogView } from './catalogFixtures';

/** Settings → LLM, inside a router and a fresh query cache unless one is passed. */
export const renderLlmSettings = (wrapper = queryWrapper()) =>
  render(
    <MemoryRouter>
      <SettingsLlmProviders />
    </MemoryRouter>,
    { wrapper },
  );

/** The reads every LLM settings test needs and does not assert: a catalog with no rows and no default model. */
export function stubLlmSettingsReads() {
  vi.spyOn(api, 'fetchLlmCatalog').mockResolvedValue(catalogView());
  vi.spyOn(api, 'fetchLlmDefaultModel').mockResolvedValue({ provider: null, model: null });
  vi.spyOn(api, 'fetchHarnessCredentials').mockResolvedValue([]);
}

export const llmProvider = (over: Partial<api.LlmProviderView> = {}): api.LlmProviderView => ({
  id: 'TEST-provider-1', name: 'TEST-provider', type: 'openai', baseUrl: 'https://llm.example.test/v1',
  temperature: 0.2, maxTokens: null, hasApiKey: true, enabled: true, isDefault: true,
  createdAt: '1970-01-01T00:00:00Z', lastCheckAt: null, lastCheckOk: null, lastCheckError: null, ...over,
});

export const manualModel = (over: Partial<api.LlmModelView> = {}): api.LlmModelView => ({
  id: 'TEST-manual-id', type: 'openai', name: 'TEST-manual', label: 'TEST manual', pricingMode: 'METERED',
  rates: { INPUT: 1, OUTPUT: 2 }, notBilled: [], outputTokenParam: 'MAX_TOKENS', supportsTemperature: true,
  reasoningEffort: null, extraParams: {}, enabled: true, createdAt: '1970-01-01T00:00:00Z', source: 'MANUAL', ...over,
});

/** The table row holding this text. */
export const rowOf = async (text: string) => (await screen.findByText(text)).closest('tr') as HTMLElement;
