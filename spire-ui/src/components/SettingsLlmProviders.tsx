import { useEffect, useState } from 'react';
import {
  checkLlmProvider,
  createLlmProvider,
  deleteLlmModel,
  deleteLlmProvider,
  fetchLlmModels,
  fetchLlmProviders,
  setDefaultLlmProvider,
  updateLlmProvider,
  type LlmModelView,
  type LlmProviderInput,
  type LlmProviderView,
  type LlmType,
} from '../api';
import { formatCost } from '../money';
import { RATE_TYPES, TOKEN_TYPE_LABEL, sumRates } from '../llmPricing';
import { Plus } from 'lucide-react';
import IconButton from './IconButton';
import LastChecked from './LastCheckedBadge';
import Select from './Select';
import Tooltip from './Tooltip';
import SettingsLlmModelForm from './SettingsLlmModelForm';
import { useEditDeepLink } from '../hooks/useEditDeepLink';

// Phase 1: OpenAI only. Anthropic/Gemini land in phase 2.
const LLM_TYPES: LlmType[] = ['openai', 'anthropic', 'gemini'];
const DEFAULT_BASE_URLS: Record<string, string> = {
  openai: 'https://api.openai.com/v1',
  anthropic: 'https://api.anthropic.com/v1',
  gemini: 'https://generativelanguage.googleapis.com/v1beta',
};

/** A one-glance hint of a model's non-default API dialect, or '' when it's the classic one. */
export function profileHint(m: LlmModelView): string {
  const bits: string[] = [];
  if (m.outputTokenParam === 'MAX_COMPLETION_TOKENS') bits.push('max_completion_tokens');
  else if (m.outputTokenParam === 'NONE') bits.push('no cap');
  if (!m.supportsTemperature) bits.push('no temp');
  if (m.reasoningEffort) bits.push(`effort: ${m.reasoningEffort}`);
  return bits.join(' · ');
}

/** The default API base URL for a provider type ('' when unknown). */
export function defaultBaseUrl(type: string): string {
  return DEFAULT_BASE_URLS[type] ?? '';
}

/** Models most-expensive-first by summed per-type rates; ties by label. An UNMETERED model's empty
 *  `rates` sums to 0 and sorts last — correctly, since it is the cheapest thing in the list. */
export function byExpenseDesc(models: LlmModelView[]): LlmModelView[] {
  return [...models].sort((a, b) => sumRates(b.rates) - sumRates(a.rates) || a.label.localeCompare(b.label));
}

/** The catalog table's compact rate cell: "Self-hosted" for UNMETERED, else each priced dimension
 *  joined together — never "$0.00" for a self-hosted model, which would read as a priced free tier
 *  rather than as an operator's assertion that there is no per-token cost at all. */
export function ratesSummary(m: LlmModelView): string {
  if (m.pricingMode === 'UNMETERED') return 'Self-hosted';
  return RATE_TYPES
    .filter((t) => m.rates[t] != null)
    .map((t) => `${TOKEN_TYPE_LABEL[t]} ${formatCost(m.rates[t]!)}/1M`)
    .join(' · ');
}

// Per-provider connectivity status, keyed by provider id.
type ConnState = 'idle' | 'checking' | 'ok' | 'fail';
interface Conn {
  state: ConnState;
  detail?: string | null;
}

