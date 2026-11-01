import type { LlmProviderView } from '../../api';
import SettingField from '../SettingField';

/** The "Review with" value that means a harness, not an API provider (harness reviews, step 5). */
export const HARNESS_CHOICE = 'harness';

interface Props {
  /** The provider the repository names; null is the global default. Ignored while `harness` is on. */
  value: string | null;
  /** The repository reviews on a harness: its fields are shown beside this select, by the settings tab. */
  harness: boolean;
  /** The providers read; undefined until the read answers. */
  providers: LlmProviderView[] | undefined;
  /** Why the read failed, when it did. A failed read is not an empty list (review of PR 365, M-1). */
  failure: string | null;
  onRetry: () => void;
  /** A provider id, null for the global default, or `HARNESS_CHOICE`. */
  onChange: (choice: string | null) => void;
}

/** The default's option names the default only once the list is read: "none set" is a claim about the list. */
function defaultLabel(providers: LlmProviderView[] | undefined, failure: string | null): string {
  if (providers === undefined) {
    return failure ? 'Global default' : 'Global default (loading…)';
  }
  const fallback = providers.find(p => p.isDefault && p.enabled);
  return fallback ? `Global default (${fallback.name})` : 'Global default (none set)';
}

/**
 * Which LLM provider this repository's reviews and replies use (harness reviews, decision 1), or a harness. Only
 * enabled providers are offered, but the one the repository already names is always shown, marked when switched
 * off: hiding it would display the default and a save would silently change who pays.
 */
export default function RepositoryReviewProvider({ value, harness, providers, failure, onRetry, onChange }: Props) {
  const list = providers ?? [];
  const offered = list.filter(p => p.enabled || p.id === value);
  // Before the list arrives, the stored id still has an option, so the select never shows a choice it does not hold.
  const unlisted = value !== null && !list.some(p => p.id === value) ? value : null;
  return <><SettingField label="Review with" scope="repository"
    hint="The LLM provider or the harness this repository's reviews use. One that is switched off or refused never falls back to another.">
    <select aria-label="Review with" value={harness ? HARNESS_CHOICE : value ?? ''} onChange={e => onChange(e.target.value || null)}>
      <option value="">{defaultLabel(providers, failure)}</option>
      {offered.map(p => <option key={p.id} value={p.id}>{p.enabled ? p.name : `${p.name} (switched off)`}</option>)}
      {unlisted !== null && <option value={unlisted}>{unlisted}</option>}
      <option value={HARNESS_CHOICE}>Harness</option>
    </select></SettingField>
    {failure && <p className="prov-error" role="alert">
      Could not load the LLM providers: {failure}{' '}
      <button type="button" className="btn-ghost" onClick={onRetry}>Retry loading LLM providers</button>
    </p>}</>;
}
