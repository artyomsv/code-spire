// Synthetic model-catalog fixtures for component tests: TEST- names, rates 1, 2, 3 and 1970 dates only.
import type { CatalogModelView, CatalogRowView, LlmCatalogView } from '../api';

export function catalogModel(over: Partial<CatalogModelView> = {}): CatalogModelView {
  return {
    modelId: 'TEST-cat-a',
    provider: 'anthropic',
    displayName: 'TEST catalog model',
    aliases: [],
    lifecycle: { status: 'ACTIVE', deprecationDate: null, retirementDate: null, successor: null },
    contextWindow: 1,
    maxOutputTokens: 1,
    capabilities: { tools: true, structuredOutput: true, imageInput: false, promptCache: true },
    api: { outputTokenParam: 'MAX_TOKENS', temperature: true, reasoningEffort: null },
    pricing: {
      input: 1, output: 2, cacheRead: 3, cacheWrite: null, cacheWrite1h: null, reasoning: null, notBilled: [],
      sourceUrl: 'https://example.invalid/pricing', observedAt: '1970-01-01',
    },
    ...over,
  };
}

export function catalogRow(over: Partial<CatalogModelView> = {}, shadowed = false): CatalogRowView {
  return { model: catalogModel(over), shadowed };
}

export function catalogView(over: Partial<LlmCatalogView> = {}): LlmCatalogView {
  return {
    url: 'https://example.invalid/ai-catalog/v1/catalog.yaml',
    source: 'remote',
    sha256: 'abcdef0123456789',
    loadedAt: '1970-01-01T00:00:00Z',
    lastCheckedAt: '1970-01-01T00:00:00Z',
    lastSuccessAt: '1970-01-01T00:00:00Z',
    lastError: null,
    lastChange: null,
    rows: [],
    ...over,
  };
}
