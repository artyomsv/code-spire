import { useEffect, useState } from 'react';
import {
  checkContextProvider,
  createContextProvider,
  deleteContextProvider,
  fetchContextProviders,
  previewContextProvider,
  updateContextProvider,
  type ContextAuthKind,
  type ContextPreviewResult,
  type ContextProviderInput,
  type ContextProviderView,
  type ContextType,
} from '../api';
import { Plus } from 'lucide-react';
import IconButton from './IconButton';
import LastChecked from './LastCheckedBadge';
import Tooltip from './Tooltip';
import Select from './Select';
import { useEditDeepLink } from '../hooks/useEditDeepLink';

export const CONTEXT_TYPES: ContextType[] = ['jira', 'confluence', 'github-issues', 'gitlab-issues'];

/** Per-type form and preview copy — Jira resolves ticket keys, Confluence resolves page links. */
interface TypeCopy {
  namePlaceholder: string;
  baseUrlPlaceholder: string;
  baseUrlHint: string;
  narrowLabel: string;
  narrowPlaceholder: string;
  narrowHint: string;
  previewLabel: string;
  previewPlaceholder: (projectKeys: string | null) => string;
  previewHint: string;
  // Auth kinds the backend accepts for this type — GitHub's basic auth is deprecated and a
  // GitLab PAT is bearer-only, so those two types permit only 'bearer'.
  authKinds: ContextAuthKind[];
}

