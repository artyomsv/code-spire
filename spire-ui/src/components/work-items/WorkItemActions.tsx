import { useEffect, useRef, useState } from 'react';
import { resumeWorkItem, type WorkItemDetail } from '../../api';
import { canAdminister } from '../../auth';
import { useMe } from '../../hooks/useMe';
import SettingField from '../SettingField';
import { workRefusal } from './workReasons';

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
  const [busy, setBusy] = useState<'resume' | 'readmit' | null>(null), [error, setError] = useState('');
  const [note, setNote] = useState('');
  async function resume(readmit: boolean) {
    const number = started(); setBusy(readmit ? 'readmit' : 'resume'); setError('');
    try {
      const outcome = item.workflowStatus === 'suspended' ? await resumeWorkItem(item, readmit, note) : await resumeWorkItem(item, readmit);
      if (active.current) changed(outcome.detail ? workRefusal(outcome.reason, outcome.detail) : undefined, number);
    }
    catch (failure) { if (active.current) setError(String(failure)); } finally { if (active.current) setBusy(null); }
  }
  if (!canAdminister(me)) return null;
  // A suspended item is resumed by re-observing a branch: the pull request's, or the prepared base.
  // Without either the server can only refuse, so no button is offered for it.
  const coordinates = item.preparation != null || item.progress?.execution?.pullRequest != null;
  const canResume = ['awaiting_input', 'capability_unavailable', 'suspended'].includes(item.workflowStatus);
  const canReadmit = ['not_eligible', 'stopped', 'failed', 'completed', 'awaiting_input', 'capability_unavailable'].includes(item.workflowStatus);
  if (item.workflowStatus === 'suspended' && !coordinates)
    return <p className="factory-note">This item has no branch to re-observe, so it cannot resume. Its prepared task or pull request is missing.</p>;
  if (!canResume && !canReadmit) return null;
  return <div className="work-actions">
    {item.workflowStatus === 'suspended' && <SettingField label="Resume note" scope="work item" hint="Required. Why work may continue after a person took over; recorded with the resume.">
      <textarea aria-label="Resume note" disabled={busy !== null} value={note} onChange={event => setNote(event.target.value)} /></SettingField>}
    <div className="prov-actions">
      {canResume && <button className="btn sm" type="button" disabled={busy !== null || item.workflowStatus === 'suspended' && !note.trim()} onClick={() => void resume(false)}>
        {busy === 'resume' ? 'Rechecking…' : 'Recheck and resume'}</button>}
      {canReadmit && <button className="btn-ghost sm" type="button" disabled={busy !== null} onClick={() => void resume(true)}>
        {busy === 'readmit' ? 'Re-admitting…' : 'Re-admit under current policy'}</button>}
    </div>
    {busy && <p className="factory-note" role="status">Working. The buttons unlock when the server answers.</p>}
    {error && <p className="prov-error" role="alert">{error}</p>}
  </div>;
}
