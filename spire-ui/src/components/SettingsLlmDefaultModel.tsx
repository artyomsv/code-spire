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
import SettingField from './SettingField';

interface Props {
  providers: LlmProviderView[];
  models: LlmModelView[];
}

const HINT = 'What a review spends when its repository names no provider: one of the default provider\'s vendor\'s '
  + 'enabled models. With no enabled default provider there is nothing to choose a model for: choose a default '
  + 'provider first.';

/** The default model's read and its save; a failed read is kept apart from "no model chosen". */
function useDefaultModel() {
  const client = useQueryClient();
  const read = useQuery({ queryKey: llmDefaultModelKey, queryFn: () => fetchLlmDefaultModel() });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
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
  return { read, busy, error, choose };
}

/** A failed read says so and can be retried: it never looks like a default with no model. */
function ReadFailed({ failure, onRetry }: { failure: unknown; onRetry: () => void }) {
  return (
    <p className="prov-error" role="alert">
      Could not load the default review model: {failure instanceof Error ? failure.message : String(failure)}{' '}
      <button type="button" className="btn-ghost" onClick={onRetry}>Retry loading the default model</button>
    </p>
  );
}

/** The default review model: what a review spends when its repository names no provider. */
export default function SettingsLlmDefaultModel({ providers, models }: Props) {
  const { read, busy, error, choose } = useDefaultModel();
  const provider = providers.find((p) => p.isDefault && p.enabled) ?? null;
  const stored = read.data?.model ?? null;
  const options = vendorModelOptions(models, provider?.type ?? null, stored);
  return (
    <div className="card" style={{ marginBottom: 18 }}>
      <div className="head">
        <h3>Default review model</h3>
        <span className="k">{provider ? `on ${provider.name}` : 'no default provider'}</span>
      </div>
      <div style={{ padding: '14px 18px' }}>
        {read.error && <ReadFailed failure={read.error} onRetry={() => void read.refetch()} />}
        <SettingField label="Default review model" hint={HINT} scope="global default">
          <Select ariaLabel="Default review model" value={provider ? (stored ?? '') : ''}
            disabled={!provider || busy || read.data === undefined}
            options={[{ value: '', label: provider ? '— select a model —' : 'Choose a default provider first' }, ...options]}
            onChange={(v) => void choose(v)} />
        </SettingField>
        {error && <div className="modal-msg modal-error">{error}</div>}
      </div>
    </div>
  );
}
