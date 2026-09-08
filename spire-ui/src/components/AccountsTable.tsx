import { ExternalLink } from 'lucide-react';
import { type ContextProviderView, type ProviderView } from '../api';
import { CopyableValue } from '../render';
import IconButton from './IconButton';
import Tooltip from './Tooltip';
import { accountKind, hostOf, roleLabel } from './accounts';
import {
  ConnBadge,
  EnabledDot,
  IdentityCell,
  KindCell,
  PolicyCell,
  StoredBadge,
  type Conn,
} from './AccountsCells';

// The cells live next door; the two types travel with them, and are re-exported here because the
// screen that owns the check results imports them from the table it hands them to.
export type { Conn, ConnState } from './AccountsCells';

interface Props {
  providers: ProviderView[];
  trackers: ContextProviderView[];
  conns: Record<string, Conn>;
  onRecheck: (id: string) => void;
  onEdit: (p: ProviderView) => void;
  onDelete: (p: ProviderView) => void;
}

/** A mono cell's type, hoisted: it closes over nothing and a fresh object per cell per render is waste. */
const MONO = { fontSize: 12, color: 'var(--text-2)' } as const;

/**
 * The machine-accounts table: forge accounts first (edited here), then tracker and knowledge
 * accounts (read-only, managed on Context). One list answers "who acts as what"; two registries
 * still back it, which is why the second kind links out rather than opening a form here.
 *
 * <p>Reviewer-only columns show a dash on a Factory row. The allowlist and the conversation level
 * are read through the REVIEWER lookup alone, so on a factory row they are dead data, and showing
 * a number there would invite editing it.
 *
 * <p>Every cell but the name is one line — `nowrap` on the td. A row that wrapped its kind and
 * stacked a date under a login stood three lines tall, and ten of those stopped reading as a list.
 * The Name cell keeps its second line (the base URL) because that is the house pattern here.
 *
 * <p>Eight columns, because ten did not fit the card on a laptop and the squeezed last column is
 * what made the tracker rows tall. Enabled is one bit, so it is a dot beside the name rather than a
 * column; the two reviewer-only settings answer one question — what this bot is allowed to do — so
 * they share the Policy cell. The connection is one icon and the kind is another: what each stands
 * for is its accessible name and its tooltip, never a word repeated down eleven rows.
 *
 * <p>A value with no bound on its length — a base URL, a bot login, a workspace — is bounded,
 * ellipses, and carries the whole value in its tooltip and in what its copy button copies. The
 * operator pastes these into other people's portals, so truncating without offering the copy would
 * take the value away rather than shorten it.
 */
export default function AccountsTable({ providers, trackers, conns, onRecheck, onEdit, onDelete }: Props) {
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
              <td className="mono nowrap" style={MONO}>
                <IdentityCell provider={p} />
              </td>
              <td className="mono nowrap" style={MONO}>
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
              <td className="mono nowrap" style={MONO}>
                <div className="cell-cap">
                  <CopyableValue text={t.username ?? ''} mono copyTitle="Copy the identity" />
                </div>
              </td>
              <td className="mono nowrap" style={MONO}>
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
