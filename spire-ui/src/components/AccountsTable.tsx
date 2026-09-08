import {
  BookOpen,
  CheckCircle2,
  CircleDashed,
  CircleX,
  ClipboardList,
  ExternalLink,
  GitFork,
  LoaderCircle,
  UsersRound,
  type LucideIcon,
} from 'lucide-react';
import { type ContextProviderView, type ProviderView } from '../api';
import { CopyableValue } from '../render';
import IconButton from './IconButton';
import Tooltip from './Tooltip';
import { lastCheckedTitle, type LastChecked } from './lastChecked';
import { accountKind, hostOf, roleLabel, type AccountKind } from './accounts';
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
 * <p>The connection is ONE icon in four states, and the Policy cell is a count and a word. The
 * connection cell used to print the login the token authenticated as beside the date of the last
 * check, and on a refusal the provider's own message as well — three variable-length strings in
 * one column, the longest of them a paragraph. All of it is on the tooltip now, which is where an
 * operator looks once, not on every row. The state word went with it: an operator scanning eleven
 * rows reads the colour and the shape, and the word only when a row stops them.
 *
 * <p>A value with no bound on its length — a base URL, a bot login, a workspace — is bounded here,
 * ellipses, and carries the whole value in its tooltip and in what its copy button copies. The
 * operator pastes these into other people's portals, so truncating without offering the copy would
 * take the value away rather than shorten it.
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
                <div className="prov-sub cell-cap">
                  <CopyableValue text={p.baseUrl} mono copyTitle="Copy the base URL" />
                </div>
              </td>
              <td className="nowrap">
                <KindCell kind="Forge" type={p.type} />
              </td>
              <td className="nowrap">{roleLabel(p.role)}</td>
              <td className="mono nowrap" style={mono}>
                <IdentityCell value={identityOf(p)} resolved={Boolean(p.botUsername || p.botAccountId)} />
              </td>
              <td className="mono nowrap" style={mono}>
                <div className="cell-cap">
                  <CopyableValue text={p.workspace} mono copyTitle="Copy the workspace" />
                </div>
              </td>
              <td className="nowrap">
                <ConnBadge conn={conns[p.id]} stored={p} enabled={p.enabled} onRecheck={() => onRecheck(p.id)} />
              </td>
              <td className="nowrap">
                <PolicyCell provider={p} />
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
                <div className="prov-sub cell-cap">
                  <CopyableValue text={t.baseUrl} mono copyTitle="Copy the base URL" />
                </div>
              </td>
              <td className="nowrap">
                <KindCell kind={accountKind(t.type)} type={t.type} />
              </td>
              <td className="nowrap">Read</td>
              <td className="mono nowrap" style={mono}>
                <div className="cell-cap">
                  <CopyableValue text={t.username ?? ''} mono copyTitle="Copy the identity" />
                </div>
              </td>
              <td className="mono nowrap" style={mono}>
                <div className="cell-cap">
                  <CopyableValue text={hostOf(t.baseUrl)} mono copyTitle="Copy the host" />
                </div>
              </td>
              <td className="nowrap">
                <StoredBadge stored={t} />
              </td>
              <td className="nowrap">
                <span className="prov-sub">—</span>
              </td>
              <td>
                <div className="prov-actions">
                  {/* An icon, like the Edit and Delete buttons it lines up with. The words took the
                      width of two of them and, squeezed, wrapped onto a second line — which is what
                      made the tracker rows taller than the forge rows above them. */}
                  <Tooltip label="Manage on Context">
                    <a
                      className="icon-btn"
                      href={`#/settings/context?edit=${encodeURIComponent(t.id)}`}
                      aria-label="Manage on Context"
                    >
                      <ExternalLink size={16} />
                    </a>
                  </Tooltip>
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
 * What kind of account this is, as an icon, followed by the type the registry stored.
 *
 * <p>The category word read "Forge · " on six rows and "Tracker · " on four: a column of the same
 * three words, spending the width the identity beside it needed. The word is not dropped — it is
 * the icon's accessible name and the cell's tooltip, so a hover and a screen reader both say it.
 */
function KindCell({ kind, type }: { kind: AccountKind; type: string }) {
  const Icon = kind === 'Forge' ? GitFork : kind === 'Tracker' ? ClipboardList : BookOpen;
  return (
    <span className="kind-cell" title={`${kind} account`}>
      <Icon size={14} role="img" aria-label={`${kind} account`} />
      <span className="mono">{type}</span>
    </span>
  );
}

/**
 * The login the forge knows this bot by, copyable — it is the value an operator pastes into a
 * repository's reviewer list. "not resolved" is a sentence about the account rather than a value,
 * so it gets no copy button: copying it would hand over two words instead of an identity.
 */
function IdentityCell({ value, resolved }: { value: string; resolved: boolean }) {
  if (!resolved) return <span className="prov-sub">{value}</span>;
  return (
    <div className="cell-cap">
      <CopyableValue text={value} mono copyTitle="Copy the identity" />
    </div>
  );
}

