import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  checkLlmProvider,
  createLlmProvider,
  deleteLlmModel,
  deleteLlmProvider,
  fetchLlmCatalog,
  fetchLlmModels,
  fetchLlmProviders,
  llmCatalogKey,
  llmDefaultModelKey,
  llmProvidersKey,
  type CatalogModelView,
  type CatalogRowView,
  updateLlmProvider,
  type LlmModelView,
  type LlmProviderInput,
  type LlmProviderView,
  type LlmType,
} from '../api';
import { sumRates } from '../llmPricing';

// Moved to llmPricing so the models table can use them without importing this page; re-exported for callers.
export { profileHint, ratesSummary, type RatesSummary } from '../llmPricing';
import { Plus } from 'lucide-react';
import IconButton from './IconButton';
import LastChecked from './LastCheckedBadge';
import Select from './Select';
import Tooltip from './Tooltip';
import SettingsLlmModelForm from './SettingsLlmModelForm';
import SettingsLlmCatalogHeader from './SettingsLlmCatalogHeader';
import SettingsLlmModelsTable, { type ModelRowActions } from './SettingsLlmModelsTable';
import LlmMakeDefaultDialog from './LlmMakeDefaultDialog';
import SettingsLlmDefaultModel from './SettingsLlmDefaultModel';
import { vendorModelOptions } from '../llmModelOptions';
import { catalogAsModel, catalogNames } from '../llmCatalog';
import { useEditDeepLink } from '../hooks/useEditDeepLink';
import { useEachRead } from '../hooks/useEachRead';
import { invalidateLive } from '../liveCache';
import { preparationKeys } from './work-items/workQueries';

/** A failed read as a sentence, or null while it has not failed. */
function readFailure(error: unknown): string | null {
  if (!error) return null;
  return error instanceof Error ? error.message : String(error);
}

/** One array each while nothing is read, so the deep link's effect does not re-run on every render. */
const NO_PROVIDERS: LlmProviderView[] = [];
const NO_MODELS: LlmModelView[] = [];
const NO_ROWS: CatalogRowView[] = [];

/** A delete waiting for its confirmation; "Use catalog" is a delete of the manual model that shadows an entry. */
interface ConfirmDelete {
  kind: 'provider' | 'model';
  id: string;
  name: string;
  useCatalog?: boolean;
}

// Phase 1: OpenAI only. Anthropic/Gemini land in phase 2.
const LLM_TYPES: LlmType[] = ['openai', 'anthropic', 'gemini'];
const DEFAULT_BASE_URLS: Record<string, string> = {
  openai: 'https://api.openai.com/v1',
  anthropic: 'https://api.anthropic.com/v1',
  gemini: 'https://generativelanguage.googleapis.com/v1beta',
};

/** The default API base URL for a provider type ('' when unknown). */
export function defaultBaseUrl(type: string): string {
  return DEFAULT_BASE_URLS[type] ?? '';
}

/** Models most-expensive-first by summed per-type rates; ties by label. An UNMETERED model's empty
 *  `rates` sums to 0 and sorts last — correctly, since it is the cheapest thing in the list. */
export function byExpenseDesc(models: LlmModelView[]): LlmModelView[] {
  return [...models].sort((a, b) => sumRates(b.rates) - sumRates(a.rates) || a.label.localeCompare(b.label));
}

// Per-provider connectivity status, keyed by provider id.
type ConnState = 'idle' | 'checking' | 'ok' | 'fail';
interface Conn {
  state: ConnState;
  detail?: string | null;
}

