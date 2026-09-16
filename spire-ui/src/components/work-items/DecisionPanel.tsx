import { useEffect, useRef, useState } from 'react';
import { getWorkItem, type WorkItemDetail } from '../../api';
import { canAdminister } from '../../auth';
import { useMe } from '../../hooks/useMe';
import SidePanel from '../SidePanel';
import SettingField from '../SettingField';
import DecisionEvidence from './DecisionEvidence';
import * as approvalsApi from './approvalsApi';
import { preparationEvidence, type PreparationEvidence } from './workPreparationApi';

interface Props {
  itemId: string;
  /** The ticket title the list already read, so the panel does not read the tracker a second time. */
  title: string | null;
  onClose: () => void;
  onDecided: (notice: string) => void;
}

interface Loaded { approval: approvalsApi.Approval | null; item: WorkItemDetail; evidence: PreparationEvidence | null; evidenceError: string }

/**
 * One open decision, beside the list it came from. It shows what the gate binds — the specification,
 * the step, the tree it starts from, the agent and the limits — before it offers Approve, because an
 * approval is a promise to spend within those limits and the old card showed none of it.
 */
export default function DecisionPanel({ itemId, title, onClose, onDecided }: Props) {
  const { me } = useMe();
  const admin = canAdminister(me);
  const [loaded, setLoaded] = useState<Loaded | null>(null), [error, setError] = useState('');
  const [note, setNote] = useState(''), [answering, setAnswering] = useState<boolean | null>(null);
  // Reuse an answer identity after a transport failure, but never attach it to a different answer or note.
  const attempt = useRef<{ key: string; approve: boolean; note: string } | null>(null);
  const live = useRef(true);
  useEffect(() => { live.current = true; return () => { live.current = false; }; }, []);

  useEffect(() => {
    let current = true;
    setLoaded(null); setError('');
    Promise.all([approvalsApi.approvals(false), getWorkItem(itemId)]).then(async ([open, item]) => {
      const approval = open.find(row => row.workItemId === itemId) ?? null;
      let evidence: PreparationEvidence | null = null, evidenceError = '';
      if (approval && item.preparation && admin) {
        try { evidence = await preparationEvidence(itemId); } catch (failure) { evidenceError = String(failure); }
      }
      if (current) setLoaded({ approval, item, evidence, evidenceError });
    }).catch(failure => { if (current) setError(String(failure)); });
    return () => { current = false; };
  }, [itemId, admin]);

  async function decide(approve: boolean) {
    const gate = loaded?.approval?.gate;
    if (!gate) return;
    const previous = attempt.current;
    const decision = previous && previous.approve === approve && previous.note === note ? previous : { key: crypto.randomUUID(), approve, note };
    attempt.current = decision; setAnswering(approve); setError('');
    try {
      await approvalsApi.answer(gate, decision.key, approve, note);
      if (live.current) onDecided(approve ? `Approved the ${gate.phase} decision. The item continues.` : `Rejected the ${gate.phase} decision. The item stops here.`);
    } catch (failure) { if (live.current) setError(String(failure)); }
    finally { if (live.current) setAnswering(null); }
  }

  const gate = loaded?.approval?.gate;
  const answeringNow = answering !== null;
  const heading = gate ? `Approve the ${gate.phase}` : 'Decision';
  const subtitle = !loaded ? undefined : title ? `${title} · ${loaded.item.issueKey} · ${loaded.item.repository}` : `${loaded.item.issueKey} · ${loaded.item.repository}`;
  return <SidePanel title={heading} subtitle={subtitle} busy={answering !== null} onClose={onClose} wide
    actions={<>
      {/* The footer sits outside the panel's fieldset, so the lock is repeated here. */}
      {admin && gate && <button className="btn" type="button" disabled={answeringNow} onClick={() => void decide(true)}>{answering === true ? 'Approving…' : 'Approve'}</button>}
      {admin && gate && <button className="btn-ghost danger" type="button" disabled={answeringNow} onClick={() => void decide(false)}>{answering === false ? 'Rejecting…' : 'Reject'}</button>}
      <button className="btn-ghost" type="button" disabled={answeringNow} onClick={onClose}>{gate ? 'Cancel' : 'Close'}</button>
    </>}>
    {!loaded && !error && <p className="prov-note" role="status" aria-busy="true">Loading the decision…</p>}
    {loaded && !gate && <p className="prov-note">This item has no open decision. It may have been answered, expired or replaced.</p>}
    {loaded && gate && <>
      <DecisionEvidence item={loaded.item} approval={loaded.approval!} evidence={loaded.evidence} evidenceError={loaded.evidenceError} />
      {admin ? <SettingField label="Decision note" scope="approval" hint="Optional. Recorded with the decision and shown in the item's history.">
        <textarea value={note} onChange={event => setNote(event.target.value)} placeholder="Why you approve or reject" /></SettingField>
        : <p className="prov-note">Only an administrator can answer this decision.</p>}
      {answering !== null && <p className="prov-note" role="status">Recording your decision. The panel unlocks when the server answers.</p>}
    </>}
    {error && <p className="prov-error" role="alert">{error}</p>}
  </SidePanel>;
}
