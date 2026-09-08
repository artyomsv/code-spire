import {
  BookOpen,
  CheckCircle2,
  CircleDashed,
  CircleX,
  ClipboardList,
  GitFork,
  LoaderCircle,
  UsersRound,
  type LucideIcon,
} from 'lucide-react';
import { type ProviderView } from '../api';
import { CopyableValue } from '../render';
import { lastCheckedTitle, type LastChecked } from './lastChecked';
import { type AccountKind } from './accounts';
import { conversationLabel } from './ProviderFormModal';

/**
 * The cells of the machine-accounts table. Split out of `AccountsTable` when three rounds of
 * fitting the table to the page took that file past the 250-line component cap: the table itself
 * is one component and these are eight, every one of them a cell of that table and used nowhere
 * else. They live together because they share the four connection states and the reason those
 * states are drawn rather than written.
 */

// Per-provider connectivity status, keyed by provider id.
export type ConnState = 'idle' | 'checking' | 'ok' | 'fail';
export interface Conn {
  state: ConnState;
  account?: string | null;
  detail?: string | null;
}

/**
 * Enabled, as a dot beside the name. It is one bit and it spent a whole column of a table that has
 * to fit eight, so the word moves to the title and to the accessible label.
 *
 * <p>`role="img"` is load-bearing, not decoration: a bare `<span>` maps to the ARIA `generic` role,
 * which cannot be named, so browsers ignore `aria-label` AND `title` on it. Without the role this
 * dot has no accessible name at all and the state is carried by colour alone.
 *
 * <p>No test can hold that. jsdom's name computation does not implement the prohibition, so
 * `getByLabelText` and `toHaveAccessibleName` both pass with the role deleted — measured, not
 * assumed. The rule is enforced by this comment and by review; the same applies to the icon labels
 * on {@link KindCell} and {@link PolicyCell}.
 */
export function EnabledDot({ enabled }: { enabled: boolean }) {
  const word = enabled ? 'Enabled' : 'Disabled';
  return (
    <span className={`enabled-dot ${enabled ? 'on' : 'off'}`} role="img" title={word} aria-label={word} />
  );
}

/**
 * What kind of account this is, as an icon, followed by the type the registry stored.
 *
 * <p>The category word read "Forge · " on six rows and "Tracker · " on four: a column of the same
 * three words, spending the width the identity beside it needed. The word is not dropped — it is
 * the icon's accessible name and the cell's tooltip, so a hover and a screen reader both say it.
 */
export function KindCell({ kind, type }: { kind: AccountKind; type: string }) {
  const Icon = kind === 'Forge' ? GitFork : kind === 'Tracker' ? ClipboardList : BookOpen;
  return (
    <span className="kind-cell" title={`${kind} account`}>
      <Icon size={14} role="img" aria-label={`${kind} account`} />
      <span className="mono">{type}</span>
    </span>
  );
}

/**
 * The login the forge knows this bot by.
 *
 * <p>Shown with the `@` an operator recognises and copied without it: the value goes into a forge's
 * own reviewer list or allowlist, which wants the bare handle. "not resolved" is a sentence about
 * the account rather than a value, so it gets no copy button — copying it would hand over two
 * words instead of an identity.
 */
