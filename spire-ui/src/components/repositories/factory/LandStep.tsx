import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import SettingField from '../../SettingField';
import { workReason } from '../../work-items/workReasons';
import FactoryStep from './FactoryStep';
import { writeLive } from '../../../liveCache';
import { landOptions, landOptionsKey, landSettingsKey, saveLandSettings, type LandOptions, type LandSettings, type MergeMethod, type TicketRule } from './landSettingsApi';

interface Props {
  repositoryId: string;
  settings: LandSettings;
  open: string | null;
  setOpen: (open: string | null) => void;
  changed: (notice?: string) => void;
  /** Re-reads the tab. A save refused for a stale revision can only be retried against the current one. */
  reload: () => void;
}

/** The two ticket rules in the operator's own words (spec §6). */
const TICKET_RULE_LABEL: Record<TicketRule, string> = {
  comment: 'Keep the status and post a comment that the work is merged, with the PR link',
  close: 'Close the ticket',
};
const TICKET_RULE_SHORT: Record<TicketRule, string> = { comment: 'a comment on the ticket', close: 'the ticket closed' };
const MAX_FIX_ATTEMPTS = 10;
/** What the form offers when the server itself did not answer. */
const UNCHECKED: LandOptions = { checked: false, allowed: ['merge', 'squash', 'rebase'] };
const validAttempts = (typed: string) => /^\d+$/.test(typed) && Number(typed) <= MAX_FIX_ATTEMPTS;

/**
 * How this repository lands a delivered pull request (M4 land, spec §7.1).
 *
 * <p>There is always an answer: with nothing saved, the operator's defaults apply. GitHub's allowed
 * methods are read only while the form is open, so viewing the tab never calls the forge; a method GitHub
 * no longer allows is named there, and the land itself checks it again before any merge.
 */
export default function LandStep({ repositoryId, settings, open, setOpen, changed, reload }: Props) {
  const live = useRef(true);
  useEffect(() => { live.current = true; return () => { live.current = false; }; }, []);
  const [form, setForm] = useState({ mergeMethod: settings.mergeMethod, ticketRule: settings.ticketRule, fixAttempts: String(settings.fixAttempts) });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const editing = open === 'land';

  const client = useQueryClient();
  const optionsRead = useQuery({ queryKey: landOptionsKey(repositoryId), queryFn: () => landOptions(repositoryId), enabled: editing });
  // The server already answers "unchecked" for a forge that did not reply; this covers the server itself.
  // A failed read wins over an older cached answer: the forge may allow more methods now (review of PR 358, M-4).
  const options: LandOptions | null = optionsRead.isError ? UNCHECKED : optionsRead.data ?? null;

  async function submit() {
    setSaving(true); setError('');
    try {
      const saved = await saveLandSettings(repositoryId, { expectedRevision: settings.revision, mergeMethod: form.mergeMethod, ticketRule: form.ticketRule,
        fixAttempts: Number(form.fixAttempts) });
      writeLive(client, landSettingsKey(repositoryId), saved);
      if (live.current) changed(`Land setup saved: ${form.mergeMethod}, ${TICKET_RULE_SHORT[form.ticketRule]}, up to ${form.fixAttempts} agent fixes.`);
    } catch (failure) { if (live.current) setError(String(failure instanceof Error ? failure.message : failure)); }
    finally { if (live.current) setSaving(false); }
  }

  // Before GitHub answers, only the saved method is offered; nothing is judged until then.
  const offered: MergeMethod[] = options ? options.allowed : [form.mergeMethod];
  const methodAllowed = offered.includes(form.mergeMethod);
  const attemptsValid = validAttempts(form.fixAttempts);
  const summary = `${settings.mergeMethod}, ${TICKET_RULE_SHORT[settings.ticketRule]} and up to ${settings.fixAttempts} agent fixes`;
  return <FactoryStep number={6} question="How it lands" term="land setup" state={editing ? 'editing' : 'done'}
    actions={!editing && <button className="btn-ghost sm" type="button" disabled={open !== null} onClick={() => setOpen('land')}>Change</button>}>
    {settings.revision > 0
      ? <div className="factory-row"><b>{settings.mergeMethod} · {TICKET_RULE_SHORT[settings.ticketRule]} · up to {settings.fixAttempts} agent fixes</b></div>
      : <p className="factory-note">Not saved, so it lands with {summary}.</p>}
    <p className="factory-note">The item branch is deleted after the merge. A refused merge caused by the code is fixed by the agent,
      up to the attempts set here; setting 0 attempts hands every refusal to you.</p>
    {editing && <fieldset className="form-lock factory-form" aria-label="Set up the land" disabled={saving}>
      <SettingField label="Merge method" scope="land setup" hint="How GitHub merges the pull request. Only the methods the repository allows are listed.">
        <select aria-label="Merge method" value={methodAllowed ? form.mergeMethod : ''}
          onChange={event => setForm(previous => ({ ...previous, mergeMethod: event.target.value as MergeMethod }))}>
          {!methodAllowed && <option value="">Choose a method</option>}
          {offered.map(method => <option key={method} value={method}>{method}</option>)}
        </select></SettingField>
      {options && !options.checked && <p className="factory-note" role="status">
        GitHub did not say which methods this repository allows, so all three are offered. A method it refuses is handed to you before any merge.</p>}
      {!methodAllowed && <p className="prov-error">GitHub no longer allows {form.mergeMethod} on this repository. Choose one it allows.</p>}
      <fieldset className="factory-form" aria-label="Ticket after the merge">
        <legend className="field-label">Ticket after the merge</legend>
        {(Object.keys(TICKET_RULE_LABEL) as TicketRule[]).map(rule => <label key={rule} className="field-check">
          <input type="radio" name={`ticket-rule-${repositoryId}`} value={rule} checked={form.ticketRule === rule}
            onChange={() => setForm(previous => ({ ...previous, ticketRule: rule }))} /><span>{TICKET_RULE_LABEL[rule]}</span></label>)}
      </fieldset>
      <SettingField label="Fix attempts" scope="land setup" hint="How many agent fixes one land may spend on failed checks or a conflict. A seat costs nothing extra; an API key does.">
        <input aria-label="Fix attempts" type="number" min={0} max={MAX_FIX_ATTEMPTS} step={1} value={form.fixAttempts}
          onChange={event => setForm(previous => ({ ...previous, fixAttempts: event.target.value }))} /></SettingField>
      {!attemptsValid && <p className="prov-error">{workReason('fix_attempts_invalid')}</p>}
      {error && <p className="prov-error" role="alert">{error}</p>}
      {/* A stale revision cannot be retried from this form: every attempt resends the number it loaded. */}
      {error.includes('Reload it') && <div className="prov-actions">
        <button className="btn-ghost sm" type="button" onClick={reload}>Reload the saved land setup</button></div>}
      <div className="prov-actions">
        <button className="btn" type="button" disabled={saving || !methodAllowed || !attemptsValid} onClick={() => void submit()}>
          {saving ? 'Saving…' : 'Save land setup'}</button>
        <button className="btn-ghost" type="button" disabled={saving} onClick={() => setOpen(null)}>Cancel</button></div>
    </fieldset>}
  </FactoryStep>;
}
