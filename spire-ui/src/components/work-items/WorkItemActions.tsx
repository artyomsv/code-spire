import { useEffect, useRef, useState } from 'react';
import { resumeWorkItem, type WorkItemDetail } from '../../api';
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
  const [busy, setBusy] = useState<'resume' | 'readmit' | 'compose' | null>(null), [error, setError] = useState('');
  const [note, setNote] = useState('');
  async function resume(readmit: boolean) {
    const number = started(); setBusy(readmit ? 'readmit' : 'resume'); setError('');
    try {
      const outcome = item.workflowStatus === 'suspended' ? await resumeWorkItem(item, readmit, note) : await resumeWorkItem(item, readmit);
      if (active.current) changed(outcome.detail ? workRefusal(outcome.reason, outcome.detail) : undefined, number);
    }
    catch (failure) { if (active.current) setError(String(failure)); } finally { if (active.current) setBusy(null); }
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
  const canResume = ['awaiting_input', 'capability_unavailable', 'suspended'].includes(item.workflowStatus);
  const canReadmit = ['not_eligible', 'stopped', 'failed', 'completed', 'awaiting_input', 'capability_unavailable'].includes(item.workflowStatus);
  if (item.workflowStatus === 'suspended' && !coordinates)
    return <p className="factory-note">This item has no branch to re-observe, so it cannot resume. Its prepared task or pull request is missing.</p>;
  // Composing is offered wherever a person could want the ticket read again: before anything is
  // prepared, and after an edit they made on purpose.
  // waiting_approval included on purpose: an assisted item sits at its plan gate, which is exactly when
  // an operator reads the ticket again and edits it. The server refuses anything a build has started.
  const canCompose = ['awaiting_input', 'capability_unavailable', 'not_eligible', 'stopped', 'waiting_approval'].includes(item.workflowStatus);
  if (!canResume && !canReadmit && !canCompose) return null;
  return <div className="work-actions">
    {item.workflowStatus === 'suspended' && <SettingField label="Resume note" scope="work item" hint="Required. Why work may continue after a person took over; recorded with the resume.">
      <textarea aria-label="Resume note" disabled={busy !== null} value={note} onChange={event => setNote(event.target.value)} /></SettingField>}
    <div className="prov-actions">
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
