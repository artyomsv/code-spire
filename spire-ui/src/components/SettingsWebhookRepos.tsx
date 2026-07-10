import { useEffect, useState } from 'react';
import {
  createWebhookRepo,
  deleteWebhookRepo,
  fetchWebhookRepos,
  updateWebhookRepo,
  type WebhookRepoInput,
  type WebhookRepoView,
  type WebhookScope,
} from '../api';
import { CopyableValue } from '../render';
import IconButton from './IconButton';
import Tooltip from './Tooltip';

const PROVIDER_TYPES = ['github', 'gitlab', 'bitbucket-cloud'] as const;
const SCOPES: { value: WebhookScope; label: string; hint: string; placeholder: string }[] = [
  { value: 'repo', label: 'Repository', hint: 'One repo. Paste into that repo’s webhook settings.', placeholder: 'owner/repo' },
  { value: 'org', label: 'Organization', hint: 'Every repo in the org. Paste into the org’s webhook settings.', placeholder: 'owner (org login)' },
];

const scopeLabel = (s: WebhookScope) => SCOPES.find((x) => x.value === s)?.label ?? s;

/** The gateway path a delivery is routed on. Prefix with the public webhook base to build the payload URL. */
export function webhookPath(w: Pick<WebhookRepoView, 'providerType' | 'webhookKey'>): string {
  return `/webhooks/${w.providerType}/${w.webhookKey}`;
}

