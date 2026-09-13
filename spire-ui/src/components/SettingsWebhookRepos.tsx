import { useEffect, useState } from 'react';
import { Check } from 'lucide-react';
import {
  deleteWebhookRepo,
  fetchWebhookRepos,
  rotateWebhookSecret,
  updateWebhookRepo,
  type WebhookRepoSecret,
  type WebhookRepoView,
  type WebhookScope,
} from '../api';
import { CopyableValue } from '../render';
import CopyField from './CopyField';
import IconButton from './IconButton';
import ServingCell from './ServingCell';
import Tooltip from './Tooltip';
import { webhookSetupGuide } from './webhookSetup';
import { useEditDeepLink } from '../hooks/useEditDeepLink';
import { useServingAccounts } from '../hooks/useServingAccounts';

const SCOPES: { value: WebhookScope; label: string }[] = [
  { value: 'repo', label: 'Repository' },
  { value: 'org', label: 'Organization' },
];

const scopeLabel = (s: WebhookScope) => SCOPES.find((x) => x.value === s)?.label ?? s;

/** Cell types, hoisted: they close over nothing and were rebuilt for every cell of every row. */
const CELL_SUB = { fontSize: 12, color: 'var(--text-2)' } as const;
const CELL_TARGET = { fontSize: 12.5 } as const;

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
  // An attention row names one registration; land the operator on it, not just on this page.
  useEditDeepLink(repos, setForm);
  const [confirmDelete, setConfirmDelete] = useState<WebhookRepoView | null>(null);
  const serving = useServingAccounts(repos);

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
          <a href="#/settings/repositories/registry">Registered repositories and accounts</a>
          <Tooltip label="Add webhook">
            <button className="iconbtn" onClick={() => setForm('new')} aria-label="Add webhook">
              <svg width="15" height="15" viewBox="0 0 16 16" fill="none">
                <path d="M8 3v10M3 8h10" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
              </svg>
            </button>
          </Tooltip>
        </div>

        {repos.length > 0 && (
          <p className="prov-note">
            Paste each row’s <strong>Payload URL</strong>, and the <strong>secret</strong> you were shown
            when the row was created, into that repository or organization’s webhook settings, prefixing
            the path with your public webhook base (e.g. your Cloudflare tunnel URL). The secret is shown
            once; use Rotate in the edit dialog to mint a new one. Repository settings select the
            accounts that process each repository's events.
          </p>
        )}

        {error ? (
          <div style={{ padding: '26px 18px', color: 'var(--crit)', fontSize: 13 }}>{error}</div>
        ) : loading && repos.length === 0 ? (
          <div style={{ padding: '26px 18px', color: 'var(--text-3)', fontSize: 13 }}>Loading…</div>
        ) : repos.length === 0 ? (
          <div className="wh-empty">
            <div className="wh-empty-icon">
              <svg
                width="22"
                height="22"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.7"
                strokeLinecap="round"
                strokeLinejoin="round"
              >
                <path d="M10 13a5 5 0 0 0 7.07 0l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71" />
                <path d="M14 11a5 5 0 0 0-7.07 0l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71" />
              </svg>
            </div>
            <div className="wh-empty-title">No webhooks yet</div>
            <p className="wh-empty-text">
              Register a repository and select the webhook kinds it needs.
            </p>
            <button className="btn" onClick={() => setForm('new')}>
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none">
                <path d="M8 3v10M3 8h10" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
              </svg>
              Add webhook
            </button>
          </div>
        ) : (
          // Every cell is one line, so a narrow window scrolls the table rather than stacking the
          // payload path under the target and turning nine rows into thirty.
          <div className="prov-scroll">
            <table className="prov-table">
              <thead>
                <tr>
                  <th>Scope</th>
                  <th>Target</th>
                  <th>Forge</th>
                  <th>Accounts</th>
                  <th>Webhook path</th>
                  <th>Enabled</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {repos.map((w) => {
                  // One lookup for both chips: the same (type, owner) pair answers reviewer and factory.
                  const serves = w.repositoryId ? serving[w.repositoryId] : { error: 'Select a repository to see its accounts' };
                  return (
                  <tr key={w.id}>
                    <td style={CELL_SUB}>{scopeLabel(w.scope)}</td>
                    {/* The secret has no column of its own: it read "secret set" on every healthy
                        row. A missing one is the only case worth pixels, and it is said here — the
                        attention panel raises WEBHOOK_SECRET_MISSING for it too, and Rotate mints a
                        new one from the edit dialog. */}
                    <td className="mono nowrap" style={CELL_TARGET}>
                      {w.target}
                      {!w.hasSecret && <div className="prov-sub wh-nosecret">no secret</div>}
                    </td>
                    <td className="mono nowrap" style={CELL_SUB}>
                      {w.providerType}
                    </td>
                    <td>
                      <div className="serving-pair">
                        <ServingCell role="reviewer" lookup={serves} />
                        <ServingCell role="factory" lookup={serves} />
                      </div>
                    </td>
                    {/* Bounded so the path ellipses instead of taking the row's width. Nothing is
                        lost: CopyableValue puts the whole path in its own title and copies it in full.
                        The bound is on a div, not on the td: max-width on a table cell is undefined in
                        CSS 2.1 and every browser ignores it under table-layout:auto, so the same class
                        one element up did nothing at all. */}
                    <td>
                      <div className="wh-url">
                        <CopyableValue text={webhookPath(w)} mono copyTitle="Copy the webhook path" />
                      </div>
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
                  );
                })}
              </tbody>
            </table>
          </div>
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

/** Legacy registration repair preserves its existing key, ciphertext and product kind. */
function WebhookRepoFormModal({ initial, onClose, onSaved }: {
  initial: WebhookRepoView | null; onClose: () => void; onSaved: () => void;
}) {
  const [origin, setOrigin] = useState(initial?.forgeOrigin ?? '');
  const [revealed, setRevealed] = useState<WebhookRepoSecret | null>(null);
  const [enabled, setEnabled] = useState(initial?.enabled ?? true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  async function save(event: React.FormEvent) {
    event.preventDefault();
    if (!initial) return;
    setBusy(true); setError(null);
    try {
      await updateWebhookRepo(initial.id, { providerType: initial.providerType, scope: initial.scope,
        target: initial.target, enabled, forgeOrigin: origin.trim() || null });
      onSaved();
    } catch (failure) { setError(String(failure)); }
    finally { setBusy(false); }
  }
  async function rotate() {
    if (!initial || !window.confirm('Rotate this secret? Update the forge webhook afterward.')) return;
    setBusy(true); setError(null);
    try { setRevealed(await rotateWebhookSecret(initial.id)); }
    catch (failure) { setError(String(failure)); }
    finally { setBusy(false); }
  }
  if (revealed) return <SecretRevealModal result={revealed} rotated onDone={onSaved} />;
  return <div className="modal-overlay"><div className="modal" role="dialog" aria-modal="true" aria-label="Edit webhook">
    <form onSubmit={save} className="modal-body">
      <h3>{initial ? 'Edit existing webhook' : 'Register a repository'}</h3>
      {initial ? <>
        <p>{initial.providerType} · {initial.scope} · {initial.target}</p>
        <p>Existing organization hooks accept events only for explicitly registered repositories.</p>
        <label className="field">Forge origin<input aria-label="Forge origin" type="url" value={origin} onChange={e => setOrigin(e.target.value)} /></label>
        <label><input type="checkbox" checked={enabled} onChange={e => setEnabled(e.target.checked)} /> Enabled</label>
        <p>Saving preserves the existing URL, secret and event kind.</p>
        <button className="btn" disabled={busy}>Save changes</button>
        <button type="button" className="btn" disabled={busy} onClick={() => void rotate()}>Rotate secret</button>
      </> : <p><a href="#/settings/repositories">Register a repository, then choose its webhook kinds.</a></p>}
      {error && <p role="alert">{error}</p>}
      <button type="button" className="btn" onClick={onClose}>Close</button>
    </form>
  </div></div>;
}

function WebhookSetupChecklist({ providerType }: { providerType: string }) {
  const guide = webhookSetupGuide(providerType);
  if (!guide) {
    return null;
  }
  return (
    <div className="wh-setup">
      <div className="wh-setup-title">Next — set up on {guide.providerLabel}</div>
      <ol className="wh-steps">
        {guide.steps.map((step, i) => (
          <li className="wh-step" key={i}>
            <div className="wh-step-body">
              <div className="wh-step-title">{step.title}</div>
              {step.detail && <div className="wh-step-detail">{step.detail}</div>}
              {step.events && (
                <div className="chips">
                  {step.events.map((event) => (
                    <span className="chip on" key={event}>
                      <Check size={10} aria-hidden="true" /> {event}
                    </span>
                  ))}
                </div>
              )}
            </div>
          </li>
        ))}
      </ol>
    </div>
  );
}

/** One-time reveal of a freshly minted secret + its payload URL — the only time the secret is visible. */
function SecretRevealModal({
  result,
  rotated,
  onDone,
}: {
  result: WebhookRepoSecret;
  rotated: boolean;
  onDone: () => void;
}) {
  const path = webhookPath(result.repo);
  return (
    <div className="modal-overlay">
      <div className="modal wide" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <div className="modal-head">
          <h3>{rotated ? 'New secret generated' : 'Webhook created'}</h3>
          <button className="iconbtn" onClick={onDone} aria-label="Close">
            ✕
          </button>
        </div>
        <div className="modal-body scroll">
          <CopyField
            label="Payload URL (path)"
            value={path}
            hint="Prefix with your public webhook base (e.g. your Cloudflare tunnel URL)."
          />
          <CopyField label="Secret" value={result.secret} hint="Copy it now — it won’t be shown again." />

          <WebhookSetupChecklist providerType={result.repo.providerType} />

          <div className="modal-actions">
            <button type="button" className="btn" onClick={onDone}>
              Done
            </button>
          </div>
        </div>
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
    <div className="modal-overlay">
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
