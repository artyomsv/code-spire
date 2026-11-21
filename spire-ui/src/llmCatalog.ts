// How the model catalog reads on screen: status strings, its names, its prices. Shared by the Settings
// table, its header and the catalog model's read-only form, so the wording is written once.
import type { CatalogModelView, CatalogRowView, LlmModelView } from './api';
import { RATE_TYPES, TOKEN_TYPE_LABEL, formatRate, type RateType } from './llmPricing';

/** A catalog model's prices by ledger type, as the pricer charges them (cacheWrite1h is shown apart). */
export const CATALOG_RATE_FIELDS: { type: RateType; field: 'input' | 'cacheRead' | 'cacheWrite' | 'output' | 'reasoning' }[] = [
  { type: 'INPUT', field: 'input' },
  { type: 'CACHED_INPUT', field: 'cacheRead' },
  { type: 'CACHE_WRITE', field: 'cacheWrite' },
  { type: 'OUTPUT', field: 'output' },
  { type: 'REASONING', field: 'reasoning' },
];

/** Every name the catalog answers to: each model id and alias. A manual model with one of them shadows it. */
export function catalogNames(rows: CatalogRowView[]): Set<string> {
  return new Set(rows.flatMap((row) => [row.model.modelId, ...row.model.aliases]));
}

/** The status column of a catalog row. */
export function catalogStatus(row: CatalogRowView): string {
  if (row.shadowed) return 'Ignored · a manual model has this name';
  const lifecycle = row.model.lifecycle;
  if (lifecycle?.status === 'RETIRED') return 'Retired';
  if (lifecycle?.status === 'DEPRECATED') {
    return lifecycle.successor ? `Deprecated · successor ${lifecycle.successor}` : 'Deprecated';
  }
  return 'Active';
}

/** One price as the detail shows it: a rate, "not billed", or "not priced" (a run reporting it is refused). */
export function catalogPrice(model: CatalogModelView, type: RateType): string {
  const pricing = model.pricing;
  const field = CATALOG_RATE_FIELDS.find((entry) => entry.type === type)?.field;
  const rate = pricing && field ? pricing[field] : null;
  if (rate != null) return `${formatRate(rate)}/1M`;
  if (pricing?.notBilled.includes(type)) return 'not billed';
  return 'not priced';
}

/** The table's compact rate cell for a catalog row. */
export function catalogRatesText(model: CatalogModelView): string {
  const parts = CATALOG_RATE_FIELDS
    .filter(({ field }) => model.pricing?.[field] != null)
    .map(({ type, field }) => `${TOKEN_TYPE_LABEL[type]} ${formatRate(model.pricing![field] as number)}/1M`);
  return parts.length > 0 ? parts.join(' · ') : 'Not priced';
}

/** The first seven characters of a sha, as git shows one. */
export function shortSha(sha: string | null): string {
  return sha ? sha.slice(0, 7) : '—';
}

/**
 * A catalog entry in the shape the model form reads, so a catalog model opens in the same dialog as a manual one
 * (read-only). No `api` means the form's own defaults; a type with no price and no "not billed" stays blank.
 */
export function catalogAsModel(model: CatalogModelView): LlmModelView {
  const rates: Partial<Record<RateType, number>> = {};
  for (const { type, field } of CATALOG_RATE_FIELDS) {
    const rate = model.pricing?.[field];
    if (rate != null) rates[type] = rate;
  }
  const notBilled = RATE_TYPES.filter((type) => model.pricing?.notBilled.includes(type));
  return {
    id: null, type: model.provider, name: model.modelId, label: model.displayName, pricingMode: 'METERED', rates,
    notBilled, outputTokenParam: model.api?.outputTokenParam ?? 'MAX_TOKENS',
    supportsTemperature: model.api?.temperature ?? true, reasoningEffort: model.api?.reasoningEffort ?? null,
    extraParams: {}, enabled: model.lifecycle?.status !== 'RETIRED', createdAt: null, source: 'CATALOG',
  };
}
