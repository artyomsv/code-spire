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
 *
 * <p>Every cell but the name is one line — `nowrap` on the td, and the connection button beside
 * its last-check badge rather than above it. A row that wrapped its kind and stacked a date under
 * a login stood three lines tall, and ten of those stopped reading as a list. The Name cell keeps
 * its second line (the base URL) because that is the house pattern for a name in these tables.
 *
 * <p>Eight columns, because ten did not fit the card on a laptop and the squeezed last column is
 * what made the tracker rows tall. Enabled is one bit, so it is a dot beside the name rather than a
 * column; the two reviewer-only settings answer one question — what this bot is allowed to do — so
 * they share the Policy cell.
 */
export default function AccountsTable({ providers, trackers, conns, onRecheck, onEdit, onDelete }: Props) {
  const mono = { fontSize: 12, color: 'var(--text-2)' } as const;
  return (
    // Eight columns that each refuse to wrap can still outgrow a narrow window. Scrolling the table
    // sideways is the honest answer; wrapping them was the three-line row this replaced.
    <div className="prov-scroll">
      <table className="prov-table">
        <thead>
          <tr>
            <th>Name</th>
            <th>Kind</th>
            <th>Role</th>
            <th>Identity</th>
            <th>Scope</th>
            <th>Connection</th>
            <th>Policy</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {providers.map((p) => (
            <tr key={p.id}>
              <td>
                <div className="prov-name">
                  <EnabledDot enabled={p.enabled} />
                  {p.name}
                </div>
                <div className="prov-sub">{p.baseUrl}</div>
              </td>
              <td className="mono nowrap" style={mono}>{`Forge · ${p.type}`}</td>
              <td className="nowrap">{roleLabel(p.role)}</td>
              <td className="mono nowrap" style={mono}>
                <Ellipsed value={identityOf(p)} />
              </td>
              <td className="mono nowrap" style={mono}>
                {p.workspace}
              </td>
              <td>
                <div className="conn-line">
                  <ConnCell conn={conns[p.id]} enabled={p.enabled} onRecheck={() => onRecheck(p.id)} />
                  <LastChecked item={p} />
                </div>
              </td>
              <td className="nowrap">
                <span className="prov-sub">
                  {p.role === 'REVIEWER'
                    ? `${p.authors.length} ids · ${conversationLabel(p.conversationLevel)}`
                    : '—'}
                </span>
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
                <div className="prov-name">
                  <EnabledDot enabled={t.enabled} />
                  {t.name}
                </div>
                <div className="prov-sub">{t.baseUrl}</div>
              </td>
              <td className="mono nowrap" style={mono}>{`${accountKind(t.type)} · ${t.type}`}</td>
              <td className="nowrap">Read</td>
              <td className="mono nowrap" style={mono}>
                <Ellipsed value={t.username ?? '—'} />
              </td>
              <td className="mono nowrap" style={mono}>
                {hostOf(t.baseUrl)}
              </td>
              <td>
                <div className="conn-line">
                  <LastChecked item={t} />
                </div>
              </td>
              <td className="nowrap">
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
    </div>
  );
}

/**
 * Enabled, as a dot beside the name. It is one bit and it spent a whole column of a table that has
 * to fit eight, so the word moves to the title and to the accessible label — the dot alone would be
 * a colour with no name.
 */
function EnabledDot({ enabled }: { enabled: boolean }) {
  const word = enabled ? 'Enabled' : 'Disabled';
  return <span className={`enabled-dot ${enabled ? 'on' : 'off'}`} title={word} aria-label={word} />;
}

/**
 * A value with no bound on its length — a bot login, a mail address — held to one line. It ellipses
 * at the column's width and carries the whole value as its own tooltip, so nothing is hidden.
 */
function Ellipsed({ value }: { value: string }) {
  return (
    <span className="cell-ellip" title={value}>
      {value}
    </span>
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
