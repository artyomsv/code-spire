import { type ContextProviderView, type ProviderView } from '../api';
import IconButton from './IconButton';
import LastChecked from './LastCheckedBadge';
import { accountKind, hostOf, roleLabel } from './accounts';
import { conversationLabel } from './ProviderFormModal';

// Per-provider connectivity status, keyed by provider id.
export type ConnState = 'idle' | 'checking' | 'ok' | 'fail';
export interface Conn {
  state: ConnState;
  account?: string | null;
  detail?: string | null;
}

interface Props {
  providers: ProviderView[];
  trackers: ContextProviderView[];
  conns: Record<string, Conn>;
  onRecheck: (id: string) => void;
  onEdit: (p: ProviderView) => void;
  onDelete: (p: ProviderView) => void;
}

/**
 * The machine-accounts table: forge accounts first (edited here), then tracker and knowledge
 * accounts (read-only, managed on Context). One list answers "who acts as what"; two registries
 * still back it, which is why the second kind links out rather than opening a form here.
 *
 * <p>Reviewer-only columns show a dash on a Factory row. The allowlist and the conversation level
 * are read through the REVIEWER lookup alone, so on a factory row they are dead data, and showing
 * a number there would invite editing it.
 */
export default function AccountsTable({ providers, trackers, conns, onRecheck, onEdit, onDelete }: Props) {
  const mono = { fontSize: 12, color: 'var(--text-2)' } as const;
  return (
    <table className="prov-table">
      <thead>
        <tr>
          <th>Name</th>
          <th>Kind</th>
          <th>Role</th>
          <th>Identity</th>
          <th>Scope</th>
          <th>Connection</th>
          <th>Enabled</th>
          <th className="cell-r">May command</th>
          <th>Conversation</th>
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
            <td className="mono" style={mono}>{`Forge · ${p.type}`}</td>
            <td>{roleLabel(p.role)}</td>
            <td className="mono" style={mono}>
              {identityOf(p)}
            </td>
            <td className="mono" style={mono}>
              {p.workspace}
            </td>
            <td>
              <ConnCell conn={conns[p.id]} enabled={p.enabled} onRecheck={() => onRecheck(p.id)} />
              <LastChecked item={p} />
            </td>
            <td>
              <span className={`pill ${p.enabled ? 'completed' : 'cancelled'}`}>
                <span className="glyph"></span>
                {p.enabled ? 'Enabled' : 'Disabled'}
              </span>
            </td>
            <td className="cell-r mono" style={mono}>
              {p.role === 'REVIEWER' ? p.authors.length : '—'}
            </td>
            <td>
              <span className="prov-sub">{p.role === 'REVIEWER' ? conversationLabel(p.conversationLevel) : '—'}</span>
            </td>
            <td>
              <div className="prov-actions">
                <IconButton kind="edit" onClick={() => onEdit(p)} title="Edit" aria-label="Edit" />
                <IconButton kind="delete" onClick={() => onDelete(p)} title="Delete" aria-label="Delete" />
              </div>
            </td>
          </tr>
        ))}
        {trackers.map((t) => (
          <tr key={`context-${t.id}`}>
            <td>
              <div className="prov-name">{t.name}</div>
              <div className="prov-sub">{t.baseUrl}</div>
            </td>
            <td className="mono" style={mono}>{`${accountKind(t.type)} · ${t.type}`}</td>
            <td>Read</td>
            <td className="mono" style={mono}>
              {t.username ?? '—'}
            </td>
            <td className="mono" style={mono}>
              {hostOf(t.baseUrl)}
            </td>
            <td>
              <LastChecked item={t} />
            </td>
            <td>
              <span className={`pill ${t.enabled ? 'completed' : 'cancelled'}`}>
                <span className="glyph"></span>
                {t.enabled ? 'Enabled' : 'Disabled'}
              </span>
            </td>
            <td className="cell-r mono" style={mono}>
              —
            </td>
            <td>
              <span className="prov-sub">—</span>
            </td>
            <td>
              <div className="prov-actions">
                <a className="btn-ghost" href={`#/settings/context?edit=${encodeURIComponent(t.id)}`}>
                  Manage on Context
                </a>
              </div>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** The login the forge knows the bot by; the id when only that resolved; a plain word when neither did. */
function identityOf(p: ProviderView): string {
  if (p.botUsername) return `@${p.botUsername}`;
  if (p.botAccountId) return p.botAccountId;
  return 'not resolved';
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
          ? conn?.account
            ? `@${conn.account}`
            : 'Connected'
          : 'Failed';
  const title =
    state === 'idle'
      ? 'Disabled — not checked automatically. Click to check anyway.'
      : state === 'checking'
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