export default function SettingsLlmProviders() {
  const [providers, setProviders] = useState<LlmProviderView[]>([]);
  const [models, setModels] = useState<LlmModelView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [providerForm, setProviderForm] = useState<'new' | LlmProviderView | null>(null);
  // An attention row names one provider; land the operator on it, not just on this page.
  useEditDeepLink(providers, setProviderForm);
  const [modelForm, setModelForm] = useState<'new' | LlmModelView | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<{ kind: 'provider' | 'model'; id: string; name: string } | null>(
    null,
  );
  const [conns, setConns] = useState<Record<string, Conn>>({});

  async function checkOne(id: string) {
    setConns((prev) => ({ ...prev, [id]: { state: 'checking' } }));
    try {
      const r = await checkLlmProvider(id);
      setConns((prev) => ({
        ...prev,
        [id]: r.ok ? { state: 'ok' } : { state: 'fail', detail: r.detail },
      }));
    } catch (err) {
      setConns((prev) => ({
        ...prev,
        [id]: { state: 'fail', detail: err instanceof Error ? err.message : String(err) },
      }));
    }
  }

  async function load() {
    setLoading(true);
    setError(null);
    try {
      const [ps, ms] = await Promise.all([fetchLlmProviders(), fetchLlmModels()]);
      setProviders(ps);
      setModels(ms);
      // Check connectivity once on load, but only for enabled providers — a disabled one
      // is intentionally inactive and may hold a deliberately stale/revoked key.
      ps.filter((p) => p.enabled).forEach((p) => void checkOne(p.id));
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
      {error && (
        <div className="card" style={{ padding: '14px 18px', color: 'var(--crit)', fontSize: 13, marginBottom: 18 }}>
          {error}
        </div>
      )}

      {/* --- Providers --- */}
      <div className="card">
        <div className="head">
          <h3>Providers</h3>
          <span className="k">model connections</span>
          <Tooltip label="Add provider" className="tt-push">
            <button className="iconbtn" onClick={() => setProviderForm('new')} aria-label="Add provider">
              <Plus size={15} />
            </button>
          </Tooltip>
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
                <th>Connection</th>
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
                    <ConnCell conn={conns[p.id]} enabled={p.enabled} onRecheck={() => void checkOne(p.id)} />
                    <LastChecked item={p} />
                  </td>
                  <td>
                    <span className={`pill ${p.enabled ? 'completed' : 'cancelled'}`}>
                      <span className="glyph"></span>
                      {p.enabled ? 'Enabled' : 'Disabled'}
                    </span>
                  </td>
                  <td>
                    <div className="prov-actions">
                      <IconButton kind="edit" onClick={() => setProviderForm(p)} title="Edit" aria-label="Edit" />
                      <IconButton
                        kind="delete"
                        onClick={() => setConfirmDelete({ kind: 'provider', id: p.id, name: p.name })}
                        title="Delete"
                        aria-label="Delete"
                      />
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {/* --- Models catalog --- */}
      <div className="card" style={{ marginTop: 18 }}>
        <div className="head">
          <h3>Models</h3>
          <span className="k">catalog · pricing</span>
          <Tooltip label="Add model" className="tt-push">
            <button className="iconbtn" onClick={() => setModelForm('new')} aria-label="Add model">
              <Plus size={15} />
            </button>
          </Tooltip>
        </div>
        {loading && models.length === 0 ? (
          <div style={{ padding: '20px 18px', color: 'var(--text-3)', fontSize: 13 }}>Loading…</div>
        ) : models.length === 0 ? (
          <div style={{ padding: '20px 18px', color: 'var(--text-3)', fontSize: 13 }}>
            No models yet — add one (name + rate per 1M tokens, or mark it self-hosted) so reviews can
            be priced and providers can pick it.
          </div>
        ) : (
          <table className="prov-table">
            <thead>
              <tr>
                <th>Model</th>
                <th>Type</th>
                <th>Rates / 1M tokens</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {byExpenseDesc(models).map((m) => (
                <tr key={m.id}>
                  <td>
                    {m.label} <span className="mono" style={{ color: 'var(--text-3)', fontSize: 11 }}>{m.name}</span>
                    {profileHint(m) && <div className="prov-sub">{profileHint(m)}</div>}
                  </td>
                  <td className="mono">{m.type}</td>
                  <td className="mono">{ratesSummary(m)}</td>
                  <td>
                    <div className="prov-actions">
                      <IconButton kind="edit" onClick={() => setModelForm(m)} title="Edit" aria-label="Edit" />
                      <IconButton
                        kind="delete"
                        onClick={() => setConfirmDelete({ kind: 'model', id: m.id, name: m.label })}
                        title="Delete"
                        aria-label="Delete"
                      />
                    </div>
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
        <SettingsLlmModelForm
          initial={modelForm === 'new' ? null : modelForm}
          onClose={() => setModelForm(null)}
          onSaved={async () => {
            setModelForm(null);
            await load();
          }}
        />
      )}

      {confirmDelete && (
        <div className="modal-overlay">
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

function ConnCell({ conn, enabled, onRecheck }: { conn: Conn | undefined; enabled: boolean; onRecheck: () => void }) {
  // No stored result yet: an enabled provider is being auto-checked; a disabled
  // one was skipped on purpose and sits idle until the operator clicks to check.
  const state = conn?.state ?? (enabled ? 'checking' : 'idle');
  const label =
    state === 'idle'
      ? 'Not checked'
      : state === 'checking'
        ? 'Checking…'
        : state === 'ok'
          ? 'Connected'
          : 'Failed';
  const title =
    state === 'idle'
      ? 'Disabled — not checked automatically. Click to check anyway.'
      : state === 'checking'
        ? 'Contacting the provider…'
        : state === 'ok'
          ? 'Connected — click to re-check'
          : `${conn?.detail ?? 'Connection failed'} — click to re-check`;
  return (
    <div className="conn-cell">
      <button
        type="button"
        className={`conn conn-${state}`}
        onClick={onRecheck}
        disabled={state === 'checking'}
        title={title}
      >
        <span className="conn-dot" />
        <span className="conn-label">{label}</span>
      </button>
      {state === 'fail' && conn?.detail && <div className="conn-detail">{conn.detail}</div>}
    </div>
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
    <div className="modal-overlay">
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
              <Select
                ariaLabel="Type"
                value={type}
                options={LLM_TYPES.map((t) => ({ value: t, label: t }))}
                onChange={(v) => {
                  const t = v as LlmType;
                  setType(t);
                  if (!baseUrl.trim() || Object.values(DEFAULT_BASE_URLS).includes(baseUrl)) {
                    setBaseUrl(defaultBaseUrl(t));
                  }
                }}
              />
            </label>
            <label className="field">
              <span>Model</span>
              {typeModels.length > 0 ? (
                <Select
                  ariaLabel="Model"
                  value={model}
                  options={[
                    { value: '', label: '— select a model —' },
                    ...typeModels.map((m) => ({ value: m.name, label: `${m.label} (${m.name})` })),
                  ]}
                  onChange={setModel}
                />
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
              placeholder={defaultBaseUrl(type)}
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