export default function SettingsWebhookRepos() {
  const [repos, setRepos] = useState<WebhookRepoView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // null = form closed; a WebhookRepoView = editing; 'new' = adding.
  const [form, setForm] = useState<'new' | WebhookRepoView | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<WebhookRepoView | null>(null);

  async function load() {
    setLoading(true);
    setError(null);
    try {
      setRepos(await fetchWebhookRepos());
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
      <div className="card">
        <div className="prov-head">
          <h2 className="prov-title">Webhooks</h2>
          <Tooltip label="Add webhook">
            <button className="iconbtn" onClick={() => setForm('new')} aria-label="Add webhook">
              <svg width="15" height="15" viewBox="0 0 16 16" fill="none">
                <path d="M8 3v10M3 8h10" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
              </svg>
            </button>
          </Tooltip>
        </div>

        <p className="prov-note">
          One endpoint per registration, routed by its key. Scope it to a single{' '}
          <strong>repository</strong> or a whole <strong>organization</strong> (one hook for every repo in
          the org). Paste the shown <strong>Payload URL</strong> + <strong>Secret</strong> into the matching
          webhook settings on the provider, prefixing the path with your public webhook base (e.g. your
          Tailscale Funnel URL). The owner must match a provider you've registered under Settings →
          Repositories.
        </p>

        {error ? (
          <div style={{ padding: '26px 18px', color: 'var(--crit)', fontSize: 13 }}>{error}</div>
        ) : loading && repos.length === 0 ? (
          <div style={{ padding: '26px 18px', color: 'var(--text-3)', fontSize: 13 }}>Loading…</div>
        ) : repos.length === 0 ? (
          <div className="prov-empty">
            <span>No webhooks yet.</span>
            <button className="btn" onClick={() => setForm('new')}>
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none">
                <path d="M8 3v10M3 8h10" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
              </svg>
              Add webhook
            </button>
          </div>
        ) : (
          <table className="prov-table">
            <thead>
              <tr>
                <th>Scope</th>
                <th>Target</th>
                <th>Provider</th>
                <th>Payload URL (path)</th>
                <th>Secret</th>
                <th>Enabled</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {repos.map((w) => (
                <tr key={w.id}>
                  <td style={{ fontSize: 12, color: 'var(--text-2)' }}>{scopeLabel(w.scope)}</td>
                  <td className="mono" style={{ fontSize: 12.5 }}>
                    {w.target}
                  </td>
                  <td className="mono" style={{ fontSize: 12, color: 'var(--text-2)' }}>
                    {w.providerType}
                  </td>
                  <td>
                    <CopyableValue text={webhookPath(w)} mono copyTitle="Copy the webhook path" />
                  </td>
                  <td>
                    <div className="prov-sub">{w.hasSecret ? 'secret set' : 'no secret'}</div>
                  </td>
                  <td>
                    <span className={`pill ${w.enabled ? 'completed' : 'cancelled'}`}>
                      <span className="glyph"></span>
                      {w.enabled ? 'Enabled' : 'Disabled'}
                    </span>
                  </td>
                  <td>
                    <div className="prov-actions">
                      <IconButton kind="edit" onClick={() => setForm(w)} title="Edit" aria-label="Edit" />
                      <IconButton
                        kind="delete"
                        onClick={() => setConfirmDelete(w)}
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
        <WebhookRepoFormModal
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
          repo={confirmDelete}
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

function WebhookRepoFormModal({
  initial,
  onClose,
  onSaved,
}: {
  initial: WebhookRepoView | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const editing = initial !== null;

  const [providerType, setProviderType] = useState(initial?.providerType ?? PROVIDER_TYPES[0]);
  const [scope, setScope] = useState<WebhookScope>(initial?.scope ?? 'repo');
  const [target, setTarget] = useState(initial?.target ?? '');
  const [secret, setSecret] = useState('');
  const [enabled, setEnabled] = useState(initial?.enabled ?? true);

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const scopeMeta = SCOPES.find((s) => s.value === scope)!;
  const valid = scope === 'org' ? /^[^/\s]+$/.test(target.trim()) : /^[^/\s]+\/[^/\s]+$/.test(target.trim());

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!valid) {
      setError(scope === 'org' ? 'Organization must be an owner (no slash).' : 'Repository must be owner/repo.');
      return;
    }
    if (!editing && !secret.trim()) {
      setError('A webhook secret is required.');
      return;
    }

    const input: WebhookRepoInput = { providerType, scope, target: target.trim(), enabled };
    if (secret.trim()) input.secret = secret;

    setBusy(true);
    setError(null);
    try {
      if (editing && initial) {
        await updateWebhookRepo(initial.id, input);
      } else {
        await createWebhookRepo(input);
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
          <h3>{editing ? 'Edit webhook' : 'Add webhook'}</h3>
          <button className="iconbtn" onClick={onClose} aria-label="Close">
            ✕
          </button>
        </div>
        <form className="modal-body scroll" onSubmit={submit}>
          <div className="field-row-2">
            <label className="field">
              <span>Provider type</span>
              <select value={providerType} onChange={(e) => setProviderType(e.target.value)}>
                {PROVIDER_TYPES.map((t) => (
                  <option key={t} value={t}>
                    {t}
                  </option>
                ))}
              </select>
            </label>
            <label className="field">
              <span>Scope</span>
              <select value={scope} onChange={(e) => setScope(e.target.value as WebhookScope)}>
                {SCOPES.map((s) => (
                  <option key={s.value} value={s.value}>
                    {s.label}
                  </option>
                ))}
              </select>
            </label>
          </div>

          <label className="field">
            <span>{scope === 'org' ? 'Organization' : 'Repository'}</span>
            <input
              className="mono"
              placeholder={scopeMeta.placeholder}
              value={target}
              onChange={(e) => setTarget(e.target.value)}
              autoFocus
            />
            <small className="field-hint">{scopeMeta.hint}</small>
          </label>

          <label className="field">
            <span>Webhook secret</span>
            <input
              type="password"
              autoComplete="new-password"
              placeholder={editing ? 'leave blank to keep current' : 'the HMAC secret you set on the provider'}
              value={secret}
              onChange={(e) => setSecret(e.target.value)}
            />
            {editing && (
              <small className="field-hint">
                {initial?.hasSecret ? 'A secret is stored — leave blank to keep it.' : 'No secret stored yet.'}
              </small>
            )}
          </label>

          {editing && initial && (
            <label className="field">
              <span>Payload URL (path)</span>
              <div className="mono field-static">{webhookPath(initial)}</div>
              <small className="field-hint">Prefix with your public webhook base to get the full payload URL.</small>
            </label>
          )}

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
              {busy ? 'Saving…' : editing ? 'Save changes' : 'Add webhook'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

function DeleteConfirmModal({
  repo,
  onClose,
  onDeleted,
}: {
  repo: WebhookRepoView;
  onClose: () => void;
  onDeleted: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function remove() {
    setBusy(true);
    setError(null);
    try {
      await deleteWebhookRepo(repo.id);
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
          <h3>Delete webhook</h3>
          <button className="iconbtn" onClick={onClose} aria-label="Close">
            ✕
          </button>
        </div>
        <div className="modal-body">
          <p style={{ margin: 0, fontSize: 13.5, color: 'var(--text)' }}>
            Delete the webhook for <strong>{repo.target}</strong>? Its key stops working immediately — remove
            the hook on the provider too. This cannot be undone.
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
