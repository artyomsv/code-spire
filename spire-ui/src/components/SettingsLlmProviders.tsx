import { useEffect, useState } from 'react';
import {
  createLlmModel,
  createLlmProvider,
  deleteLlmModel,
  deleteLlmProvider,
  fetchLlmModels,
  fetchLlmProviders,
  setDefaultLlmProvider,
  updateLlmModel,
  updateLlmProvider,
  type LlmModelInput,
  type LlmModelView,
  type LlmProviderInput,
  type LlmProviderView,
  type LlmType,
} from '../api';
import { dollarsToMillicentsPerMillion, millicentsPerMillionToDollars } from '../money';

// Phase 1: OpenAI only. Anthropic/Gemini land in phase 2.
const LLM_TYPES: LlmType[] = ['openai'];
const DEFAULT_BASE_URLS: Record<string, string> = {
  openai: 'https://api.openai.com/v1',
};

/** The default API base URL for a provider type ('' when unknown). */
export function defaultBaseUrl(type: string): string {
  return DEFAULT_BASE_URLS[type] ?? '';
}

/** Models most-expensive-first by combined input+output price per 1M tokens; ties by label. */
export function byExpenseDesc(models: LlmModelView[]): LlmModelView[] {
  return [...models].sort((a, b) => {
    const ea = a.inputPriceMillicentsPerMillion + a.outputPriceMillicentsPerMillion;
    const eb = b.inputPriceMillicentsPerMillion + b.outputPriceMillicentsPerMillion;
    return eb - ea || a.label.localeCompare(b.label);
  });
}

