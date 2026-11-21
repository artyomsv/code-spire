import { useState } from 'react';
import { setDefaultLlmProvider, type LlmModelView, type LlmProviderView } from '../api';
import { vendorModelOptions } from '../llmModelOptions';
import Select from './Select';
import SettingField from './SettingField';
import SidePanel from './SidePanel';

interface Props {
  provider: LlmProviderView;
  models: LlmModelView[];
  onClose: () => void;
  onSaved: () => Promise<void>;
}

/** The model chosen in the panel and its save, which sends the provider and the model in one request. */
function useMakeDefault(provider: LlmProviderView, onSaved: () => Promise<void>) {
  const [model, setModel] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  async function submit() {
    setBusy(true);
    setError(null);
    try {
      await setDefaultLlmProvider(provider.id, model);
      await onSaved();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }
  return { model, setModel, busy, error, submit };
}

function hintFor(provider: LlmProviderView, offered: number): string {
  const base = `The review model that becomes the default with ${provider.name}, one of its vendor's enabled models.`;
  return offered === 0 ? `${base} No enabled ${provider.type} models yet: add one under Models first.` : base;
}

/**
 * Making a provider the default asks for its model and sends both in one request, so no review sees the new
 * provider with the old default's model, of another vendor. Opens in the shared SidePanel, like every settings edit.
 */
export default function LlmMakeDefaultDialog({ provider, models, onClose, onSaved }: Props) {
  const { model, setModel, busy, error, submit } = useMakeDefault(provider, onSaved);
  const options = vendorModelOptions(models, provider.type);
  const actions = <>
    <button type="button" className="btn-ghost" onClick={onClose} disabled={busy}>Cancel</button>
    <button type="button" className="btn" disabled={busy || model === ''} onClick={() => void submit()}>
      {busy ? 'Saving…' : 'Make default'}
    </button>
  </>;
  return (
    <SidePanel title={`Make “${provider.name}” the default`} busy={busy} onClose={onClose} actions={actions}>
      <SettingField label="Default review model" hint={hintFor(provider, options.length)} scope={provider.name}>
        <Select ariaLabel="Default review model" value={model} onChange={setModel}
          options={[{ value: '', label: '— select a model —' }, ...options]} />
      </SettingField>
      {error && <div className="modal-msg modal-error">{error}</div>}
    </SidePanel>
  );
}
