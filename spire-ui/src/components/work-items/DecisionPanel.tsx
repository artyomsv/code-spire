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

interface Loaded { approval: approvalsApi.Approval | null; item: WorkItemDetail }

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
  const [evidence, setEvidence] = useState<{ value: PreparationEvidence | null; error: string }>({ value: null, error: '' });
  // Reuse an answer identity after a transport failure, but never attach it to a different gate, answer or note.
  const attempt = useRef<{ key: string; gate: string; approve: boolean; note: string } | null>(null);
  const live = useRef(true);
  useEffect(() => { live.current = true; return () => { live.current = false; }; }, []);

  useEffect(() => {
    let current = true;
    setLoaded(null); setError('');
    Promise.all([approvalsApi.approvals(false), getWorkItem(itemId)]).then(([open, item]) => {
      if (current) setLoaded({ approval: open.find(row => row.workItemId === itemId) ?? null, item });
    }).catch(failure => { if (current) setError(String(failure)); });
    return () => { current = false; };
  }, [itemId]);

  // The ticket texts are an admin read, loaded beside the decision rather than before it: the session
  // answers after the panel opens, and waiting for it must not blank a decision already on screen.
  const gateKey = loaded?.approval ? `${loaded.approval.gate.id}:${loaded.approval.gate.version}` : null;
  const preparation = loaded?.item.preparation ?? null;
  const prepared = preparation ? `${preparation.specification.sha256}:${preparation.plan.sha256}` : null;
  useEffect(() => {
    let current = true;
    setEvidence({ value: null, error: '' });
    if (!admin || !gateKey || !prepared) return;
    preparationEvidence(itemId).then(value => { if (current) setEvidence({ value, error: '' }); })
      .catch(failure => { if (current) setEvidence({ value: null, error: String(failure) }); });
    return () => { current = false; };
  }, [itemId, admin, gateKey, prepared]);

  async function decide(approve: boolean) {
    const gate = loaded?.approval?.gate;
    if (!gate) return;
    const previous = attempt.current, bound = `${gate.id}:${gate.version}`;
    const decision = previous && previous.gate === bound && previous.approve === approve && previous.note === note
      ? previous : { key: crypto.randomUUID(), gate: bound, approve, note };
    attempt.current = decision; setAnswering(approve); setError('');
    try {
      await approvalsApi.answer(gate, decision.key, approve, note);
      if (live.current) onDecided(approve ? `Approved the ${gate.phase} decision. The item continues.` : `Rejected the ${gate.phase} decision. The item stops here.`);
    } catch (failure) { if (live.current) setError(String(failure)); }
    finally { if (live.current) setAnswering(null); }
  }

  const gate = loaded?.approval?.gate;
  const answeringNow = answering !== null;
  // Approval is a promise to build what the panel shows. Until the texts for this very preparation
  // are on screen it is not offered; rejecting needs no evidence and stays available.
  const bound = !preparation || !!evidence.value && !evidence.value.reason
    && evidence.value.specificationSha256 === preparation.specification.sha256 && evidence.value.planSha256 === preparation.plan.sha256;
  const mismatched = !!preparation && !!evidence.value && !evidence.value.reason && !bound;
  const heading = gate ? `Approve the ${gate.phase}` : 'Decision';
  const subtitle = !loaded ? undefined : title ? `${title} · ${loaded.item.issueKey} · ${loaded.item.repository}` : `${loaded.item.issueKey} · ${loaded.item.repository}`;
  return <SidePanel title={heading} subtitle={subtitle} busy={answering !== null} onClose={onClose} wide
    actions={<>
      {/* The footer sits outside the panel's fieldset, so the lock is repeated here. */}
      {admin && gate && <button className="btn" type="button" disabled={answeringNow || !bound} onClick={() => void decide(true)}>{answering === true ? 'Approving…' : 'Approve'}</button>}
      {admin && gate && <button className="btn-ghost danger" type="button" disabled={answeringNow} onClick={() => void decide(false)}>{answering === false ? 'Rejecting…' : 'Reject'}</button>}
      <button className="btn-ghost" type="button" disabled={answeringNow} onClick={onClose}>{gate ? 'Cancel' : 'Close'}</button>
    </>}>
    {!loaded && !error && <p className="prov-note" role="status" aria-busy="true">Loading the decision…</p>}
    {loaded && !gate && <p className="prov-note">This item has no open decision. It may have been answered, expired or replaced.</p>}
    {loaded && gate && <>
      <DecisionEvidence item={loaded.item} approval={loaded.approval!} evidence={mismatched ? null : evidence.value} evidenceError={evidence.error} />
      {mismatched && <p className="prov-error" role="alert">The prepared task changed while this panel was open. Close it and open the decision again.</p>}
      {admin && preparation && !evidence.value && !evidence.error && <p className="prov-note" role="status">Reading the tickets. Approve is offered once they are on screen.</p>}
      {admin ? <SettingField label="Decision note" scope="approval" hint="Optional. Recorded with the decision and shown in the item's history.">
        <textarea aria-label="Decision note" value={note} onChange={event => setNote(event.target.value)} placeholder="Why you approve or reject" /></SettingField>
        : <p className="prov-note">Only an administrator can answer this decision.</p>}
      {answering !== null && <p className="prov-note" role="status">Recording your decision. The panel unlocks when the server answers.</p>}
    </>}
    {error && <p className="prov-error" role="alert">{error}</p>}
  </SidePanel>;
}
