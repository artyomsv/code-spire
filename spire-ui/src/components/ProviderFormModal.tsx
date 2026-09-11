/**
 * Account identity and role controls. Credentials and reviewer policy live in focused child components.
 */
import { useState } from 'react';
import {
  createProvider,
  updateProvider,
  type AuthKind,
  type ProviderInput,
  type ProviderRole,
  type ProviderView,
} from '../api';
import { roleLabel } from './accounts';
import Select from './Select';
import AccountCredentialFields, { type AccountFields } from './AccountCredentialFields';
export { DeleteConfirmModal } from './DeleteAccountModal';
import ReviewerFieldsSection, { type ReviewerFields } from './ReviewerFieldsSection';

/** Human label for a conversation level; '' / null / unknown = inherit the global default. */
export function conversationLabel(level: string | null | undefined): string {
  switch (level) {
    case 'REPORT_ONLY':
      return 'Report-only';
    case 'EXPLAIN':
      return 'Explain';
    case 'INTERACTIVE':
      return 'Interactive';
    default:
      return 'Inherit (global)';
  }
}

// Provider types and their default API base URLs. When a user switches type
// without having customised the base URL, we swap in the matching default.
const PROVIDER_TYPES = ['bitbucket-cloud', 'github', 'gitlab', 'atlassian'] as const;
const DEFAULT_BASE_URLS: Record<string, string> = {
  'bitbucket-cloud': 'https://api.bitbucket.org/2.0',
  github: 'https://api.github.com',
  gitlab: 'https://gitlab.com/api/v4',
  atlassian: '',
};
// Providers that authenticate with a Bearer token only (no Basic-auth path).
const BEARER_ONLY = new Set(['github', 'gitlab']);
const KNOWN_DEFAULTS = new Set(Object.values(DEFAULT_BASE_URLS));
const DEFAULT_BASE_URL = DEFAULT_BASE_URLS['bitbucket-cloud'];

const ROLE_OPTIONS: { value: ProviderRole; label: string }[] = [
  { value: 'REVIEWER', label: 'Reviewer' },
  { value: 'FACTORY', label: 'Factory' },
];

