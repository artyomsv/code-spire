import type { LlmModelView, LlmType } from './api';

/** One choice in a model select. */
export interface ModelOption {
  value: string;
  label: string;
}

/**
 * The enabled models (catalog and manual) of one vendor, as select options. A stored choice that is switched off or
 * not listed stays visible and selected, labelled, so the operator sees what reviews use and can choose again.
 */
export function vendorModelOptions(models: LlmModelView[], type: LlmType | null, stored?: string | null): ModelOption[] {
  if (!type) return [];
  const options = models
    .filter((m) => m.type === type && m.enabled)
    // A label never reaches here empty from the catalog (the validator requires it); a manual row falls back to its name.
    .sort((a, b) => (a.label || a.name).localeCompare(b.label || b.name))
    .map((m) => ({ value: m.name, label: `${m.label || m.name} (${m.name})` }));
  if (stored && !options.some((o) => o.value === stored)) {
    const known = models.find((m) => m.name === stored);
    const why = known && !known.enabled ? 'switched off' : known ? `a ${known.type} model` : 'not listed';
    options.unshift({ value: stored, label: `${stored} (${why})` });
  }
  return options;
}
