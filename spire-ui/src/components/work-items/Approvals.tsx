import { useEffect, useState } from 'react';
import { ClipboardCheck } from 'lucide-react';
import { Link } from 'react-router';
import { useMe } from '../../hooks/useMe';
import { canAdminister } from '../../auth';
import * as api from './approvalsApi';

export default function Approvals() {
  const { me } = useMe();
  const [history, setHistory] = useState(false), [refresh, setRefresh] = useState(0);
  const [rows, setRows] = useState<api.Approval[] | null>(null), [error, setError] = useState('');
  useEffect(() => {
    let active = true; setRows(null); setError('');
    api.approvals(history).then(value => { if (active) setRows(value); }).catch(failure => { if (active) setError(String(failure)); });
    return () => { active = false; };
  }, [history, refresh]);
  return <section className="content"><div className="card" style={{ padding: 18 }}>
    <h2>Approvals</h2>
    <label><input type="checkbox" checked={history} onChange={event => setHistory(event.target.checked)} />Show decision history</label>
    <button className="btn" onClick={() => setRefresh(value => value + 1)}>Refresh approvals</button>
    {error && <p role="alert">{error}</p>}
    {!rows ? <p>Loading approvals…</p> : rows.length === 0 ? <div className="wh-empty">
      <div className="wh-empty-icon"><ClipboardCheck size={22} aria-hidden="true" /></div>
      <div className="wh-empty-title">{history ? 'No past decisions.' : 'No open approvals.'}</div>
      <p className="wh-empty-text">{history ? 'Decisions will appear here after an approval is answered or expires.' : 'When a work item needs your approval, its phase and supporting evidence will appear here.'}</p>
    </div> :
      <ul className="work-approvals">{rows.map(row => <li key={`${row.gate.id}:${row.gate.version}`}>
        <Decision row={row} admin={canAdminister(me)} resolved={() => setRefresh(value => value + 1)} />
      </li>)}</ul>}
  </div></section>;
}

function Decision({ row, admin, resolved }: { row: api.Approval; admin: boolean; resolved: () => void }) {
  const [note, setNote] = useState(''), [busy, setBusy] = useState(false), [error, setError] = useState('');
  // Reuse a key after a transport failure, but never attach it to a different answer/note.
  const [attempt, setAttempt] = useState<{ key: string; approve: boolean; note: string } | null>(null);
  async function submit(approve: boolean) {
    const decision = attempt && attempt.approve === approve && attempt.note === note ? attempt : { key: crypto.randomUUID(), approve, note };
    setAttempt(decision); setBusy(true); setError('');
    try { await api.answer(row.gate, decision.key, approve, note); resolved(); }
    catch (failure) { setError(String(failure)); } finally { setBusy(false); }
  }
  return <article>
    <h3><Link to={`/work-items/${encodeURIComponent(row.workItemId)}`}>{row.issueKey}</Link> · {row.gate.phase}</h3>
    <p>Generation {row.gate.generation} · Policy revision {row.gate.policyRevision} · {row.gate.state}</p>
    <p>Expires: <time dateTime={row.gate.expiresAt}>{row.gate.expiresAt}</time></p>
    <p>{row.gate.artifact ? `Artifact or head: ${row.gate.artifact}` : 'No artifact bound to this decision.'}</p>
    <p>PR review: {row.prReviewAvailable ? 'Available' : 'Unavailable'}. {row.prReviewDetail ?? 'Use the dashboard.'}</p>
    {row.gate.state === 'OPEN' && row.trackerCommand && <p>Allowed people may answer on the linked ticket: <code>{row.trackerCommand}</code>. Use /reject with the same binding to refuse.</p>}
    {row.gate.resolver && <p>Decided by {row.gate.resolver} via {row.gate.channel}</p>}
    {row.gate.note && <p>{row.gate.note}</p>}
    {admin && row.gate.state === 'OPEN' && <fieldset className="modal-body" style={{ borderWidth: 0, borderStyle: 'none', margin: 0, minWidth: 0 }} disabled={busy}><legend className="field-sep">Record a decision</legend>
      <label className="field">Decision note<textarea value={note} onChange={event => setNote(event.target.value)} /></label>
      <div className="prov-actions"><button className="btn" onClick={() => void submit(true)}>Approve</button><button className="btn" onClick={() => void submit(false)}>Reject</button></div>
    </fieldset>}
    {error && <p role="alert">{error}</p>}
  </article>;
}
