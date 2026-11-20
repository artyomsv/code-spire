import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  fetchLlmDefaultModel,
  llmDefaultModelKey,
  putLlmDefaultModel,
  type LlmModelView,
  type LlmProviderView,
} from '../api';
import { writeLive } from '../liveCache';
import { vendorModelOptions } from '../llmModelOptions';
import Select from './Select';

interface Props {
  providers: LlmProviderView[];
  models: LlmModelView[];
}

/**
 * The default review model: what a review spends when its repository names no provider. Lists the enabled models of
 * the default provider's vendor; with no enabled default provider there is nothing to choose a model for.
 */
export default function SettingsLlmDefaultModel({ providers, models }: Props) {
  const client = useQueryClient();
  const read = useQuery({ queryKey: llmDefaultModelKey, queryFn: () => fetchLlmDefaultModel() });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const provider = providers.find((p) => p.isDefault && p.enabled) ?? null;
  const stored = read.data?.model ?? null;
  const options = vendorModelOptions(models, provider?.type ?? null, stored);

  async function choose(model: string) {
    setBusy(true);
    setError(null);
    try {
      writeLive(client, llmDefaultModelKey, await putLlmDefaultModel(model === '' ? null : model));
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="card" style={{ marginBottom: 18 }}>
      <div className="head">
        <h3>Default review model</h3>
        <span className="k">{provider ? `on ${provider.name}` : 'no default provider'}</span>
      </div>
      <div style={{ padding: '14px 18px' }}>
        <Select
          ariaLabel="Default review model"
          value={provider ? (stored ?? '') : ''}
          disabled={!provider || busy || !read.isSuccess}
          options={[{ value: '', label: provider ? '— select a model —' : 'Choose a default provider first' }, ...options]}
          onChange={(v) => void choose(v)}
        />
        {!provider && <small className="field-hint">Choose a default provider first.</small>}
        {error && <div className="modal-msg modal-error">{error}</div>}
      </div>
    </div>
  );
}
