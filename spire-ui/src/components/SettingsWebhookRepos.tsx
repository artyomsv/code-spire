import { useEffect, useRef, useState } from 'react';
import { Check, Copy, RotateCw } from 'lucide-react';
import {
  createWebhookRepo,
  deleteWebhookRepo,
  fetchProviders,
  fetchWebhookRepos,
  rotateWebhookSecret,
  updateWebhookRepo,
  verifyRepo,
  type ProviderView,
  type RepoCheck,
  type WebhookRepoInput,
  type WebhookRepoSecret,
  type WebhookRepoView,
  type WebhookScope,
} from '../api';
import { CopyableValue } from '../render';
import IconButton from './IconButton';
import Select from './Select';
import Tooltip from './Tooltip';
import { webhookSetupGuide, webhookTargetHelp } from './webhookSetup';

const SCOPES: { value: WebhookScope; label: string }[] = [
  { value: 'repo', label: 'Repository' },
  { value: 'org', label: 'Organization' },
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

        {repos.length > 0 && (
          <p className="prov-note">
            Paste each row’s <strong>Payload URL</strong> + <strong>Secret</strong> into that repository or
            organization’s webhook settings, prefixing the path with your public webhook base (e.g. your
            Cloudflare tunnel URL). The owner must match a provider registered under Settings →
            Repositories.
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
              Register a repository or organization to get a unique payload URL and secret. Paste them
              into the provider’s webhook settings and every pull request flows in automatically — no
              per-repo setup after that.
            </p>
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

/** Loads enabled providers and preselects one — the row's provider on edit (matched by type + owner),
 *  else the first. Keeps the modal under the max-8 useState rule. */
function useWebhookProviders(initial: WebhookRepoView | null) {
  const [providers, setProviders] = useState<ProviderView[]>([]);
  const [providersLoaded, setProvidersLoaded] = useState(false);
  const [providerId, setProviderId] = useState('');
  const [loadError, setLoadError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    fetchProviders()
      .then((all) => {
        if (!alive) return;
        const usable = all.filter((p) => p.enabled);
        setProviders(usable);
        if (initial) {
          const owner = initial.scope === 'org' ? initial.target : initial.target.split('/')[0];
          const match = usable.find((p) => p.type === initial.providerType && p.workspace === owner);
          setProviderId(match?.id ?? '');
        } else if (usable.length > 0) {
          setProviderId(usable[0].id);
        }
      })
      .catch((err) => alive && setLoadError(err instanceof Error ? err.message : String(err)))
      .finally(() => alive && setProvidersLoaded(true));
    return () => {
      alive = false;
    };
  }, [initial]);

  return { providers, providersLoaded, providerId, setProviderId, loadError };
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

  const { providers, providersLoaded, providerId, setProviderId, loadError } = useWebhookProviders(initial);
  const [scope, setScope] = useState<WebhookScope>(initial?.scope ?? 'repo');
  const [slug, setSlug] = useState(() => {
    if (initial && initial.scope === 'repo') {
      const i = initial.target.indexOf('/');
      return i >= 0 ? initial.target.slice(i + 1) : '';
    }
    return '';
  });
  const [enabled, setEnabled] = useState(initial?.enabled ?? true);

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [revealed, setRevealed] = useState<WebhookRepoSecret | null>(null);

  const [verify, setVerify] = useState<{ state: 'idle' | 'checking' | 'ok' | 'fail'; detail?: string }>({
    state: 'idle',
  });
  const verifyReq = useRef(0);

  // A changed provider / scope / slug invalidates any prior/in-flight verify result.
  useEffect(() => {
    verifyReq.current += 1;
    setVerify({ state: 'idle' });
  }, [providerId, scope, slug]);

  const selectedProvider = providers.find((p) => p.id === providerId) ?? null;
  // On edit, if the provider was deleted we can't derive the owner — fall back to the stored row (read-only).
  const legacyEdit = editing && providersLoaded && !selectedProvider;
  const owner = selectedProvider?.workspace ?? (legacyEdit ? initial!.target.split('/')[0] : '');
  const providerType = selectedProvider?.type ?? initial?.providerType ?? '';
  const target = legacyEdit ? initial!.target : scope === 'org' ? owner : `${owner}/${slug.trim()}`;
  const targetHelp = webhookTargetHelp(providerType, scope);
  const validSlug = /^[^/\s]+$/.test(slug.trim());
  const valid = legacyEdit ? true : selectedProvider != null && (scope === 'org' ? owner.length > 0 : validSlug);
  const noProviders = providersLoaded && providers.length === 0 && !legacyEdit;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!valid) {
      setError(selectedProvider == null ? 'Select a provider first.' : 'Repository must be a single name (no slash).');
      return;
    }
    const input: WebhookRepoInput = { providerType, scope, target, enabled };
    setBusy(true);
    setError(null);
    try {
      if (editing && initial) {
        await updateWebhookRepo(initial.id, input);
        onSaved();
      } else {
        setRevealed(await createWebhookRepo(input));
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  async function rotate() {
    if (!initial) return;
    setBusy(true);
    setError(null);
    try {
      setRevealed(await rotateWebhookSecret(initial.id));
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  async function onVerify() {
    if (!selectedProvider) return;
    const reqId = ++verifyReq.current;
    setVerify({ state: 'checking' });
    try {
      const result: RepoCheck = await verifyRepo(selectedProvider.id, target);
      if (reqId !== verifyReq.current) return; // input changed while in flight — drop the stale result
      setVerify(result.ok ? { state: 'ok' } : { state: 'fail', detail: result.detail ?? 'Not reachable' });
    } catch (err) {
      if (reqId !== verifyReq.current) return;
      setVerify({ state: 'fail', detail: err instanceof Error ? err.message : String(err) });
    }
  }

  if (revealed) {
    return <SecretRevealModal result={revealed} rotated={editing} onDone={onSaved} />;
  }

  return (
    <div className="modal-overlay">
      <div className="modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <div className="modal-head">
          <h3>{editing ? 'Edit webhook' : 'Add webhook'}</h3>
          <button className="iconbtn" onClick={onClose} aria-label="Close">
            ✕
          </button>
        </div>
        <form className="modal-body scroll" onSubmit={submit}>
          {noProviders ? (
            <div className="modal-msg">
              Register a provider first under Settings → Providers, then add a webhook for one of its repositories.
            </div>
          ) : (
            <>
              <div className="field-row-2">
                <label className="field">
                  <span>Provider</span>
                  {legacyEdit ? (
                    <div className="mono field-static">
                      {initial!.providerType} · {owner}
                    </div>
                  ) : (
                    <Select
                      ariaLabel="Provider"
                      value={providerId}
                      options={providers.map((p) => ({ value: p.id, label: `${p.name} · ${p.type} · ${p.workspace}` }))}
                      onChange={setProviderId}
                    />
                  )}
                </label>
                <label className="field">
                  <span>Scope</span>
                  <Select
                    ariaLabel="Scope"
                    value={scope}
                    options={SCOPES.map((s) => ({ value: s.value, label: s.label }))}
                    onChange={(v) => setScope(v as WebhookScope)}
                    disabled={legacyEdit}
                  />
                </label>
              </div>

              {scope === 'repo' ? (
                <label className="field">
                  <span>Repository</span>
                  <div className="wh-repo-input">
                    <span className="wh-owner mono">{owner || '—'}/</span>
                    <input
                      className="mono"
                      placeholder="repo-name"
                      value={slug}
                      onChange={(e) => setSlug(e.target.value)}
                      disabled={legacyEdit}
                      autoFocus
                    />
                    <button
                      type="button"
                      className="btn-ghost wh-verify-btn"
                      onClick={() => void onVerify()}
                      disabled={legacyEdit || !selectedProvider || !validSlug || verify.state === 'checking'}
                    >
                      {verify.state === 'checking' ? 'Verifying…' : 'Verify'}
                    </button>
                  </div>
                  {verify.state === 'ok' && <div className="wh-verify ok">Repository found</div>}
                  {verify.state === 'fail' && <div className="wh-verify fail">{verify.detail}</div>}
                  <small className="field-hint">{targetHelp.hint}</small>
                </label>
              ) : (
                <label className="field">
                  <span>Organization</span>
                  <div className="mono field-static">{owner || '—'}</div>
                  <small className="field-hint">{targetHelp.hint}</small>
                </label>
              )}

              {editing && initial && (
                <div className="field">
                  <span>Webhook secret</span>
                  <div className="secret-row">
                    <div className="mono field-static">Stored — write-only</div>
                    <button type="button" className="btn-ghost" onClick={rotate} disabled={busy}>
                      <RotateCw size={14} />
                      Rotate
                    </button>
                  </div>
                  <small className="field-hint">
                    The secret is never shown after creation. Rotate to mint a new one — paste it into the provider’s
                    webhook settings (the old value stops working).
                  </small>
                </div>
              )}

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
            </>
          )}

          {(error ?? loadError) && <div className="modal-msg modal-error">{error ?? loadError}</div>}

          <div className="modal-actions">
            <button type="button" className="btn-ghost" onClick={onClose}>
              Cancel
            </button>
            <button type="submit" className="btn" disabled={busy || (!editing && noProviders)}>
              {busy ? 'Saving…' : editing ? 'Save changes' : 'Add webhook'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

/** A read-only value with an always-visible, labelled Copy button that confirms on click. */
function CopyField({ label, value, hint }: { label: string; value: string; hint?: string }) {
  const [copied, setCopied] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => () => clearTimeout(timer.current), []);

  function copy() {
    void navigator.clipboard?.writeText(value);
    setCopied(true);
    clearTimeout(timer.current);
    timer.current = setTimeout(() => setCopied(false), 1400);
  }

  return (
    <div className="field">
      <span>{label}</span>
      <div className="reveal-value">
        <span className="mono">{value}</span>
        <button type="button" className={`copy-btn ${copied ? 'copied' : ''}`} onClick={copy}>
          {copied ? <Check size={14} /> : <Copy size={14} />}
          {copied ? 'Copied' : 'Copy'}
        </button>
      </div>
      {hint && <small className="field-hint">{hint}</small>}
    </div>
  );
}

/** Provider-specific "what to do on the portal" steps, shown under the freshly revealed URL + secret. */
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
                      <Check size={11} aria-hidden="true" /> {event}
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
      <div className="modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <div className="modal-head">
          <h3>{rotated ? 'New secret generated' : 'Webhook created'}</h3>
          <button className="iconbtn" onClick={onDone} aria-label="Close">
            ✕
          </button>
        </div>
        <div className="modal-body scroll">
          <div className="reveal-warn">
            Copy the secret now — it won’t be shown again. Add both values to the provider’s webhook settings,
            prefixing the path with your public webhook base (e.g. your Cloudflare tunnel URL).
          </div>

          <CopyField
            label="Payload URL (path)"
            value={path}
            hint="Prefix with your public webhook base (e.g. your Cloudflare tunnel URL)."
          />
          <CopyField label="Secret" value={result.secret} />

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
