import { useState } from 'react';
import { setDefaultLlmProvider, type LlmModelView, type LlmProviderView } from '../api';
import { vendorModelOptions } from '../llmModelOptions';
import Select from './Select';

interface Props {
  provider: LlmProviderView;
  models: LlmModelView[];
  onClose: () => void;
  onSaved: () => Promise<void>;
}

/**
 * Making a provider the default asks for its model and sends both in one request, so no review sees the new
 * provider with the old default's model, of another vendor.
 */
export default function LlmMakeDefaultDialog({ provider, models, onClose, onSaved }: Props) {
  const [model, setModel] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const options = vendorModelOptions(models, provider.type);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
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

  return (
    <div className="modal-overlay">
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h3>Make “{provider.name}” the default</h3>
        <form className="modal-body" onSubmit={submit}>
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
          <div className="modal-actions">
            <button type="button" className="btn-ghost" onClick={onClose}>
              Cancel
            </button>
            <button type="submit" className="btn" disabled={busy || model === ''}>
              {busy ? 'Saving…' : 'Make default'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
