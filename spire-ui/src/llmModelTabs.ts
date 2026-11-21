import type { LlmProviderView, LlmType } from './api';

/** One tab of Settings → LLM → Models: a vendor type that has a registered provider. */
export interface ModelTab {
  type: LlmType;
  /** The providers of that type, by name, joined with " / ". */
  label: string;
}

/** What a tab filters and sorts: a row's vendor type, model name, label, and whether it is a manual model. */
export interface TabbedRow {
  type: string;
  name: string;
  label: string;
  manual: unknown;
}

/**
 * One tab per vendor type among the registered providers (a model belongs to a type, not to a provider row). The
 * default provider's type comes first, then the rest by label.
 */
export function modelTabs(providers: LlmProviderView[]): ModelTab[] {
  const names = new Map<LlmType, string[]>();
  for (const provider of providers) names.set(provider.type, [...(names.get(provider.type) ?? []), provider.name]);
  const defaultType = providers.find((p) => p.isDefault)?.type;
  return [...names.entries()]
    .map(([type, list]) => ({ type, label: list.join(' / ') }))
    .sort((a, b) => Number(b.type === defaultType) - Number(a.type === defaultType)
      || a.label.localeCompare(b.label, undefined, { sensitivity: 'base' }));
}

/** The tab a wanted type names, or the first tab when it names none (missing, or its providers are gone). */
export function chosenTab(tabs: ModelTab[], wanted: string | null): ModelTab | null {
  return tabs.find((tab) => tab.type === wanted) ?? tabs[0] ?? null;
}

/**
 * A tab's rows: only its type, grouped by model name so a manual model sits right before the catalog row it shadows.
 * Groups sort by label (case-insensitive), taking the catalog's label when the group has one: a manual label typed
 * differently (an extra space, another word) must not split the pair.
 */
export function rowsOfTab<T extends TabbedRow>(rows: T[], type: LlmType): T[] {
  const own = rows.filter((row) => row.type === type);
  const groupLabel = new Map<string, string>();
  for (const row of own) {
    if (!row.manual || !groupLabel.has(row.name)) groupLabel.set(row.name, row.label);
  }
  const labelOf = (row: T) => groupLabel.get(row.name) ?? row.label;
  return own.sort((a, b) => labelOf(a).localeCompare(labelOf(b), undefined, { sensitivity: 'base' })
    || a.name.localeCompare(b.name)
    || Number(!a.manual) - Number(!b.manual));
}
