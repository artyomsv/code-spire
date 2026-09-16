import { useEffect, useRef, useState } from 'react';
import { actorLabel, type ActorResult } from '../../actorsApi';
import * as api from '../../work-items/workSourcesApi';
import SettingField from '../../SettingField';
import FactoryStep from './FactoryStep';

interface Props {
  sources: api.WorkSource[];
  open: string | null;
  setOpen: (open: string | null) => void;
  changed: (notice?: string) => void;
}

/**
 * Finds one person on a source's tracker and allows them. A lookup that answers after the handle
 * changed, or after the form moved to another source, must not become a selection: it would allow
 * someone the operator never looked at.
 */
function AllowPerson({ source, cancelled, changed }: { source: api.WorkSource; cancelled: () => void; changed: Props['changed'] }) {
  const [handle, setHandle] = useState(''), [selected, setSelected] = useState('');
  const [resolution, setResolution] = useState<ActorResult | null>(null);
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  const request = useRef(0);
  useEffect(() => () => { request.current++; }, []);
  async function find() {
    const asked = ++request.current;
    setBusy(true); setError(''); setResolution(null); setSelected('');
    try {
      const result = await api.resolveWorkActor(source.id, handle);
      if (asked !== request.current) return;
      setResolution(result);
      setSelected(result.status === 'FOUND' && result.actors.length === 1 ? result.actors[0].providerUserId : '');
    } catch (failure) { if (asked === request.current) setError(String(failure)); }
    finally { if (asked === request.current) setBusy(false); }
  }
  async function allow() {
    setBusy(true); setError('');
    try { await api.saveWorkActor(source, handle, selected); changed(`Allowed ${handle} on ${source.name}.`); }
    catch (failure) { setError(String(failure)); setBusy(false); }
  }
  const chosen = resolution?.actors.find(actor => actor.providerUserId === selected);
  return <fieldset className="form-lock factory-form" aria-label={`Allow a person on ${source.name}`} disabled={busy}>
    {source.type === 'JIRA' && <p className="factory-note">Jira Cloud display names are not unique. Find the person, then pick the account explicitly. Data Center person lookup is currently unavailable.</p>}
    <SettingField label="Person" scope="allowed people" hint="Required. The tracker handle to find. Nothing is allowed until you confirm the person the tracker returns.">
      <input aria-label="Person" value={handle} onChange={event => { request.current++; setHandle(event.target.value); setResolution(null); setSelected(''); }} /></SettingField>
    <div className="prov-actions"><button className="btn-ghost" type="button" disabled={busy || !handle.trim()} onClick={() => void find()}>
      {busy ? 'Working…' : 'Find person'}</button></div>
    {busy && <p className="factory-note" role="status">Asking the tracker. The form unlocks when it answers.</p>}
    {resolution?.detail && <p className="factory-note">{resolution.detail}</p>}
    {resolution && (resolution.actors.length > 1 || resolution.status === 'SELECTION_REQUIRED')
      ? <SettingField label="Resolved source person" scope="allowed people" hint="Required. More than one account matched, or the tracker needs an explicit choice. Pick the one to allow.">
        <select aria-label="Resolved source person" value={selected} onChange={event => setSelected(event.target.value)}>
          <option value="">Select a person</option>{resolution.actors.map(actor => <option key={actor.providerUserId} value={actor.providerUserId}>{actorLabel(actor)} · {actor.providerUserId}</option>)}
        </select></SettingField>
      : chosen && <p className="factory-candidate"><b>{actorLabel(chosen)}</b> <span className="prov-sub">{chosen.providerUserId}</span></p>}
    {error && <p className="prov-error" role="alert">{error}</p>}
    <div className="prov-actions">
      <button className="btn" type="button" disabled={busy || !chosen} onClick={() => void allow()}>
        {busy && chosen ? 'Allowing…' : chosen ? `Allow ${actorLabel(chosen)}` : 'Allow person'}</button>
      <button className="btn-ghost" type="button" onClick={cancelled}>Cancel</button></div>
  </fieldset>;
}

export default function PeopleStep({ sources, open, setOpen, changed }: Props) {
  // Holds the person being removed, so only that row reports work — not every row at once.
  const [busy, setBusy] = useState<string | null>(null), [error, setError] = useState('');
  const counted = sources.some(source => source.enabled && source.allowedPeople.length > 0);
  async function remove(source: api.WorkSource, id: string, label: string) {
    setBusy(id); setError('');
    try { await api.removeWorkActor(source, id); changed(`Removed ${label} from ${source.name}.`); }
    catch (failure) { setError(String(failure)); } finally { setBusy(null); }
  }
  return <FactoryStep number={2} question="Who may start work" term="allowed people"
    state={open?.startsWith('people') ? 'editing' : counted ? 'done' : 'missing'}
    status={sources.length > 0 && !counted && <span className="chip warn">nobody yet</span>}>
    {sources.length === 0 && <p className="factory-note">Add a source in step 1 first. People are allowed per source.</p>}
    <p className="factory-note">Only labels these people add can start work. A label from anyone else is ignored, however it is named.</p>
    {sources.map(source => <section key={source.id} className="factory-group" aria-label={`Allowed people on ${source.name}`}>
      {sources.length > 1 && <h5>{source.name}</h5>}
      <ul className="prov-list">{source.allowedPeople.map(person => <li key={person.providerUserId}>
        <span><span className="prov-name">{actorLabel(person)}</span><span className="prov-sub">{person.providerUserId}</span></span>
        <button className="btn-ghost sm" type="button" disabled={busy !== null || open !== null} aria-label={`Remove ${actorLabel(person)}`}
          onClick={() => void remove(source, person.providerUserId, actorLabel(person))}>
          {busy === person.providerUserId ? 'Removing…' : 'Remove'}</button></li>)}</ul>
      {open === `people:${source.id}`
        ? <AllowPerson key={source.id} source={source} cancelled={() => setOpen(null)} changed={changed} />
        : <button className={source.allowedPeople.length ? 'btn-ghost sm' : 'btn sm'} type="button" disabled={busy !== null || open !== null || !source.enabled}
          onClick={() => setOpen(`people:${source.id}`)} aria-label={`Add a person to ${source.name}`}>Add person</button>}
    </section>)}
    {error && <p className="prov-error" role="alert">{error}</p>}
  </FactoryStep>;
}
