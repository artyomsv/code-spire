import type { LlmModelView, LlmType } from '../../api';
import { vendorModelOptions } from '../../llmModelOptions';
import SettingField from '../SettingField';

interface Props {
  /** The vendor of the provider the repository names. */
  providerType: LlmType | null;
  /** The model saved or picked; null until one is chosen. */
  value: string | null;
  /** The models read; undefined until the read answers. */
  models: LlmModelView[] | undefined;
  onChange: (model: string | null) => void;
}

/**
 * The model this repository's reviews use with the provider it names: that provider's vendor's enabled models. A
 * stored model that is switched off or no longer listed stays selected and labelled, so the select never shows a
 * choice the repository does not hold.
 */
export default function RepositoryReviewModel({ providerType, value, models, onChange }: Props) {
  const options = vendorModelOptions(models ?? [], providerType, value);
  return <SettingField label="Review model" scope="repository"
    hint="The model the provider above reviews with. Choosing another provider clears it; Save needs both.">
    <select aria-label="Review model" value={value ?? ''} onChange={e => onChange(e.target.value || null)}>
      <option value="">{models === undefined ? 'Loading models…' : '— select a model —'}</option>
      {options.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
    </select></SettingField>;
}