/** The login the forge knows the bot by; the id when only that resolved; a plain word when neither did. */
function identityOf(p: ProviderView): string {
  if (p.botUsername) return `@${p.botUsername}`;
  if (p.botAccountId) return p.botAccountId;
  return 'not resolved';
}

/** The four standings a credential can be in. The word is the icon's label and its tooltip. */
const CONN_LABEL: Record<ConnState, string> = {
  idle: 'Not checked',
  checking: 'Checking…',
  ok: 'OK',
  fail: 'Failed',
};

/** One glyph per standing. Distinct in shape as well as colour — a colour alone is not a state. */
const CONN_ICON: Record<ConnState, LucideIcon> = {
  idle: CircleDashed,
  checking: LoaderCircle,
  ok: CheckCircle2,
  fail: CircleX,
};

/**
 * The live connection check, as one badge of fixed width.
 *
 * <p>Everything variable is on the tooltip: the login the token authenticated as, when the
 * credential was last checked, and — the widest of the three — the provider's own words when it
 * refused. The badge itself only says which of the four states this account is in, because that is
 * the part an operator scans a column of eleven rows for.
 */
function ConnBadge({
  conn,
  stored,
  enabled,
  onRecheck,
}: {
  conn: Conn | undefined;
  stored: LastChecked;
  enabled: boolean;
  onRecheck: () => void;
}) {
  // No stored result yet: an enabled provider is being auto-checked; a disabled
  // one was skipped on purpose and sits idle until the operator clicks to check.
  const state = conn?.state ?? (enabled ? 'checking' : 'idle');
  const said =
    state === 'idle'
      ? 'Disabled — not checked automatically. Click to check anyway.'
      : state === 'checking'
        ? 'Contacting the provider…'
        : state === 'ok'
          ? `Connected${conn?.account ? ` as @${conn.account}` : ''}`
          : (conn?.detail ?? 'Connection failed');
  // The stored standing dates the LAST check, which the live result may already have replaced —
  // it is worth a hover on an idle or a failing row and noise on a row that just answered.
  const history = state === 'ok' || state === 'checking' ? '' : lastCheckedTitle(stored);
  const clickable = state === 'ok' || state === 'fail' ? ' — click to re-check' : '';
  const Icon = CONN_ICON[state];
  return (
    <button
      type="button"
      className={`conn-badge conn-${state}`}
      onClick={onRecheck}
      disabled={state === 'checking'}
      aria-label={CONN_LABEL[state]}
      title={`${CONN_LABEL[state]} · ${[said, history].filter(Boolean).join(' · ')}${clickable}`}
    >
      <Icon size={16} aria-hidden="true" />
    </button>
  );
}

/**
 * A tracker's stored standing, in the same badge. Nothing checks it from here — the account is
 * registered and checked on Context — so this one is a plain span: a control that looks like the
 * button beside it and does nothing on click would be worse than the date it replaced.
 */
function StoredBadge({ stored }: { stored: LastChecked }) {
  const state: ConnState =
    stored.lastCheckAt === null || stored.lastCheckOk === null ? 'idle' : stored.lastCheckOk ? 'ok' : 'fail';
  const Icon = CONN_ICON[state];
  return (
    <span
      className={`conn-badge conn-static conn-${state}`}
      role="img"
      aria-label={CONN_LABEL[state]}
      title={`${CONN_LABEL[state]} · ${lastCheckedTitle(stored) || 'Never checked'}`}
    >
      <Icon size={16} aria-hidden="true" />
    </span>
  );
}

/**
 * What this bot is allowed to do, in two marks: how many stable ids may command it, and how far it
 * converses. Both were sentences — "0 ids · Inherit (global)" — for two settings whose value an
 * operator compares down the column rather than reads. The count keeps a head-count icon instead of
 * the word "ids", the level drops the parenthesis the form needs, and each says itself in full on
 * its own tooltip.
 *
 * <p>A Factory account has neither: they are read through the REVIEWER lookup, so a number here
 * would be dead data with an edit control implied beside it.
 */
function PolicyCell({ provider }: { provider: ProviderView }) {
  if (provider.role !== 'REVIEWER') return <span className="prov-sub">—</span>;
  const ids = provider.authors.length;
  return (
    <span className="policy-cell">
      <span className="policy-bit" title={`${ids} stable ids may command this bot`}>
        <UsersRound size={12} aria-hidden="true" />
        {ids}
      </span>
      <span className="policy-bit" title={`Conversation: ${conversationLabel(provider.conversationLevel)}`}>
        {shortConversation(provider.conversationLevel)}
      </span>
    </span>
  );
}

/** The form says "Inherit (global)" with room to spare; a table cell has none for the parenthesis. */
function shortConversation(level: string | null | undefined): string {
  const label = conversationLabel(level);
  return label === 'Inherit (global)' ? 'Inherit' : label;
}