export default function SettingsLlmProviders() {
  const client = useQueryClient();
  const providersRead = useQuery({ queryKey: llmProvidersKey, queryFn: () => fetchLlmProviders() });
  // Shared with the preparation form and the build step: a model saved here reaches their model lists.
  const modelsRead = useQuery({ queryKey: preparationKeys.llmModels(), queryFn: () => fetchLlmModels() });
  const providers = providersRead.data ?? NO_PROVIDERS;
  const models = modelsRead.data ?? NO_MODELS;
  const providersLoading = providersRead.data === undefined && !providersRead.error;
  const modelsLoading = modelsRead.data === undefined && !modelsRead.error;
  const readError = providersRead.error ?? modelsRead.error;
  const [actionError, setActionError] = useState<string | null>(null);
  const error = actionError ?? (readError ? (readError instanceof Error ? readError.message : String(readError)) : null);
  // A cached list is shown at once but may be out of date: a row is edited or deleted only once this visit's reads
  // answered, and a deep link opens from them, so a save cannot send back a value someone else changed (PR 359, M-2).
  const current = providersRead.isFetchedAfterMount && providersRead.isSuccess
    && modelsRead.isFetchedAfterMount && modelsRead.isSuccess;
  const [providerForm, setProviderForm] = useState<'new' | LlmProviderView | null>(null);
  // An attention row names one provider; land the operator on it, not just on this page.
  useEditDeepLink(current ? providers : NO_PROVIDERS, setProviderForm);
  const [modelForm, setModelForm] = useState<'new' | LlmModelView | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<ConfirmDelete | null>(null);
  const [catalogDetail, setCatalogDetail] = useState<CatalogModelView | null>(null);
  const [defaultFor, setDefaultFor] = useState<LlmProviderView | null>(null);
  const catalogRead = useQuery({ queryKey: llmCatalogKey, queryFn: () => fetchLlmCatalog() });
  const catalogRows = catalogRead.data?.rows ?? NO_ROWS;
  const modelActions: ModelRowActions = {
    current,
    onEdit: setModelForm,
    onDelete: (m) => setConfirmDelete({ kind: 'model', id: m.id as string, name: m.label }),
    onUseCatalog: (m) => setConfirmDelete({ kind: 'model', id: m.id as string, name: m.name, useCatalog: true }),
    onOpenCatalog: setCatalogDetail,
  };
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

  // After each completed read, including one whose list is unchanged. A provider no longer enabled loses its live
  // result; the enabled ones are checked. A disabled one is intentionally inactive and may hold a deliberately
  // stale/revoked key.
  useEachRead<LlmProviderView[]>(llmProvidersKey, (list) => {
    const live = new Set(list.filter((p) => p.enabled).map((p) => p.id));
    setConns((prev) => Object.fromEntries(Object.entries(prev).filter(([id]) => live.has(id))));
    list.filter((p) => p.enabled).forEach((p) => void checkOne(p.id));
  });

  const load = () => Promise.all([
    invalidateLive(client, llmProvidersKey),
    invalidateLive(client, preparationKeys.llmModels()),
    invalidateLive(client, llmCatalogKey),
    invalidateLive(client, llmDefaultModelKey),
  ]);

  async function act(fn: () => Promise<unknown>) {
    setActionError(null);
    try {
      await fn();
      await load();
    } catch (err) {
      setActionError(err instanceof Error ? err.message : String(err));
    }
  }

  return (
    <section className="content">
      {error && (
        <div className="card" style={{ padding: '14px 18px', color: 'var(--crit)', fontSize: 13, marginBottom: 18 }}>
          {error}
        </div>
      )}

      <SettingsLlmDefaultModel providers={providers} models={models} />

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
        {!current && !error && providers.length > 0 && (
          <p className="prov-note" role="status">Showing the last list read. Editing opens once the current one answers.</p>
        )}
        {providersLoading ? (
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
                  <td>
                    {p.isDefault ? (
                      <span className="pill completed">
                        <span className="glyph"></span>
                        Default
                      </span>
                    ) : (
                      <button className="btn-ghost" disabled={!current} onClick={() => setDefaultFor(p)}>
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
                      <IconButton kind="edit" onClick={() => setProviderForm(p)} title="Edit" aria-label="Edit" disabled={!current} />
                      <IconButton
                        kind="delete"
                        disabled={!current}
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
        <SettingsLlmCatalogHeader catalog={catalogRead.data} failure={readFailure(catalogRead.error)}
          onRetry={() => void catalogRead.refetch()} />
        {modelsLoading ? (
          <div style={{ padding: '20px 18px', color: 'var(--text-3)', fontSize: 13 }}>Loading…</div>
        ) : models.length === 0 && catalogRows.length === 0 ? (
          <div style={{ padding: '20px 18px', color: 'var(--text-3)', fontSize: 13 }}>
            No models yet — add one (name + rate per 1M tokens, or mark it self-hosted) so reviews can
            be priced and providers can pick it.
          </div>
        ) : (
          <SettingsLlmModelsTable models={models} catalogRows={catalogRows} actions={modelActions} />
        )}
      </div>

      {providerForm && (
        <LlmProviderForm
          initial={providerForm === 'new' ? null : providerForm}
          models={models}
          hasEnabledDefault={providers.some((p) => p.isDefault && p.enabled)}
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
          catalogNames={catalogNames(catalogRows)}
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
            <h3>{confirmDelete.useCatalog ? `Use the catalog for “${confirmDelete.name}”?` : `Delete “${confirmDelete.name}”?`}</h3>
            <p style={{ color: 'var(--text-3)', fontSize: 13 }}>
              {confirmDelete.useCatalog
                ? 'This deletes the manual model. The catalog model with the same name takes its place, unless another manual model answers to one of its names; while it is in use that case is refused.'
                : 'This cannot be undone.'}
            </p>
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
                {confirmDelete.useCatalog ? 'Use catalog' : 'Delete'}
              </button>
            </div>
          </div>
        </div>
      )}

      {defaultFor && (
        <LlmMakeDefaultDialog
          provider={defaultFor}
          models={models}
          onClose={() => setDefaultFor(null)}
          onSaved={async () => {
            setDefaultFor(null);
            await load();
          }}
        />
      )}

      {catalogDetail && (
        <SettingsLlmModelForm initial={catalogAsModel(catalogDetail)} catalog={catalogDetail}
          onClose={() => setCatalogDetail(null)} onSaved={async () => setCatalogDetail(null)} />
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
  hasEnabledDefault,
  onClose,
  onSaved,
}: {
  initial: LlmProviderView | null;
  models: LlmModelView[];
  /** Replacing an enabled default needs the new default's model in the same request (the server refuses it otherwise). */
  hasEnabledDefault: boolean;
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const editing = initial !== null;
  const [name, setName] = useState(initial?.name ?? '');
  const [type, setType] = useState<LlmType>(initial?.type ?? 'openai');
  const [baseUrl, setBaseUrl] = useState(initial?.baseUrl ?? defaultBaseUrl('openai'));
  const [defaultModel, setDefaultModel] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [temperature, setTemperature] = useState(String(initial?.temperature ?? 0.2));
  const [maxTokens, setMaxTokens] = useState(initial?.maxTokens != null ? String(initial.maxTokens) : '');
  const [isDefault, setIsDefault] = useState(initial?.isDefault ?? false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const typeModels = vendorModelOptions(models, type);
  const modelRequired = !editing && isDefault && hasEnabledDefault;

  function chooseType(t: LlmType) {
    setType(t);
    setDefaultModel('');
    if (!baseUrl.trim() || Object.values(DEFAULT_BASE_URLS).includes(baseUrl)) {
      setBaseUrl(defaultBaseUrl(t));
    }
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const input: LlmProviderInput = {
      name: name.trim(),
      type,
      baseUrl: baseUrl.trim(),
      apiKey: apiKey.trim() || undefined,
      temperature: temperature.trim() === '' ? undefined : Number(temperature),
      maxTokens: maxTokens.trim() === '' ? null : Number(maxTokens),
      enabled: initial?.enabled ?? true,
      ...(editing ? {} : { isDefault, defaultModel: isDefault && defaultModel ? defaultModel : undefined }),
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
      <div className="modal tall" onClick={(e) => e.stopPropagation()}>
        <h3>{editing ? 'Edit LLM provider' : 'Add LLM provider'}</h3>
        <form className="modal-body" onSubmit={submit}>
          <label className="field">
            <span>Name</span>
            <input placeholder="OpenAI (prod)" value={name} onChange={(e) => setName(e.target.value)} />
          </label>

          <label className="field">
            <span>Type</span>
            <Select
              ariaLabel="Type"
              value={type}
              disabled={editing}
                options={LLM_TYPES.map((t) => ({ value: t, label: t }))}
              onChange={(v) => chooseType(v as LlmType)}
            />
            {editing && <small className="field-hint">A provider's type is fixed; add a new provider for another vendor.</small>}
          </label>

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

          {!editing && (
            <label className="field-check">
              <input type="checkbox" checked={isDefault} onChange={(e) => setIsDefault(e.target.checked)} />
              <span>Use as the default provider</span>
            </label>
          )}
          {!editing && isDefault && (
            <label className="field">
              <span>Default review model</span>
              <Select
                ariaLabel="Default review model"
                value={defaultModel}
                options={[{ value: '', label: '— select a model —' }, ...typeModels]}
                onChange={setDefaultModel}
              />
              <small className="field-hint">
                {typeModels.length === 0
                  ? `No enabled ${type} models — add one under Models first.`
                  : modelRequired
                    ? 'Required: it replaces the current default model, together with the default provider.'
                    : 'Written with the default provider; leave empty to choose it later.'}
              </small>
            </label>
          )}

          {error && <div className="modal-msg modal-error">{error}</div>}

          <div className="modal-actions">
            <button type="button" className="btn-ghost" onClick={onClose}>
              Cancel
            </button>
            <button type="submit" className="btn" disabled={busy || (modelRequired && defaultModel === '')}>
              {busy ? 'Saving…' : editing ? 'Save changes' : 'Add provider'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

