import { useEffect, useState } from 'react';
import {
  checkContextProvider,
  createContextProvider,
  deleteContextProvider,
  fetchContextProviders,
  previewContextProvider,
  setDefaultContextProvider,
  updateContextProvider,
  type ContextAuthKind,
  type ContextPreviewResult,
  type ContextProviderInput,
  type ContextProviderView,
  type ContextType,
} from '../api';
import { Plus } from 'lucide-react';
import IconButton from './IconButton';

const CONTEXT_TYPES: ContextType[] = ['jira'];

// Per-provider connectivity status, keyed by provider id.
type ConnState = 'checking' | 'ok' | 'fail';
interface Conn {
  state: ConnState;
  account?: string | null;
  detail?: string | null;
}

export default function SettingsContextProviders() {
  const [providers, setProviders] = useState<ContextProviderView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [form, setForm] = useState<'new' | ContextProviderView | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<{ id: string; name: string } | null>(null);
  const [conns, setConns] = useState<Record<string, Conn>>({});
  const [testProvider, setTestProvider] = useState<ContextProviderView | null>(null);

  async function checkOne(id: string) {
    setConns((prev) => ({ ...prev, [id]: { state: 'checking' } }));
    try {
      const r = await checkContextProvider(id);
      setConns((prev) => ({
        ...prev,
        [id]: r.ok ? { state: 'ok', account: r.account } : { state: 'fail', detail: r.detail },
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
      const list = await fetchContextProviders();
      setProviders(list);
      // Check connectivity once on load — no continuous polling.
      list.forEach((p) => void checkOne(p.id));
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

      <div className="card">
        <div className="head">
          <h3>Context providers</h3>
          <span className="k">Jira · enrich reviews with linked-ticket context</span>
          <button
            className="iconbtn"
            style={{ marginLeft: 'auto' }}
            onClick={() => setForm('new')}
            aria-label="Add provider"
            title="Add provider"
          >
            <Plus size={15} />
          </button>
        </div>
        {loading && providers.length === 0 ? (
          <div style={{ padding: '20px 18px', color: 'var(--text-3)', fontSize: 13 }}>Loading…</div>
        ) : providers.length === 0 ? (
          <div style={{ padding: '20px 18px', color: 'var(--text-3)', fontSize: 13 }}>
            No context providers yet — add a Jira connection so reviews can pull the referenced ticket’s summary and
            description into the prompt.
          </div>
        ) : (
          <table className="prov-table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Type</th>
                <th>Base URL</th>
                <th>Connection</th>
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
                  <td className="mono">{p.baseUrl}</td>
                  <td>
                    <ConnCell conn={conns[p.id]} onRecheck={() => void checkOne(p.id)} />
                  </td>
                  <td>
                    {p.isDefault ? (
                      <span className="pill completed">
                        <span className="glyph"></span>
                        Default
                      </span>
                    ) : (
                      <button className="btn-ghost" onClick={() => act(() => setDefaultContextProvider(p.id))}>
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
                  <td>
                    <div className="prov-actions">
                      <IconButton
                        kind="test"
                        onClick={() => setTestProvider(p)}
                        title="Test connection & preview a ticket"
                        aria-label="Test"
                      />
                      <IconButton kind="edit" onClick={() => setForm(p)} title="Edit" aria-label="Edit" />
                      <IconButton
                        kind="delete"
                        onClick={() => setConfirmDelete({ id: p.id, name: p.name })}
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

      {form && (
        <ContextProviderForm
          initial={form === 'new' ? null : form}
          onClose={() => setForm(null)}
          onSaved={async () => {
            setForm(null);
            await load();
          }}
        />
      )}

      {testProvider && <PreviewModal provider={testProvider} onClose={() => setTestProvider(null)} />}

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
                  const { id } = confirmDelete;
                  setConfirmDelete(null);
                  void act(() => deleteContextProvider(id));
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

function ConnCell({ conn, onRecheck }: { conn: Conn | undefined; onRecheck: () => void }) {
  const state = conn?.state ?? 'checking';
  const label =
    state === 'checking'
      ? 'Checking…'
      : state === 'ok'
        ? (conn?.account ?? 'Connected')
        : 'Failed';
  const title =
    state === 'checking'
      ? 'Contacting the provider…'
      : state === 'ok'
        ? `Connected${conn?.account ? ` as ${conn.account}` : ''} — click to re-check`
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

function ContextProviderForm({
  initial,
  onClose,
  onSaved,
}: {
  initial: ContextProviderView | null;
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const editing = initial !== null;
  const [name, setName] = useState(initial?.name ?? '');
  const [type, setType] = useState<ContextType>(initial?.type ?? 'jira');
  const [baseUrl, setBaseUrl] = useState(initial?.baseUrl ?? '');
  const [authKind, setAuthKind] = useState<ContextAuthKind>(initial?.authKind ?? 'basic');
  const [username, setUsername] = useState(initial?.username ?? '');
  const [projectKeys, setProjectKeys] = useState(initial?.projectKeys ?? '');
  const [secret, setSecret] = useState('');
  const [isDefault, setIsDefault] = useState(initial?.isDefault ?? false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const input: ContextProviderInput = {
      name: name.trim(),
      type,
      baseUrl: baseUrl.trim(),
      authKind,
      username: authKind === 'basic' ? username.trim() : undefined,
      secret: secret.trim() || undefined,
      projectKeys: projectKeys.trim() || undefined,
      enabled: initial?.enabled ?? true,
      isDefault,
    };
    try {
      if (editing && initial) {
        await updateContextProvider(initial.id, input);
      } else {
        await createContextProvider(input);
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
        <h3>{editing ? 'Edit context provider' : 'Add context provider'}</h3>
        <form className="modal-body" onSubmit={submit}>
          <label className="field">
            <span>Name</span>
            <input placeholder="Acme Jira" value={name} onChange={(e) => setName(e.target.value)} />
          </label>

          <div className="field-row-2">
            <label className="field">
              <span>Type</span>
              <select value={type} onChange={(e) => setType(e.target.value as ContextType)}>
                {CONTEXT_TYPES.map((t) => (
                  <option key={t} value={t}>
                    {t}
                  </option>
                ))}
              </select>
            </label>
            <label className="field">
              <span>Auth</span>
              <select value={authKind} onChange={(e) => setAuthKind(e.target.value as ContextAuthKind)}>
                <option value="basic">basic · email + API token (Cloud)</option>
                <option value="bearer">bearer · personal access token (Data Center)</option>
              </select>
            </label>
          </div>

          <label className="field">
            <span>Base URL</span>
            <input
              className="mono"
              placeholder="https://acme.atlassian.net"
              value={baseUrl}
              onChange={(e) => setBaseUrl(e.target.value)}
            />
            <small className="field-hint">Your Jira site root — the client appends the REST paths.</small>
          </label>

          <label className="field">
            <span>
              Project keys <span className="field-optional">optional</span>
            </span>
            <input
              className="mono"
              placeholder="ACME, PROJ"
              value={projectKeys}
              onChange={(e) => setProjectKeys(e.target.value)}
            />
            <small className="field-hint">
              Only issue keys for these projects are looked up (e.g. ACME matches ACME-123). Leave blank
              to accept any key. Also lets the Test box resolve a bare ticket number.
            </small>
          </label>

          {authKind === 'basic' && (
            <label className="field">
              <span>Account email</span>
              <input placeholder="bot@acme.com" value={username} onChange={(e) => setUsername(e.target.value)} />
            </label>
          )}

          <label className="field">
            <span>{authKind === 'basic' ? 'API token' : 'Personal access token'}</span>
            <input
              type="password"
              autoComplete="new-password"
              placeholder={editing ? 'leave blank to keep current' : '••••••••'}
              value={secret}
              onChange={(e) => setSecret(e.target.value)}
            />
            {editing && (
              <small className="field-hint">
                {initial?.hasSecret ? 'A secret is stored — leave blank to keep it.' : 'No secret stored yet.'}
              </small>
            )}
          </label>

          <label className="field-check">
            <input type="checkbox" checked={isDefault} onChange={(e) => setIsDefault(e.target.checked)} />
            <span>Use as the default context source</span>
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

function PreviewModal({ provider, onClose }: { provider: ContextProviderView; onClose: () => void }) {
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<ContextPreviewResult | null>(null);

  async function run(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      setResult(await previewContextProvider(provider.id, text.trim()));
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  const hint = provider.projectKeys
    ? `a ticket number (${provider.projectKeys.split(/[,\s]+/)[0]}-123 or just 123) or a PR title`
    : 'a full ticket key (PROJ-123) or a PR title';

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h3>Test context — {provider.name}</h3>
        <form className="modal-body" onSubmit={run}>
          <label className="field">
            <span>Ticket or text</span>
            <input placeholder={hint} value={text} onChange={(e) => setText(e.target.value)} autoFocus />
            <small className="field-hint">
              Resolves the key with this provider’s pattern, fetches it live, and shows exactly what a review would
              inject.
            </small>
          </label>

          <div className="modal-actions" style={{ justifyContent: 'flex-start' }}>
            <button type="submit" className="btn" disabled={busy || !text.trim()}>
              {busy ? 'Fetching…' : 'Fetch preview'}
            </button>
          </div>

          {error && <div className="modal-msg modal-error">{error}</div>}

          {result && (
            <div className="ctx-preview">
              <div className="ctx-preview-meta">
                <span className={`pill ${result.status === 'OK' ? 'completed' : 'cancelled'}`}>
                  <span className="glyph"></span>
                  {result.status}
                </span>
                {result.keys.length > 0 && <span className="mono">{result.keys.join(', ')}</span>}
              </div>
              {result.detail && <div className="field-hint">{result.detail}</div>}
              {result.items.map((it, i) => (
                <div className="ctx-preview-item" key={i}>
                  <div className="ctx-preview-title">
                    {it.uri ? (
                      <a href={it.uri} target="_blank" rel="noreferrer">
                        {it.title}
                      </a>
                    ) : (
                      it.title
                    )}
                  </div>
                  <pre className="ctx-preview-body">{it.body}</pre>
                </div>
              ))}
            </div>
          )}
        </form>
        <div className="modal-actions">
          <button type="button" className="btn-ghost" onClick={onClose}>
            Close
          </button>
        </div>
      </div>
    </div>
  );
}
