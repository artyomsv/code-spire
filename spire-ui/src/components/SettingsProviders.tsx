import { useEffect, useState } from 'react';
import {
  checkProvider,
  createProvider,
  deleteProvider,
  fetchProviders,
  updateProvider,
  type AuthKind,
  type ProviderInput,
  type ProviderView,
} from '../api';
import ReviewModeToggle from './ReviewModeToggle';

// Provider types and their default API base URLs. When a user switches type
// without having customised the base URL, we swap in the matching default.
const PROVIDER_TYPES = ['bitbucket-cloud', 'github', 'gitlab'] as const;
const DEFAULT_BASE_URLS: Record<string, string> = {
  'bitbucket-cloud': 'https://api.bitbucket.org/2.0',
  github: 'https://api.github.com',
  gitlab: 'https://gitlab.com/api/v4',
};
// Providers that authenticate with a Bearer token only (no Basic-auth path).
const BEARER_ONLY = new Set(['github', 'gitlab']);
const KNOWN_DEFAULTS = new Set(Object.values(DEFAULT_BASE_URLS));
const DEFAULT_BASE_URL = DEFAULT_BASE_URLS['bitbucket-cloud'];

// Per-provider connectivity status, keyed by provider id.
type ConnState = 'checking' | 'ok' | 'fail';
interface Conn {
  state: ConnState;
  account?: string | null;
  detail?: string | null;
}