export const TYPE_COPY: Record<ContextType, TypeCopy> = {
  jira: {
    namePlaceholder: 'Acme Jira',
    baseUrlPlaceholder: 'https://acme.atlassian.net',
    baseUrlHint: 'Your Jira site root — the client appends the REST paths.',
    narrowLabel: 'Project keys',
    narrowPlaceholder: 'ACME, PROJ',
    narrowHint:
      'Only issue keys for these projects are looked up (e.g. ACME matches ACME-123). Leave blank ' +
      'to accept any key. Also lets the Test box resolve a bare ticket number.',
    previewLabel: 'Ticket or text',
    previewPlaceholder: (projectKeys) =>
      projectKeys
        ? `a ticket number (${projectKeys.split(/[,\s]+/)[0]}-123 or just 123) or a PR title`
        : 'a full ticket key (PROJ-123) or a PR title',
    previewHint:
      'Resolves the key with this provider’s pattern, fetches it live, and shows exactly what a review would inject.',
    authKinds: ['basic', 'bearer'],
  },
  confluence: {
    namePlaceholder: 'Acme Confluence',
    baseUrlPlaceholder: 'https://acme.atlassian.net/wiki',
    baseUrlHint: 'Your Confluence wiki root (…/wiki on Cloud) — the client appends the REST paths.',
    narrowLabel: 'Space keys',
    narrowPlaceholder: 'ENG, DOC',
    narrowHint:
      'Optional: only pages in these spaces are included (e.g. ENG). Leave blank to accept any page linked ' +
      'from the PR description.',
    previewLabel: 'Page URL or id',
    previewPlaceholder: () => 'a page URL (…/pages/12345/…) or a bare page id',
    previewHint: 'Fetches the linked page live and shows exactly what a review would inject.',
    authKinds: ['basic', 'bearer'],
  },
  'github-issues': {
    namePlaceholder: 'Acme GitHub issues',
    baseUrlPlaceholder: 'https://api.github.com',
    baseUrlHint:
      'The API root — https://api.github.com for github.com, or https://your-host/api/v3 for ' +
      'Enterprise Server. Needs a token that can read issues.',
    narrowLabel: 'Owner/repo allow-list',
    narrowPlaceholder: 'acme, acme/widgets',
    narrowHint:
      'Optional: only these repositories are looked up. An owner (acme) covers everything under it; ' +
      'acme/widgets matches one repository. Leave blank to accept any repository on this host.',
    previewLabel: 'Issue reference',
    previewPlaceholder: () => 'a qualified reference (acme/widgets#123) or an issue URL',
    previewHint:
      'A bare #123 only means something inside a pull request, so the test box needs the repository ' +
      'named — in the reference or in a pasted URL.',
    authKinds: ['bearer'],
  },
  'gitlab-issues': {
    namePlaceholder: 'Acme GitLab issues',
    baseUrlPlaceholder: 'https://gitlab.com',
    baseUrlHint:
      'Your instance root, with no /api/v4 suffix — the client appends the API paths. Needs a token ' +
      'with read_api scope.',
    narrowLabel: 'Group/project allow-list',
    narrowPlaceholder: 'acme, acme/tools/widgets',
    narrowHint:
      'Optional: only these projects are looked up. A group (acme) covers every project beneath it. ' +
      'Leave blank to accept any project on this host.',
    previewLabel: 'Issue reference',
    previewPlaceholder: () => 'a qualified reference (acme/widgets#123) or an issue URL',
    previewHint:
      'Resolves issues (#12), merge requests (!34) and epics (&7). A bare reference needs the project ' +
      'named here, since the test box has no merge request behind it.',
    authKinds: ['bearer'],
  },
};

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
  // An attention row names one provider; land the operator on it, not just on this page.
  useEditDeepLink(providers, setForm);
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
          <span className="k">Enrich reviews with the tickets, issues and pages a pull request references</span>
          <Tooltip label="Add provider" className="tt-push">
            <button className="iconbtn" onClick={() => setForm('new')} aria-label="Add provider">
              <Plus size={15} />
            </button>
          </Tooltip>
        </div>
        {loading && providers.length === 0 ? (
          <div style={{ padding: '20px 18px', color: 'var(--text-3)', fontSize: 13 }}>Loading…</div>
        ) : providers.length === 0 ? (
          <div style={{ padding: '20px 18px', color: 'var(--text-3)', fontSize: 13 }}>
            No context providers yet — connect an issue tracker or a documentation space, and the tickets,
            issues and pages a pull request references get pulled into the review prompt.
          </div>
        ) : (
          <table className="prov-table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Type</th>
                <th>Base URL</th>
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
                  <td className="mono">{p.baseUrl}</td>
                  <td>
                    <ConnCell conn={conns[p.id]} onRecheck={() => void checkOne(p.id)} />
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
                      <IconButton
                        kind="test"
                        onClick={() => setTestProvider(p)}
                        title="Test connection & preview context"
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
  const [enabled, setEnabled] = useState(initial?.enabled ?? true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const copy = TYPE_COPY[type];

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
      enabled,
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
    <div className="modal-overlay">
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h3>{editing ? 'Edit context provider' : 'Add context provider'}</h3>
        <form className="modal-body" onSubmit={submit}>
          <label className="field">
            <span>Name</span>
            <input placeholder={copy.namePlaceholder} value={name} onChange={(e) => setName(e.target.value)} />
          </label>

          <div className="field-row-2">
            <label className="field">
              <span>Type</span>
              <Select
                ariaLabel="Type"
                value={type}
                options={CONTEXT_TYPES.map((t) => ({ value: t, label: t }))}
                onChange={(v) => {
                  const nextType = v as ContextType;
                  setType(nextType);
                  // The new type may not permit the currently-selected auth kind (e.g. GitHub
                  // Issues is bearer-only) — coerce it rather than let the form offer a save the
                  // backend will reject.
                  const permitted = TYPE_COPY[nextType].authKinds;
                  if (!permitted.includes(authKind)) setAuthKind(permitted[0]);
                }}
              />
            </label>
            <label className="field">
              <span>Auth</span>
              <Select
                ariaLabel="Auth"
                value={authKind}
                options={[
                  { value: 'basic', label: 'basic · email + API token (Cloud)' },
                  { value: 'bearer', label: 'bearer · personal access token' },
                ].filter((o) => copy.authKinds.includes(o.value as ContextAuthKind))}
                onChange={(v) => setAuthKind(v as ContextAuthKind)}
              />
            </label>
          </div>

          <label className="field">
            <span>Base URL</span>
            <input
              className="mono"
              placeholder={copy.baseUrlPlaceholder}
              value={baseUrl}
              onChange={(e) => setBaseUrl(e.target.value)}
            />
            <small className="field-hint">{copy.baseUrlHint}</small>
          </label>

          <label className="field">
            <span>
              {copy.narrowLabel} <span className="field-optional">optional</span>
            </span>
            <input
              className="mono"
              placeholder={copy.narrowPlaceholder}
              value={projectKeys}
              onChange={(e) => setProjectKeys(e.target.value)}
            />
            <small className="field-hint">{copy.narrowHint}</small>
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

  const copy = TYPE_COPY[provider.type];
  const hint = copy.previewPlaceholder(provider.projectKeys);

  return (
    <div className="modal-overlay">
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h3>Test context — {provider.name}</h3>
        <form className="modal-body" onSubmit={run}>
          <label className="field">
            <span>{copy.previewLabel}</span>
            <input placeholder={hint} value={text} onChange={(e) => setText(e.target.value)} autoFocus />
            <small className="field-hint">{copy.previewHint}</small>
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
