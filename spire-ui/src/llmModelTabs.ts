import type { HarnessCredentialView, LlmProviderView, LlmType } from './api';

const VENDORS: readonly string[] = ['openai', 'anthropic', 'gemini'];

/** One tab of Settings → LLM → Models: a vendor type that has a registered API provider or harness key. */
export interface ModelTab {
  type: LlmType;
  /** The API providers of that type, by name, joined with " / "; "<type> (harness keys)" when it has only those. */
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
 * One tab per vendor type among the registered API providers and harness keys (a model belongs to a type, not to a
 * provider row): a vendor used only through a harness key, such as an Anthropic key for Claude Code, keeps its models
 * reachable. The default provider's type comes first, then the rest by label.
 */
export function modelTabs(providers: LlmProviderView[], credentials: HarnessCredentialView[] = []): ModelTab[] {
  const names = new Map<LlmType, string[]>();
  for (const provider of providers) names.set(provider.type, [...(names.get(provider.type) ?? []), provider.name]);
  const tabs: ModelTab[] = [...names.entries()].map(([type, list]) => ({ type, label: list.join(' / ') }));
  for (const type of new Set(credentials.map((credential) => credential.type))) {
    if (VENDORS.includes(type) && !names.has(type as LlmType)) {
      tabs.push({ type: type as LlmType, label: `${type} (harness keys)` });
    }
  }
  const defaultType = providers.find((p) => p.isDefault)?.type;
  return tabs.sort((a, b) => Number(b.type === defaultType) - Number(a.type === defaultType)
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
