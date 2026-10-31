import type { LlmProviderView } from '../../api';
import SettingField from '../SettingField';

interface Props {
  /** The provider the repository names; null is the global default. */
  value: string | null;
  providers: LlmProviderView[];
  onChange: (id: string | null) => void;
}

/**
 * Which LLM provider this repository's reviews and replies use (harness reviews, decision 1). Only enabled
 * providers are offered, but the one the repository already names is always shown, marked when switched
 * off: hiding it would display the default and a save would silently change who pays.
 */
export default function RepositoryReviewProvider({ value, providers, onChange }: Props) {
  const fallback = providers.find(p => p.isDefault && p.enabled);
  const offered = providers.filter(p => p.enabled || p.id === value);
  // Before the list arrives, the stored id still has an option, so the select never shows a choice it does not hold.
  const unlisted = value !== null && !providers.some(p => p.id === value) ? value : null;
  return <SettingField label="Review with" scope="repository"
    hint="The LLM provider this repository's reviews and replies use. A provider that is switched off refuses them; it never falls back to the default.">
    <select aria-label="Review with" value={value ?? ''} onChange={e => onChange(e.target.value || null)}>
      <option value="">{fallback ? `Global default (${fallback.name})` : 'Global default (none set)'}</option>
      {offered.map(p => <option key={p.id} value={p.id}>{p.enabled ? p.name : `${p.name} (switched off)`}</option>)}
      {unlisted !== null && <option value={unlisted}>{unlisted}</option>}
    </select></SettingField>;
}