export function IdentityCell({ provider }: { provider: ProviderView }) {
  const login = provider.botUsername;
  const id = provider.botAccountId;
  if (!login && !id) return <span className="prov-sub">not resolved</span>;
  const copy = login ?? id ?? '';
  return (
    <div className="cell-cap">
      <CopyableValue text={copy} display={login ? `@${login}` : copy} mono copyTitle="Copy the identity" />
    </div>
  );
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
 * What the registry last stored about this credential, as a state. `idle` means nothing is stored,
 * never "nothing was checked this session" — a rejected token that is sitting disabled must not
 * read as unknown, which is what a hard-coded `idle` for every disabled account said.
 */
export function storedState(stored: LastChecked): ConnState {
  if (stored.lastCheckAt === null || stored.lastCheckOk === null) return 'idle';
  return stored.lastCheckOk ? 'ok' : 'fail';
}

/**
 * The connection check, as one icon of fixed width.
 *
 * <p>Everything variable is on the tooltip: the login the token authenticated as, when the
 * credential was last checked, and the server's category for a refusal. (That category is one of a
 * fixed set keyed on the HTTP status — the provider's own response body never reaches the browser,
 * and restoring it would be a way to put a token on a hover.) The icon says only which of the four
 * states this account is in, because that is what an operator scans a column of eleven rows for.
 *
 * <p>The accessible name states the action as well as the state: this is a button, and "OK" alone
 * tells someone who cannot see the tooltip nothing about what pressing it does.
 */
export function ConnBadge({
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
  // No live result: an enabled provider is being auto-checked; a disabled one was skipped on
  // purpose, so it reports what the registry stored rather than claiming nothing is known.
  const state = conn?.state ?? (enabled ? 'checking' : storedState(stored));
  const said = checkDetail(state, conn, enabled);
  // The stored standing dates the LAST check, which a live result may already have replaced — it is
  // worth a hover on a row that has not just answered, and noise on one that has.
  const history = conn && (state === 'ok' || state === 'checking') ? '' : lastCheckedTitle(stored);
  const clickable = state === 'checking' ? '' : ' — click to check';
  const Icon = CONN_ICON[state];
  return (
    <button
      type="button"
      className={`conn-badge conn-${state}`}
      onClick={onRecheck}
      disabled={state === 'checking'}
      aria-label={state === 'checking' ? CONN_LABEL[state] : `${CONN_LABEL[state]} — check the connection`}
      title={[CONN_LABEL[state], said, history].filter(Boolean).join(' · ') + clickable}
    >
      <Icon size={16} aria-hidden="true" />
    </button>
  );
}

/** What to say about the check itself, given who answered it. The call to action is added once. */
function checkDetail(state: ConnState, conn: Conn | undefined, enabled: boolean): string {
  if (state === 'checking') return 'Contacting the provider…';
  if (!conn) return enabled ? '' : 'Disabled — not checked automatically';
  if (state === 'ok') return `Connected${conn.account ? ` as @${conn.account}` : ''}`;
  return conn.detail ?? 'Connection failed';
}

/**
 * A tracker's stored standing, in the same icon. Nothing checks it from here — the account is
 * registered and checked on Context — so this one is a plain span: a control that looks like the
 * button beside it and does nothing on click would be worse than the date it replaced.
 */
export function StoredBadge({ stored }: { stored: LastChecked }) {
  const state = storedState(stored);
  const Icon = CONN_ICON[state];
  return (
    <span
      className={`conn-badge conn-static conn-${state}`}
      role="img"
      aria-label={CONN_LABEL[state]}
      title={[CONN_LABEL[state], lastCheckedTitle(stored)].filter(Boolean).join(' · ')}
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
 * its own tooltip and as its accessible name.
 *
 * <p>A Factory account has neither: they are read through the REVIEWER lookup, so a number here
 * would be dead data with an edit control implied beside it.
 */
export function PolicyCell({ provider }: { provider: ProviderView }) {
  if (provider.role !== 'REVIEWER') return <span className="prov-sub">—</span>;
  const ids = provider.authors.length;
  const commanders = `${ids} stable ids may command this bot`;
  const conversation = `Conversation: ${conversationLabel(provider.conversationLevel)}`;
  return (
    <span className="policy-cell">
      <span className="policy-bit" role="img" aria-label={commanders} title={commanders}>
        <UsersRound size={12} aria-hidden="true" />
        {ids}
      </span>
      <span className="policy-bit" role="img" aria-label={conversation} title={conversation}>
        {shortConversation(provider.conversationLevel)}
      </span>
    </span>
  );
}

/**
 * The conversation level as a table cell says it: the form's "Inherit (global)" has no room for its
 * parenthesis here.
 *
 * <p>It switches on the LEVEL, not on the label the form renders. Comparing the label made two
 * silent failures: a reworded label widened the cell back, and a level this build has never heard
 * of fell through to "Inherit" — telling an operator that a bot follows the global default when
 * nobody knows what it follows. That is the `refused`-as-five-green-segments shape, and the house
 * answer beside it is `roleLabel`'s: name the unknown.
 */
export function shortConversation(level: string | null | undefined): string {
  switch (level) {
    case 'REPORT_ONLY':
      return 'Report-only';
    case 'EXPLAIN':
      return 'Explain';
    case 'INTERACTIVE':
      return 'Interactive';
    case null:
    case undefined:
    case '':
      return 'Inherit';
    default:
      return `Unknown (${level})`;
  }
}
