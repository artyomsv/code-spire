import { useEffect, useState } from 'react';
import {
  createLlmProvider,
  deleteLlmProvider,
  fetchLlmProviders,
  setDefaultLlmProvider,
  updateLlmProvider,
  type LlmProviderInput,
  type LlmProviderView,
  type LlmType,
} from '../api';

// Phase 1: OpenAI only. Anthropic/Gemini land in phase 2.
const LLM_TYPES: LlmType[] = ['openai'];
const DEFAULT_BASE_URLS: Record<string, string> = {
  openai: 'https://api.openai.com/v1',
};

/** The default API base URL for a provider type ('' when unknown). */
export function defaultBaseUrl(type: string): string {
  return DEFAULT_BASE_URLS[type] ?? '';
}

export default function SettingsLlmProviders() {
  const [providers, setProviders] = useState<LlmProviderView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [form, setForm] = useState<'new' | LlmProviderView | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<LlmProviderView | null>(null);

  async function load() {
    setLoading(true);
    setError(null);
    try {
      setProviders(await fetchLlmProviders());
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

  return (
    <section className="content">
      <div className="page-head">
        <div className="grow"></div>
        <button className="btn" onClick={() => setForm('new')}>
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none">
            <path d="M8 3v10M3 8h10" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
          </svg>
          Add LLM provider
        </button>
      </div>

      <div className="card">
        {error ? (
          <div style={{ padding: '26px 18px', color: 'var(--crit)', fontSize: 13 }}>{error}</div>
        ) : loading && providers.length === 0 ? (
          <div style={{ padding: '26px 18px', color: 'var(--text-3)', fontSize: 13 }}>Loading…</div>
        ) : providers.length === 0 ? (
          <div className="prov-empty">
            <span>No LLM providers yet — add one so reviews have a model to run.</span>
            <button className="btn" onClick={() => setForm('new')}>
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none">
                <path d="M8 3v10M3 8h10" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
              </svg>
              Add LLM provider
            </button>
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
                    <button className="btn-ghost" onClick={() => setForm(p)}>
                      Edit
                    </button>
                    <button className="btn-ghost" onClick={() => setConfirmDelete(p)}>
                      Delete
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {form && (
        <LlmProviderForm
          initial={form === 'new' ? null : form}
          onClose={() => setForm(null)}
          onSaved={async () => {
            setForm(null);
            await load();
          }}
        />
      )}

      {confirmDelete && (
        <div className="modal-overlay" onClick={() => setConfirmDelete(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <h3>Delete “{confirmDelete.name}”?</h3>
            <p style={{ color: 'var(--text-3)', fontSize: 13 }}>
              Reviews will fall back to the default provider. This cannot be undone.
            </p>
            <div className="modal-actions">
              <button className="btn-ghost" onClick={() => setConfirmDelete(null)}>
                Cancel
              </button>
              <button
                className="btn btn-danger"
                onClick={() => {
                  const id = confirmDelete.id;
                  setConfirmDelete(null);
                  void act(() => deleteLlmProvider(id));
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
  onClose,
  onSaved,
}: {
  initial: LlmProviderView | null;
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
              <input className="mono" placeholder="gpt-4o" value={model} onChange={(e) => setModel(e.target.value)} />
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