export default function ProviderFormModal({
  initial,
  onClose,
  onSaved,
}: {
  initial: ProviderView | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const editing = initial !== null;

  const [fields, setFields] = useState<AccountFields>({
    name: initial?.name ?? '',
    type: initial?.type ?? 'bitbucket-cloud',
    baseUrl: initial?.baseUrl ?? DEFAULT_BASE_URL,
    workspace: initial?.workspace ?? '',
    authKind: initial?.authKind ?? 'bearer' as AuthKind,
    authUsername: initial?.authUsername ?? '',
    secret: '',
    botAccountId: initial?.botAccountId ?? '',
    enabled: initial?.enabled ?? true,
  });
  const patch = (next: Partial<typeof fields>) => setFields((previous) => ({ ...previous, ...next }));
  const { name, type, baseUrl, workspace, authKind, authUsername, secret, botAccountId, enabled } = fields;
  const [role, setRole] = useState<ProviderRole>(initial?.role ?? 'REVIEWER');
  const [reviewer, setReviewer] = useState<ReviewerFields>({
    conversationLevel: initial?.conversationLevel ?? '',
    authors: initial?.authors ?? [],
    authorDraft: '',
  });
  const patchReviewer = (patch: Partial<ReviewerFields>) => setReviewer((prev) => ({ ...prev, ...patch }));

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function changeType(next: string) {
    patch({ type: next });
    if (next === 'atlassian') { setRole('CONTEXT'); patch({ workspace: '', authKind: 'basic' }); }
    else if (role === 'CONTEXT' && !editing) setRole('REVIEWER');
    // Swap the base URL to the new type's default unless the user has customised it.
    if (!baseUrl.trim() || KNOWN_DEFAULTS.has(baseUrl.trim())) {
      patch({ baseUrl: DEFAULT_BASE_URLS[next] ?? baseUrl });
    }
    // GitHub and GitLab authenticate with a Bearer token only.
    if (BEARER_ONLY.has(next)) {
      patch({ authKind: 'bearer' });
    }
  }

  function addAuthor() {
    const v = reviewer.authorDraft.trim();
    if (!v || reviewer.authors.includes(v)) {
      patchReviewer({ authorDraft: '' });
      return;
    }
    patchReviewer({ authors: [...reviewer.authors, v], authorDraft: '' });
  }

  function removeAuthor(a: string) {
    patchReviewer({ authors: reviewer.authors.filter((x) => x !== a) });
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!name.trim() || (role !== 'CONTEXT' && !workspace.trim()) || !baseUrl.trim()) {
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
    const draft = reviewer.authorDraft.trim();
    const finalAuthors = draft && !reviewer.authors.includes(draft) ? [...reviewer.authors, draft] : reviewer.authors;

    const input: ProviderInput = {
      name: name.trim(),
      type,
      baseUrl: baseUrl.trim(),
      workspace: role === 'CONTEXT' ? null : workspace.trim(),
      authKind,
      authUsername: authKind === 'basic' ? authUsername.trim() : null,
      botAccountId: botAccountId.trim(),
      enabled,
      authors: role === 'REVIEWER' ? finalAuthors : [],
      conversationLevel: role === 'REVIEWER' && reviewer.conversationLevel ? reviewer.conversationLevel : undefined,
      // Fixed at registration: on edit the stored role goes back as it came. A different one is a 409.
      role: editing && initial ? initial.role : role,
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
    <div className="modal-overlay">
      <div className="modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <div className="modal-head">
          <h3>{editing ? 'Edit account' : 'Add account'}</h3>
          <button className="iconbtn" onClick={onClose} aria-label="Close">
            ✕
          </button>
        </div>
        <form className="modal-body scroll" onSubmit={submit}>
          {type !== 'atlassian' && <label className="field">
            <span>Role</span>
            {editing && initial ? (
              <>
                <div className="field-static">{roleLabel(initial.role)}</div>
                <small className="field-hint">
                  Set at registration. To change it, register a new account with the other role and delete this one.
                </small>
              </>
            ) : (
              <>
                <Select
                  ariaLabel="Role"
                  value={role}
                  options={ROLE_OPTIONS}
                  onChange={(v) => setRole(v as ProviderRole)}
                />
                <small className="field-hint">
                  Reviewer reads pull requests and posts comments. Factory pushes branches and opens pull
                  requests as a separate machine account (ADR-038) — never as the reviewer.
                </small>
              </>
            )}
          </label>}

          <label className="field">
            <span>Name</span>
            <input placeholder="Acme Bitbucket" value={name} onChange={(e) => patch({ name: e.target.value })} autoFocus />
          </label>

          <div className="field-row-12">
            <label className="field">
              <span>Kind</span>
              <Select
                ariaLabel="Kind"
                value={type}
                options={PROVIDER_TYPES.map((t) => ({ value: t, label: t }))}
                disabled={editing}
                onChange={changeType}
              />
            </label>
            {role !== 'CONTEXT' && <label className="field">
              <span>Workspace</span>
              <input
                className="mono"
                placeholder="workspace"
                value={workspace}
                onChange={(e) => patch({ workspace: e.target.value })}
              />
            </label>}
          </div>

          <label className="field">
            <span>Base URL</span>
            <input
              className="mono"
              placeholder={DEFAULT_BASE_URL}
              value={baseUrl}
              onChange={(e) => patch({ baseUrl: e.target.value })}
            />
          </label>

          <AccountCredentialFields fields={fields} patch={patch} initial={initial} role={role} />

          {role === 'REVIEWER' && <ReviewerFieldsSection reviewer={reviewer} patchReviewer={patchReviewer} addAuthor={addAuthor} removeAuthor={removeAuthor} />}

          <label className="field-check">
            <input type="checkbox" checked={enabled} onChange={(e) => patch({ enabled: e.target.checked })} />
            <span>Enabled</span>
          </label>

          {error && <div className="modal-msg modal-error">{error}</div>}

          <div className="modal-actions">
            <button type="button" className="btn-ghost" onClick={onClose}>
              Cancel
            </button>
            <button type="submit" className="btn" disabled={busy}>
              {busy ? 'Saving…' : editing ? 'Save changes' : 'Add account'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
