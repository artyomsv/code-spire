import { type ProviderView } from '../api';
import IconButton from './IconButton';
import LastChecked from './LastCheckedBadge';
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
  conns: Record<string, Conn>;
  onRecheck: (id: string) => void;
  onEdit: (p: ProviderView) => void;
  onDelete: (p: ProviderView) => void;
}

/** The machine-accounts table. The page owns loading and the modals; this only renders rows. */
export default function AccountsTable({ providers, conns, onRecheck, onEdit, onDelete }: Props) {
  return (
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
              <ConnCell conn={conns[p.id]} enabled={p.enabled} onRecheck={() => onRecheck(p.id)} />
              <LastChecked item={p} />
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
              <span className="prov-sub">{conversationLabel(p.conversationLevel)}</span>
            </td>
            <td>
              <div className="prov-actions">
                <IconButton kind="edit" onClick={() => onEdit(p)} title="Edit" aria-label="Edit" />
                <IconButton kind="delete" onClick={() => onDelete(p)} title="Delete" aria-label="Delete" />
              </div>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
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