export default function SettingsLlmProviders() {
  const [providers, setProviders] = useState<LlmProviderView[]>([]);
  const [models, setModels] = useState<LlmModelView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [providerForm, setProviderForm] = useState<'new' | LlmProviderView | null>(null);
  const [modelForm, setModelForm] = useState<'new' | LlmModelView | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<{ kind: 'provider' | 'model'; id: string; name: string } | null>(
    null,
  );

  async function load() {
    setLoading(true);
    setError(null);
    try {
      const [ps, ms] = await Promise.all([fetchLlmProviders(), fetchLlmModels()]);
      setProviders(ps);
      setModels(ms);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  async function act(fn: () => Promise<unknown>) {
    setError(null);
    try {
      await fn();
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  }

  function priceLabel(mc: number): string {
    return `$${millicentsPerMillionToDollars(mc).toFixed(2)}`;
  }

  return (
    <section className="content">
      {error && (
        <div className="card" style={{ padding: '14px 18px', color: 'var(--crit)', fontSize: 13, marginBottom: 18 }}>
          {error}
        </div>
      )}

      {/* --- Models catalog --- */}
      <div className="card">
        <div className="head">
          <h3>Models</h3>
          <span className="k">catalog · pricing</span>
          <button className="btn" style={{ marginLeft: 'auto' }} onClick={() => setModelForm('new')}>
            Add model
          </button>
        </div>
        {loading && models.length === 0 ? (
          <div style={{ padding: '20px 18px', color: 'var(--text-3)', fontSize: 13 }}>Loading…</div>
        ) : models.length === 0 ? (
          <div style={{ padding: '20px 18px', color: 'var(--text-3)', fontSize: 13 }}>
            No models yet — add one (name + price per 1M tokens) so reviews can be priced and providers can pick it.
          </div>
        ) : (
          <table className="prov-table">
            <thead>
              <tr>
                <th>Model</th>
                <th>Type</th>
                <th className="cell-r">Input / 1M</th>
                <th className="cell-r">Output / 1M</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {byExpenseDesc(models).map((m) => (
                <tr key={m.id}>
                  <td>
                    {m.label} <span className="mono" style={{ color: 'var(--text-3)', fontSize: 11 }}>{m.name}</span>
                  </td>
                  <td className="mono">{m.type}</td>
                  <td className="cell-r mono">{priceLabel(m.inputPriceMillicentsPerMillion)}</td>
                  <td className="cell-r mono">{priceLabel(m.outputPriceMillicentsPerMillion)}</td>
                  <td className="prov-actions">
                    <button className="btn-ghost" onClick={() => setModelForm(m)}>
                      Edit
                    </button>
                    <button
                      className="btn-ghost"
                      onClick={() => setConfirmDelete({ kind: 'model', id: m.id, name: m.label })}
                    >
                      Delete
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {/* --- Providers --- */}
      <div className="card" style={{ marginTop: 18 }}>
        <div className="head">
          <h3>Providers</h3>
          <button className="btn" style={{ marginLeft: 'auto' }} onClick={() => setProviderForm('new')}>
            Add provider
          </button>
        </div>
        {loading && providers.length === 0 ? (
          <div style={{ padding: '20px 18px', color: 'var(--text-3)', fontSize: 13 }}>Loading…</div>
        ) : providers.length === 0 ? (
          <div style={{ padding: '20px 18px', color: 'var(--text-3)', fontSize: 13 }}>
            No LLM providers yet — add one so reviews have a model to run.
          </div>
        ) : (
          <table className="prov-table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Type</th>
                <th>Model</th>
                <th>Default</th>
                <th>Status</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {providers.map((p) => (
                <tr key={p.id}>
                  <td>{p.name}</td>
                  <td className="mono">{p.type}</td>
                  <td className="mono">{p.model}</td>
                  <td>
                    {p.isDefault ? (
                      <span className="pill completed">
                        <span className="glyph"></span>
                        Default
                      </span>
                    ) : (
                      <button className="btn-ghost" onClick={() => act(() => setDefaultLlmProvider(p.id))}>
                        Set default
                      </button>
                    )}
                  </td>
                  <td>
                    <span className={`pill ${p.enabled ? 'completed' : 'cancelled'}`}>
                      <span className="glyph"></span>
                      {p.enabled ? 'Enabled' : 'Disabled'}
                    </span>
                  </td>
                  <td className="prov-actions">
                    <button className="btn-ghost" onClick={() => setProviderForm(p)}>
                      Edit
                    </button>
                    <button
                      className="btn-ghost"
                      onClick={() => setConfirmDelete({ kind: 'provider', id: p.id, name: p.name })}
                    >
                      Delete
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {providerForm && (
        <LlmProviderForm
          initial={providerForm === 'new' ? null : providerForm}
          models={models}
          onClose={() => setProviderForm(null)}
          onSaved={async () => {
            setProviderForm(null);
            await load();
          }}
        />
      )}

      {modelForm && (
        <LlmModelForm
          initial={modelForm === 'new' ? null : modelForm}
          onClose={() => setModelForm(null)}
          onSaved={async () => {
            setModelForm(null);
            await load();
          }}
        />
      )}

      {confirmDelete && (
        <div className="modal-overlay" onClick={() => setConfirmDelete(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <h3>Delete “{confirmDelete.name}”?</h3>
            <p style={{ color: 'var(--text-3)', fontSize: 13 }}>This cannot be undone.</p>
            <div className="modal-actions">
              <button className="btn-ghost" onClick={() => setConfirmDelete(null)}>
                Cancel
              </button>
              <button
                className="btn btn-danger"
                onClick={() => {
                  const { kind, id } = confirmDelete;
                  setConfirmDelete(null);
                  void act(() => (kind === 'model' ? deleteLlmModel(id) : deleteLlmProvider(id)));
                }}
              >
                Delete
              </button>
            </div>
          </div>
        </div>
      )}
    </section>
  );
}

function LlmProviderForm({
  initial,
  models,
  onClose,
  onSaved,
}: {
  initial: LlmProviderView | null;
  models: LlmModelView[];
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const editing = initial !== null;
  const [name, setName] = useState(initial?.name ?? '');
  const [type, setType] = useState<LlmType>(initial?.type ?? 'openai');
  const [baseUrl, setBaseUrl] = useState(initial?.baseUrl ?? defaultBaseUrl('openai'));
  const [model, setModel] = useState(initial?.model ?? '');
  const [apiKey, setApiKey] = useState('');
  const [temperature, setTemperature] = useState(String(initial?.temperature ?? 0.2));
  const [maxTokens, setMaxTokens] = useState(initial?.maxTokens != null ? String(initial.maxTokens) : '');
  const [isDefault, setIsDefault] = useState(initial?.isDefault ?? false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const typeModels = byExpenseDesc(models.filter((m) => m.type === type && m.enabled));

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const input: LlmProviderInput = {
      name: name.trim(),
      type,
      baseUrl: baseUrl.trim(),
      model: model.trim(),
      apiKey: apiKey.trim() || undefined,
      temperature: temperature.trim() === '' ? undefined : Number(temperature),
      maxTokens: maxTokens.trim() === '' ? null : Number(maxTokens),
      enabled: initial?.enabled ?? true,
      isDefault,
    };
    try {
      if (editing && initial) {
        await updateLlmProvider(initial.id, input);
      } else {
        await createLlmProvider(input);
      }
      await onSaved();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h3>{editing ? 'Edit LLM provider' : 'Add LLM provider'}</h3>
        <form className="modal-body" onSubmit={submit}>
          <label className="field">
            <span>Name</span>
            <input placeholder="OpenAI (prod)" value={name} onChange={(e) => setName(e.target.value)} />
          </label>

          <div className="field-row-2">
            <label className="field">
              <span>Type</span>
              <select
                value={type}
                onChange={(e) => {
                  const t = e.target.value as LlmType;
                  setType(t);
                  if (!baseUrl.trim() || Object.values(DEFAULT_BASE_URLS).includes(baseUrl)) {
                    setBaseUrl(defaultBaseUrl(t));
                  }
                }}
              >
                {LLM_TYPES.map((t) => (
                  <option key={t} value={t}>
                    {t}
                  </option>
                ))}
              </select>
            </label>
            <label className="field">
              <span>Model</span>
              {typeModels.length > 0 ? (
                <select value={model} onChange={(e) => setModel(e.target.value)}>
                  <option value="">— select a model —</option>
                  {typeModels.map((m) => (
                    <option key={m.id} value={m.name}>
                      {m.label} ({m.name})
                    </option>
                  ))}
                </select>
              ) : (
                <input
                  className="mono"
                  placeholder="gpt-4o"
                  value={model}
                  onChange={(e) => setModel(e.target.value)}
                />
              )}
              {typeModels.length === 0 && (
                <small className="field-hint">No models registered — add one above to price reviews.</small>
              )}
            </label>
          </div>

          <label className="field">
            <span>Base URL</span>
            <input
              className="mono"
              placeholder={defaultBaseUrl('openai')}
              value={baseUrl}
              onChange={(e) => setBaseUrl(e.target.value)}
            />
          </label>

          <label className="field">
            <span>API key</span>
            <input
              type="password"
              autoComplete="new-password"
              placeholder={editing ? 'leave blank to keep current' : 'sk-…'}
              value={apiKey}
              onChange={(e) => setApiKey(e.target.value)}
            />
            {editing && (
              <small className="field-hint">
                {initial?.hasApiKey ? 'A key is stored — leave blank to keep it.' : 'No key stored yet.'}
              </small>
            )}
          </label>

          <div className="field-row-2">
            <label className="field">
              <span>
                Temperature <span className="field-optional">0–2</span>
              </span>
              <input
                className="mono"
                inputMode="decimal"
                value={temperature}
                onChange={(e) => setTemperature(e.target.value)}
              />
            </label>
            <label className="field">
              <span>
                Max tokens <span className="field-optional">optional</span>
              </span>
              <input
                className="mono"
                inputMode="numeric"
                placeholder="default"
                value={maxTokens}
                onChange={(e) => setMaxTokens(e.target.value)}
              />
            </label>
          </div>

          <label className="field-check">
            <input type="checkbox" checked={isDefault} onChange={(e) => setIsDefault(e.target.checked)} />
            <span>Use as the default review model</span>
          </label>

          {error && <div className="modal-msg modal-error">{error}</div>}

          <div className="modal-actions">
            <button type="button" className="btn-ghost" onClick={onClose}>
              Cancel
            </button>
            <button type="submit" className="btn" disabled={busy}>
              {busy ? 'Saving…' : editing ? 'Save changes' : 'Add provider'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

function LlmModelForm({
  initial,
  onClose,
  onSaved,
}: {
  initial: LlmModelView | null;
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const editing = initial !== null;
  const [type, setType] = useState<LlmType>(initial?.type ?? 'openai');
  const [name, setName] = useState(initial?.name ?? '');
  const [label, setLabel] = useState(initial?.label ?? '');
  const [inputPrice, setInputPrice] = useState(
    initial ? String(millicentsPerMillionToDollars(initial.inputPriceMillicentsPerMillion)) : '',
  );
  const [outputPrice, setOutputPrice] = useState(
    initial ? String(millicentsPerMillionToDollars(initial.outputPriceMillicentsPerMillion)) : '',
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const input: LlmModelInput = {
      type,
      name: name.trim(),
      label: label.trim() || name.trim(),
      inputPriceMillicentsPerMillion: dollarsToMillicentsPerMillion(Number(inputPrice) || 0),
      outputPriceMillicentsPerMillion: dollarsToMillicentsPerMillion(Number(outputPrice) || 0),
      enabled: initial?.enabled ?? true,
    };
    try {
      if (editing && initial) {
        await updateLlmModel(initial.id, input);
      } else {
        await createLlmModel(input);
      }
      await onSaved();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h3>{editing ? 'Edit model' : 'Add model'}</h3>
        <form className="modal-body" onSubmit={submit}>
          <div className="field-row-2">
            <label className="field">
              <span>Type</span>
              <select value={type} onChange={(e) => setType(e.target.value as LlmType)}>
                {LLM_TYPES.map((t) => (
                  <option key={t} value={t}>
                    {t}
                  </option>
                ))}
              </select>
            </label>
            <label className="field">
              <span>Model name</span>
              <input className="mono" placeholder="gpt-4o" value={name} onChange={(e) => setName(e.target.value)} />
            </label>
          </div>

          <label className="field">
            <span>
              Label <span className="field-optional">defaults to the model name</span>
            </span>
            <input placeholder="GPT-4o" value={label} onChange={(e) => setLabel(e.target.value)} />
          </label>

          <div className="field-row-2">
            <label className="field">
              <span>Input price $ / 1M tokens</span>
              <input
                className="mono"
                inputMode="decimal"
                placeholder="2.50"
                value={inputPrice}
                onChange={(e) => setInputPrice(e.target.value)}
              />
            </label>
            <label className="field">
              <span>Output price $ / 1M tokens</span>
              <input
                className="mono"
                inputMode="decimal"
                placeholder="10.00"
                value={outputPrice}
                onChange={(e) => setOutputPrice(e.target.value)}
              />
            </label>
          </div>
          <small className="field-hint">
            Enter the provider's current published price per 1M tokens — used to cost each review.
          </small>

          {error && <div className="modal-msg modal-error">{error}</div>}

          <div className="modal-actions">
            <button type="button" className="btn-ghost" onClick={onClose}>
              Cancel
            </button>
            <button type="submit" className="btn" disabled={busy}>
              {busy ? 'Saving…' : editing ? 'Save changes' : 'Add model'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