export default function SettingsProviders() {
  const [providers, setProviders] = useState<ProviderView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [conns, setConns] = useState<Record<string, Conn>>({});

  // null = form closed; a ProviderView = editing; 'new' = adding.
  const [form, setForm] = useState<'new' | ProviderView | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<ProviderView | null>(null);

  async function checkOne(id: string) {
    setConns((prev) => ({ ...prev, [id]: { state: 'checking' } }));
    try {
      const r = await checkProvider(id);
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
      const list = await fetchProviders();
      setProviders(list);
      // Check connectivity once on load — no continuous polling, to avoid
      // burning the provider's rate limit; re-run per row on demand.
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

  return (
    <section className="content">
      <ReviewModeToggle />

      <div className="card">
        <div className="prov-head">
          <h2 className="prov-title">Providers</h2>
          <button
            className="iconbtn"
            onClick={() => setForm('new')}
            aria-label="Add provider"
            title="Add provider"
          >
            <svg width="15" height="15" viewBox="0 0 16 16" fill="none">
              <path d="M8 3v10M3 8h10" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
            </svg>
          </button>
        </div>
        {error ? (
          <div style={{ padding: '26px 18px', color: 'var(--crit)', fontSize: 13 }}>{error}</div>
        ) : loading && providers.length === 0 ? (
          <div style={{ padding: '26px 18px', color: 'var(--text-3)', fontSize: 13 }}>Loading…</div>
        ) : providers.length === 0 ? (
          <div className="prov-empty">
            <span>No providers registered yet.</span>
            <button className="btn" onClick={() => setForm('new')}>
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none">
                <path d="M8 3v10M3 8h10" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
              </svg>
              Add provider
            </button>
          </div>
        ) : (
          <table className="prov-table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Type</th>
                <th>Workspace</th>
                <th>Auth</th>
                <th>Connection</th>
                <th className="cell-r">Authors</th>
                <th>Enabled</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {providers.map((p) => (
                <tr key={p.id}>
                  <td>
                    <div className="prov-name">{p.name}</div>
                    <div className="prov-sub">{p.baseUrl}</div>
                  </td>
                  <td className="mono" style={{ fontSize: 12, color: 'var(--text-2)' }}>
                    {p.type}
                  </td>
                  <td className="mono" style={{ fontSize: 12, color: 'var(--text-2)' }}>
                    {p.workspace}
                  </td>
                  <td>
                    <div className="mono" style={{ fontSize: 12 }}>
                      {p.authKind}
                      {p.authKind === 'basic' && p.authUsername ? ` · ${p.authUsername}` : ''}
                    </div>
                    <div className="prov-sub">{p.hasSecret ? 'token set' : 'no token'}</div>
                  </td>
                  <td>
                    <ConnCell conn={conns[p.id]} onRecheck={() => void checkOne(p.id)} />
                  </td>
                  <td className="cell-r mono" style={{ fontSize: 12, color: 'var(--text-2)' }}>
                    {p.authors.length}
                  </td>
                  <td>
                    <span className={`pill ${p.enabled ? 'completed' : 'cancelled'}`}>
                      <span className="glyph"></span>
                      {p.enabled ? 'Enabled' : 'Disabled'}
                    </span>
                  </td>
                  <td>
                    <div className="prov-actions">
                      <button className="tbtn" onClick={() => setForm(p)}>
                        Edit
                      </button>
                      <button className="tbtn danger" onClick={() => setConfirmDelete(p)}>
                        Delete
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {form && (
        <ProviderFormModal
          initial={form === 'new' ? null : form}
          onClose={() => setForm(null)}
          onSaved={() => {
            setForm(null);
            void load();
          }}
        />
      )}

      {confirmDelete && (
        <DeleteConfirmModal
          provider={confirmDelete}
          onClose={() => setConfirmDelete(null)}
          onDeleted={() => {
            setConfirmDelete(null);
            void load();
          }}
        />
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
        ? conn?.account
          ? `@${conn.account}`
          : 'Connected'
        : 'Failed';
  const title =
    state === 'checking'
      ? 'Contacting the provider…'
      : state === 'ok'
        ? `Connected${conn?.account ? ` as @${conn.account}` : ''} — click to re-check`
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

function ProviderFormModal({
  initial,
  onClose,
  onSaved,
}: {
  initial: ProviderView | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const editing = initial !== null;

  const [name, setName] = useState(initial?.name ?? '');
  const [type, setType] = useState(initial?.type ?? 'bitbucket-cloud');
  const [baseUrl, setBaseUrl] = useState(initial?.baseUrl ?? DEFAULT_BASE_URL);
  const [workspace, setWorkspace] = useState(initial?.workspace ?? '');
  const [authKind, setAuthKind] = useState<AuthKind>(initial?.authKind ?? 'bearer');
  const [authUsername, setAuthUsername] = useState(initial?.authUsername ?? '');
  const [secret, setSecret] = useState('');
  const [botAccountId, setBotAccountId] = useState(initial?.botAccountId ?? '');
  const [enabled, setEnabled] = useState(initial?.enabled ?? true);
  const [authors, setAuthors] = useState<string[]>(initial?.authors ?? []);
  const [authorDraft, setAuthorDraft] = useState('');

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function changeType(next: string) {
    setType(next);
    // Swap the base URL to the new type's default unless the user has customised it.
    if (!baseUrl.trim() || KNOWN_DEFAULTS.has(baseUrl.trim())) {
      setBaseUrl(DEFAULT_BASE_URLS[next] ?? baseUrl);
    }
    // GitHub and GitLab authenticate with a Bearer token only.
    if (BEARER_ONLY.has(next)) {
      setAuthKind('bearer');
    }
  }

  function addAuthor() {
    const v = authorDraft.trim();
    if (!v || authors.includes(v)) {
      setAuthorDraft('');
      return;
    }
    setAuthors([...authors, v]);
    setAuthorDraft('');
  }

  function removeAuthor(a: string) {
    setAuthors(authors.filter((x) => x !== a));
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!name.trim() || !workspace.trim() || !baseUrl.trim()) {
      setError('Name, base URL and workspace are required.');
      return;
    }
    if (authKind === 'basic' && !authUsername.trim()) {
      setError('Username is required for basic auth.');
      return;
    }
    if (!editing && !secret.trim()) {
      setError('A secret / token is required.');
      return;
    }

    // Flush a typed-but-not-yet-added author so it isn't silently dropped on submit.
    const draft = authorDraft.trim();
    const finalAuthors = draft && !authors.includes(draft) ? [...authors, draft] : authors;

    const input: ProviderInput = {
      name: name.trim(),
      type,
      baseUrl: baseUrl.trim(),
      workspace: workspace.trim(),
      authKind,
      authUsername: authKind === 'basic' ? authUsername.trim() : null,
      botAccountId: botAccountId.trim(),
      enabled,
      authors: finalAuthors,
    };
    if (secret.trim()) input.secret = secret;

    setBusy(true);
    setError(null);
    try {
      if (editing && initial) {
        await updateProvider(initial.id, input);
      } else {
        await createProvider(input);
      }
      onSaved();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <div className="modal-head">
          <h3>{editing ? 'Edit provider' : 'Add provider'}</h3>
          <button className="iconbtn" onClick={onClose} aria-label="Close">
            ✕
          </button>
        </div>
        <form className="modal-body scroll" onSubmit={submit}>
          <label className="field">
            <span>Name</span>
            <input placeholder="Acme Bitbucket" value={name} onChange={(e) => setName(e.target.value)} autoFocus />
          </label>

          <div className="field-row-12">
            <label className="field">
              <span>Type</span>
              <select value={type} onChange={(e) => changeType(e.target.value)}>
                {PROVIDER_TYPES.map((t) => (
                  <option key={t} value={t}>
                    {t}
                  </option>
                ))}
              </select>
            </label>
            <label className="field">
              <span>Workspace</span>
              <input
                className="mono"
                placeholder="workspace"
                value={workspace}
                onChange={(e) => setWorkspace(e.target.value)}
              />
            </label>
          </div>

          <label className="field">
            <span>Base URL</span>
            <input
              className="mono"
              placeholder={DEFAULT_BASE_URL}
              value={baseUrl}
              onChange={(e) => setBaseUrl(e.target.value)}
            />
          </label>

          <div className="field-row-13">
            <label className="field">
              <span>Auth kind</span>
              <select
                value={authKind}
                disabled={BEARER_ONLY.has(type)}
                onChange={(e) => setAuthKind(e.target.value as AuthKind)}
              >
                <option value="bearer">bearer</option>
                {!BEARER_ONLY.has(type) && <option value="basic">basic</option>}
              </select>
            </label>
            <label className="field">
              <span>Secret / token</span>
              <input
                type="password"
                autoComplete="new-password"
                placeholder={editing ? 'leave blank to keep current' : 'access token'}
                value={secret}
                onChange={(e) => setSecret(e.target.value)}
              />
              {editing && (
                <small className="field-hint">
                  {initial?.hasSecret ? 'A token is stored — leave blank to keep it.' : 'No token stored yet.'}
                </small>
              )}
            </label>
          </div>

          {authKind === 'basic' && (
            <label className="field">
              <span>Username</span>
              <input
                className="mono"
                placeholder="username"
                value={authUsername}
                onChange={(e) => setAuthUsername(e.target.value)}
              />
            </label>
          )}

          <label className="field">
            <span>Bot account id <span className="field-optional">optional</span></span>
            <input
              className="mono"
              placeholder="auto-detected from the token"
              value={botAccountId}
              onChange={(e) => setBotAccountId(e.target.value)}
            />
            <small className="field-hint">
              Leave blank — it's resolved from the token when you save (which also validates the token).
            </small>
          </label>

          <div className="field">
            <span>Authors (PR-author allowlist)</span>
            <div className="chip-add">
              <input
                className="mono"
                placeholder="username"
                value={authorDraft}
                onChange={(e) => setAuthorDraft(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') {
                    e.preventDefault();
                    addAuthor();
                  }
                }}
              />
              <button type="button" className="btn-ghost" onClick={addAuthor}>
                Add
              </button>
            </div>
            {authors.length > 0 && (
              <div className="chips-edit">
                {authors.map((a) => (
                  <span key={a} className="chip-x">
                    {a}
                    <button type="button" onClick={() => removeAuthor(a)} aria-label={`Remove ${a}`}>
                      ✕
                    </button>
                  </span>
                ))}
              </div>
            )}
          </div>

          <label className="field-check">
            <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
            <span>Enabled</span>
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

function DeleteConfirmModal({
  provider,
  onClose,
  onDeleted,
}: {
  provider: ProviderView;
  onClose: () => void;
  onDeleted: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function remove() {
    setBusy(true);
    setError(null);
    try {
      await deleteProvider(provider.id);
      onDeleted();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <div className="modal-head">
          <h3>Delete provider</h3>
          <button className="iconbtn" onClick={onClose} aria-label="Close">
            ✕
          </button>
        </div>
        <div className="modal-body">
          <p style={{ margin: 0, fontSize: 13.5, color: 'var(--text)' }}>
            Delete <strong>{provider.name}</strong>? This removes its stored token and cannot be undone.
          </p>
          {error && <div className="modal-msg modal-error">{error}</div>}
          <div className="modal-actions">
            <button type="button" className="btn-ghost" onClick={onClose}>
              Cancel
            </button>
            <button type="button" className="btn btn-danger" onClick={remove} disabled={busy}>
              {busy ? 'Deleting…' : 'Delete'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
