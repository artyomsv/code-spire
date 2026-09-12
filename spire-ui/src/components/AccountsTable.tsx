import { type ProviderView } from '../api';
import { CopyableValue } from '../render';
import IconButton from './IconButton';
import { hostOf, scopeLabel } from './accounts';
import {
  ConnBadge,
  EnabledDot,
  IdentityCell,
  KindCell,
  PolicyCell,
  type Conn,
} from './AccountsCells';

// The cells live next door; the two types travel with them, and are re-exported here because the
// screen that owns the check results imports them from the table it hands them to.
export type { Conn, ConnState } from './AccountsCells';

interface Props {
  providers: ProviderView[];
  conns: Record<string, Conn>;
  onRecheck: (id: string) => void;
  onEdit: (p: ProviderView) => void;
  onDelete: (p: ProviderView) => void;
}

/** A mono cell's type, hoisted: it closes over nothing and a fresh object per cell per render is waste. */
const MONO = { fontSize: 12, color: 'var(--text-2)' } as const;

/** Accounts own every credential. Usage comes from roles and source references. */
export default function AccountsTable({ providers, conns, onRecheck, onEdit, onDelete }: Props) {
  return (
    // Eight columns that each refuse to wrap can still outgrow a narrow window. Scrolling the table
    // sideways is the honest answer; wrapping them was the three-line row this replaced.
    <div className="prov-scroll">
      <table className="prov-table">
        <thead>
          <tr>
            <th>Name</th>
            <th>Kind</th>
            <th>Used by</th>
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
                <div className="account-scopes" title={scopeLabel(p.reportedScopes)}>{scopeLabel(p.reportedScopes)}</div>
              </td>
              <td className="nowrap">
                <KindCell kind={p.type === 'atlassian' ? 'Tracker' : 'Forge'} type={p.type} />
              </td>
              <td className="nowrap"><div className="account-uses" title={p.usedBy?.join(', ') ?? 'Usage unavailable'}>{(p.usedBy?.join(', ') ?? 'Usage unavailable') || '—'}</div></td>
              <td className="mono nowrap" style={MONO}>
                <IdentityCell provider={p} />
              </td>
              <td className="mono nowrap" style={MONO}>
                <div className="cell-cap">
                  <CopyableValue text={p.workspace ?? hostOf(p.baseUrl)} mono copyTitle="Copy the workspace" />
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
        </tbody>
      </table>
    </div>
  );
}
