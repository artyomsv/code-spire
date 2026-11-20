import { useState } from 'react';
import { setDefaultLlmProvider, type LlmModelView, type LlmProviderView } from '../api';
import { vendorModelOptions } from '../llmModelOptions';
import Select from './Select';
import SidePanel from './SidePanel';

interface Props {
  provider: LlmProviderView;
  models: LlmModelView[];
  onClose: () => void;
  onSaved: () => Promise<void>;
}

/**
 * Making a provider the default asks for its model and sends both in one request, so no review sees the new
 * provider with the old default's model, of another vendor. Opens in the shared SidePanel, like every settings edit.
 */
export default function LlmMakeDefaultDialog({ provider, models, onClose, onSaved }: Props) {
  const [model, setModel] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const options = vendorModelOptions(models, provider.type);

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

  const actions = (
    <>
      <button type="button" className="btn-ghost" onClick={onClose} disabled={busy}>
        Cancel
      </button>
      <button type="button" className="btn" disabled={busy || model === ''} onClick={() => void submit()}>
        {busy ? 'Saving…' : 'Make default'}
      </button>
    </>
  );

  return (
    <SidePanel title={`Make “${provider.name}” the default`} busy={busy} onClose={onClose} actions={actions}>
      <label className="field">
        <span>Default review model</span>
        <Select
          ariaLabel="Default review model"
          value={model}
          options={[{ value: '', label: '— select a model —' }, ...options]}
          onChange={setModel}
        />
        {options.length === 0 && (
          <small className="field-hint">No enabled {provider.type} models — add one under Models first.</small>
        )}
      </label>
      {error && <div className="modal-msg modal-error">{error}</div>}
    </SidePanel>
  );
}
