import { useEffect, useState } from 'react';
import {
  checkContextProvider,
  createContextProvider,
  deleteContextProvider,
  fetchContextProviders,
  fetchProviders,
  type ProviderView,
  previewContextProvider,
  updateContextProvider,
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
import { accountOptionLabel } from './accounts';
import { useEditDeepLink } from '../hooks/useEditDeepLink';

export const compatibleAccountKinds: Record<ContextType, string[]> = {
  jira: ['atlassian'], confluence: ['atlassian'], 'github-issues': ['github'],
  'gitlab-issues': ['gitlab'], code: ['github', 'gitlab'],
};

export const CONTEXT_TYPES: ContextType[] = ['jira', 'confluence', 'github-issues', 'gitlab-issues', 'code'];

/** Per-type form and preview copy — Jira resolves ticket keys, Confluence resolves page links. */
interface TypeCopy {
  // The type picker's option text. Usually just the type itself, except 'code' — a bare "code" option
  // reads as meaningless in a dropdown, so this type gets a descriptive label instead.
  label: string;
  namePlaceholder: string;
  baseUrlPlaceholder: string;
  baseUrlHint: string;
  narrowLabel: string;
  narrowPlaceholder: string;
  narrowHint: string;
  previewLabel: string;
  previewPlaceholder: (projectKeys: string | null) => string;
  previewHint: string;
}

export const TYPE_COPY: Record<ContextType, TypeCopy> = {
  jira: {
    label: 'jira',
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
  },
  confluence: {
    label: 'confluence',
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
  },
  'github-issues': {
    label: 'github-issues',
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
  },
  'gitlab-issues': {
    label: 'gitlab-issues',
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
  },
  code: {
    label: 'Repository code',
    namePlaceholder: 'Acme repository code',
    baseUrlPlaceholder: 'https://api.github.com',
    baseUrlHint:
      'The API root for repository contents. The selected account determines the platform.',
    narrowLabel: 'Path allow-list',
    narrowPlaceholder: 'src/main/, src/allowed/',
    narrowHint:
      'Optional: only files under these repository-relative path prefixes are read. This is a prefix ' +
      'match — src/foo also matches src/foobar/ — so add a trailing slash (src/foo/) when you mean a ' +
      'directory only. Leave blank to read from anywhere in the repository.',
    previewLabel: 'Preview',
    previewPlaceholder: () => '',
    previewHint: 'Live preview is not available for this type yet — use Check above to verify connectivity.',
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
      list.filter((p) => p.accountEnabled !== false && p.accountId).forEach((p) => void checkOne(p.id));
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
                <th>Account</th>
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
                  {/* One consumer today: the review worker's context aggregator, reading. Becomes data
                      when M3 registers a write-capable account against the same host (ADR-035). */}
                  <td>
                    <span className="prov-sub">{p.accountName ?? 'Migration pending'}</span>
                    {p.accountEnabled === false && <div className="prov-note">Account disabled</div>}
                  </td>
                  <td className="mono">{p.baseUrl}</td>
                  <td>
                    {p.accountId && p.accountEnabled !== false
                      ? <ConnCell conn={conns[p.id]} onRecheck={() => void checkOne(p.id)} />
                      : <span className="prov-sub">Inactive</span>}
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
                      {/* Not offered for 'code': the backend refuses a preview for that type with a
                          400, so the control could only ever produce an error. Check (above) is the
                          verification this type has. */}
                      {p.type !== 'code' && (
                        <IconButton
                          kind="test"
                          onClick={() => setTestProvider(p)}
                          title="Test connection & preview context"
                          aria-label="Test"
                        />
                      )}
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
  const [accountId, setAccountId] = useState(initial?.accountId ?? '');
  const [accounts, setAccounts] = useState<ProviderView[] | null>(null);
  const [accountError, setAccountError] = useState<string | null>(null);
  useEffect(() => {
    let active = true;
    fetchProviders().then((list) => { if (active) setAccounts(list); })
      .catch((err) => { if (active) setAccountError(String(err)); });
    return () => { active = false; };
  }, []);
  const [projectKeys, setProjectKeys] = useState(initial?.projectKeys ?? '');
  const [enabled, setEnabled] = useState(initial?.enabled ?? true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const copy = TYPE_COPY[type];
  const compatible = (accounts ?? []).filter((a) => compatibleAccountKinds[type].includes(a.type));

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const input: ContextProviderInput = {
      name: name.trim(),
      type,
      baseUrl: baseUrl.trim(),
      accountId,
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
                options={CONTEXT_TYPES.map((t) => ({ value: t, label: TYPE_COPY[t].label }))}
                onChange={(v) => {
                  const nextType = v as ContextType;
                  setType(nextType);
                  if (!compatibleAccountKinds[nextType].includes(accounts?.find((a) => a.id === accountId)?.type ?? '')) setAccountId('');
                }}
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

          {accountError ? <p className="modal-msg modal-error">Accounts could not be loaded: {accountError}</p>
            : accounts === null ? <p>Loading accounts…</p>
            : compatible.length === 0 ? <p>Register an account first: <a href="#/settings/accounts">Accounts</a></p>
            : <label className="field">
                <span>Account</span>
                <Select ariaLabel="Account" value={accountId}
                  options={[{ value: '', label: 'Select an account' }, ...compatible.map((a) => ({ value: a.id, label: accountOptionLabel(a) }))]}
                  onChange={(id) => {
                    setAccountId(id);
                    const account = compatible.find((a) => a.id === id);
                    if (account) {
                      let url = account.baseUrl.replace(/\/+$/, '');
                      if (account.type === 'gitlab') url = url.replace(/\/api\/v4$/, '');
                      if (type === 'jira') url = url.replace(/\/wiki$/, '');
                      if (type === 'confluence' && !url.endsWith('/wiki')) url += '/wiki';
                      setBaseUrl(url);
                    }
                  }} />
                {compatible.find((a) => a.id === accountId)?.enabled === false && <small className="field-hint">Account disabled — this source will not resolve.</small>}
              </label>}

          <label className="field-check">
            <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
            <span>Enabled</span>
          </label>

          {error && <div className="modal-msg modal-error">{error}</div>}

          <div className="modal-actions">
            <button type="button" className="btn-ghost" onClick={onClose}>
              Cancel
            </button>
            <button type="submit" className="btn" disabled={busy || !compatible.some((a) => a.id === accountId)}>
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
