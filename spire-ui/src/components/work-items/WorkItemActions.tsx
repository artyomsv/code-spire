import { useEffect, useRef, useState } from 'react';
import { resumeWorkItem, retryLand, stopLand, type WorkItemDetail } from '../../api';
import { canAdminister } from '../../auth';
import { useMe } from '../../hooks/useMe';
import SettingField from '../SettingField';
import { composePreparation } from './workPreparationApi';
import { workReason, workRefusal } from './workReasons';

interface Props {
  item: WorkItemDetail;
  /** Starting an action; the page clears what an earlier action said and numbers this one. */
  started: () => number;
  /** The item changed. `notice` names the rule that stopped a recheck; `started` is the number this action was given. */
  changed: (notice: string | undefined, started: number) => void;
}

/**
 * Recheck and re-admission for an item that stopped. A suspended item needs a note, because resuming
 * after a human took over is a decision someone has to be able to read back.
 */
export default function WorkItemActions({ item, started, changed }: Props) {
  const { me } = useMe();
  const active = useRef(true);
  useEffect(() => { active.current = true; return () => { active.current = false; }; }, []);
  const [busy, setBusy] = useState<'resume' | 'readmit' | 'compose' | 'retry' | 'stop' | null>(null), [error, setError] = useState('');
  const [note, setNote] = useState(''), [landNote, setLandNote] = useState('');
  async function resume(readmit: boolean) {
    const number = started(); setBusy(readmit ? 'readmit' : 'resume'); setError('');
    try {
      const outcome = item.workflowStatus === 'suspended' ? await resumeWorkItem(item, readmit, note) : await resumeWorkItem(item, readmit);
      if (active.current) changed(outcome.detail ? workRefusal(outcome.reason, outcome.detail) : undefined, number);
    }
    catch (failure) { if (active.current) setError(String(failure)); } finally { if (active.current) setBusy(null); }
  }
  /** Retry land or Stop on an item waiting at land (spec §7.6); both record the operator and the note. */
  async function land(action: 'retry' | 'stop') {
    const number = started(); setBusy(action); setError('');
    try {
      const outcome = action === 'retry' ? await retryLand(item, landNote) : await stopLand(item, landNote);
      if (active.current) changed(outcome.detail ? workRefusal(outcome.reason, outcome.detail) : undefined, number);
    }
    catch (failure) { if (active.current) setError(String(failure instanceof Error ? failure.message : failure)); }
    finally { if (active.current) setBusy(null); }
  }
  /**
   * The specification is a SNAPSHOT of the ticket. An edit after preparation does not change what was
   * approved — that is the point — so composing again is a deliberate act, and it supersedes an open
   * decision because the texts a new decision binds are new.
   */
  async function compose() {
    const number = started(); setBusy('compose'); setError('');
    try {
      const outcome = await composePreparation(item.id, item.revision);
      if (active.current) changed(workReason(outcome.reason), number);
    }
    catch (failure) { if (active.current) setError(String(failure instanceof Error ? failure.message : failure)); }
    finally { if (active.current) setBusy(null); }
  }
  if (!canAdminister(me)) return null;
  // A suspended item is resumed by re-observing a branch: the pull request's, or the prepared base.
  // Without either the server can only refuse, so no button is offered for it.
  const coordinates = item.preparation != null || item.progress?.execution?.pullRequest != null;
  // A landed item cannot be built again beside its merged pull request, and an item handed to the operator at
  // land goes on with Retry land or Stop (spec §7.6, §7.9). The server refuses readmit and recheck there, so neither
  // is offered. Waiting at land for any other reason, such as a tracker outage, is still answered by recheck.
  // Readmit is refused at land whenever the item waits, handed or not: its delivered pull request is still open (A8).
  // Whether the item was handed over, and whether Stop ends it, are the server's to say: it holds the reasons and the
  // land row (review of the branch). Stop reaches a land that cannot merge here, one that is off, and one that has
  // sent no merge yet; Retry land follows only a hand-off, since it opens a land gate.
  const landed = item.workflowStatus === 'completed' && item.progress?.execution?.landing != null;
  const handedAtLand = item.land?.handedOver === true;
  const waitingAtLand = item.phase === 'land' && ['awaiting_input', 'capability_unavailable', 'not_eligible'].includes(item.workflowStatus);
  const stoppableAtLand = item.land?.stoppable === true;
  const canResume = !handedAtLand && ['awaiting_input', 'capability_unavailable', 'suspended'].includes(item.workflowStatus);
  // A land fix keeps its pull request open; readmit would build a second one beside it. Only the server sees the history.
  const fixing = item.land?.fixInProgress === true;
  const canReadmit = !landed && !waitingAtLand && !fixing
    && ['not_eligible', 'stopped', 'failed', 'completed', 'awaiting_input', 'capability_unavailable'].includes(item.workflowStatus);
  if (item.workflowStatus === 'suspended' && !coordinates)
    return <p className="factory-note">This item has no branch to re-observe, so it cannot resume. Its prepared task or pull request is missing.</p>;
  // Composing is offered wherever a person could want the ticket read again: before anything is
  // prepared, and after an edit they made on purpose.
  // waiting_approval included on purpose: an assisted item sits at its plan gate, which is exactly when
  // an operator reads the ticket again and edits it. The server refuses anything a build has started.
  // The server refuses a composition once this generation has attempted a phase, because replacing the
  // task under a build that already ran needs an explicit re-admission. Offering the button there means
  // offering one that can only fail. The builds this generation already has are the visible half of that
  // rule — an approximation of work_phase_attempt, and deliberately the conservative one: it hides a
  // button the server might have allowed rather than offering one it must refuse.
  const attempted = (item.builds ?? []).some(build => build.generation === item.generation);
  const canCompose = !attempted
    && ['awaiting_input', 'capability_unavailable', 'not_eligible', 'stopped', 'waiting_approval'].includes(item.workflowStatus);
  if (!canResume && !canReadmit && !canCompose && !stoppableAtLand) return null;
  return <div className="work-actions">
    {item.workflowStatus === 'suspended' && <SettingField label="Resume note" scope="work item" hint="Required. Why work may continue after a person took over; recorded with the resume.">
      <textarea aria-label="Resume note" disabled={busy !== null} value={note} onChange={event => setNote(event.target.value)} /></SettingField>}
    {stoppableAtLand && <SettingField label="Land note" scope="work item" hint="Required. Why land may be tried again, or why it stops here; recorded with the item. Stop leaves the pull request open, and re-admitting afterwards builds the work again.">
      <textarea aria-label="Land note" disabled={busy !== null} value={landNote} onChange={event => setLandNote(event.target.value)} /></SettingField>}
    <div className="prov-actions">
      {handedAtLand && <button className="btn sm" type="button" disabled={busy !== null || !landNote.trim()} onClick={() => void land('retry')}>
        {busy === 'retry' ? 'Retrying land…' : 'Retry land'}</button>}
      {stoppableAtLand && <button className="btn-ghost danger sm" type="button" disabled={busy !== null || !landNote.trim()} onClick={() => void land('stop')}>
        {busy === 'stop' ? 'Stopping…' : 'Stop land'}</button>}
      {canResume && <button className="btn sm" type="button" disabled={busy !== null || item.workflowStatus === 'suspended' && !note.trim()} onClick={() => void resume(false)}>
        {busy === 'resume' ? 'Rechecking…' : 'Recheck and resume'}</button>}
      {canReadmit && <button className="btn-ghost sm" type="button" disabled={busy !== null} onClick={() => void resume(true)}>
        {busy === 'readmit' ? 'Re-admitting…' : 'Re-admit under current policy'}</button>}
      {canCompose && <button className="btn-ghost sm" type="button" disabled={busy !== null} onClick={() => void compose()}>
        {busy === 'compose' ? 'Preparing…' : 'Prepare again from the ticket'}</button>}
    </div>
    {busy && <p className="factory-note" role="status">Working. The buttons unlock when the server answers.</p>}
    {error && <p className="prov-error" role="alert">{error}</p>}
  </div>;
}
