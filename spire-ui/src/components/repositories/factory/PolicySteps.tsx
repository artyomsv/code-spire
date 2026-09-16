import { useState } from 'react';
import * as policyApi from '../../work-items/workPolicyApi';
import { key, pin, policyInput, rowsOf, type MappingRow } from '../../work-items/policyInput';
import PhaseStrip from '../../work-items/PhaseStrip';
import SettingField from '../../SettingField';
import FactoryStep from './FactoryStep';
import PresetForm from './PresetForm';

interface Props {
  repositoryId: string;
  policy: policyApi.Policy;
  profiles: policyApi.Profile[];
  open: string | null;
  setOpen: (open: string | null) => void;
  changed: (notice?: string) => void;
  reload: () => void;
}

const versionOptions = (profiles: policyApi.Profile[]) => profiles.map(profile =>
  <option key={key(profile)} value={key(profile)}>{profile.name} v{profile.version}</option>);

/** Save one form's policy, surfacing the server's words rather than a stack-shaped string. */
async function save(repositoryId: string, input: Parameters<typeof policyApi.savePolicy>[1], setBusy: (busy: boolean) => void, setError: (error: string) => void) {
  setBusy(true); setError('');
  try { await policyApi.savePolicy(repositoryId, input); return true; }
  catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); return false; }
  finally { setBusy(false); }
}

export function CeilingStep({ repositoryId, policy, profiles, open, setOpen, changed }: Props) {
  const [ceiling, setCeiling] = useState(policy.ceiling ? key(policy.ceiling) : '');
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  const editing = open === 'ceiling';
  async function submit() {
    const chosen = profiles.find(profile => key(profile) === ceiling);
    if (!chosen) return;
    const mappings = Object.fromEntries(Object.entries(policy.mappings).map(([label, profile]) => [label, pin(profile)]));
    if (await save(repositoryId, { revision: policy.revision, ceiling: pin(chosen), mappings }, setBusy, setError)) changed(`Ceiling set to ${chosen.name} v${chosen.version}.`);
  }
  return <FactoryStep number={3} question="The most it may ever do" term="ceiling" state={editing ? 'editing' : policy.ceiling ? 'done' : 'missing'}
    actions={!editing && <button className={policy.ceiling ? 'btn-ghost sm' : 'btn sm'} type="button" disabled={open !== null || profiles.length === 0}
      onClick={() => setOpen('ceiling')}>{policy.ceiling ? 'Change' : 'Choose ceiling'}</button>}>
    {policy.ceiling
      ? <div className="factory-row"><b>{policy.ceiling.name} v{policy.ceiling.version}</b><PhaseStrip modes={policy.ceiling.modes} /></div>
      : <p className="factory-note">No ceiling, so every label is refused.{profiles.length === 0 && ' Create a profile first, or use the presets in step 4.'}</p>}
    <p className="factory-note">A label can ask for less than this, never more. Anything above it is cut back.</p>
    {editing && <fieldset className="form-lock factory-form" aria-label="Choose the ceiling" disabled={busy}>
      <SettingField label="Repository ceiling" scope="repository policy" hint="Required. The most any ticket in this repository may do, whatever its label asks for.">
        <select aria-label="Repository ceiling" required value={ceiling} onChange={event => setCeiling(event.target.value)}>
          <option value="">Select a version</option>{versionOptions(profiles)}</select></SettingField>
      {error && <p className="prov-error" role="alert">{error}</p>}
      <div className="prov-actions">
        <button className="btn" type="button" disabled={busy || !ceiling} onClick={() => void submit()}>{busy ? 'Saving…' : 'Save ceiling'}</button>
        <button className="btn-ghost" type="button" onClick={() => setOpen(null)}>Cancel</button></div>
    </fieldset>}
  </FactoryStep>;
}

function LabelRows({ rows, setRows, profiles }: { rows: MappingRow[]; setRows: (update: (rows: MappingRow[]) => MappingRow[]) => void; profiles: policyApi.Profile[] }) {
  const change = (index: number, patch: Partial<MappingRow>) => setRows(current => current.map((row, i) => i === index ? { ...row, ...patch } : row));
  return <>{rows.map((row, index) => <div className="factory-mapping" key={index}>
    <SettingField label={`Label ${index + 1}`} scope="label mappings" hint="Required. The exact ticket label, for example spire:assisted.">
      <input aria-label={`Label ${index + 1}`} value={row.label} onChange={event => change(index, { label: event.target.value })} /></SettingField>
    <SettingField label={`Label profile ${index + 1}`} scope="label mappings" hint="Required. The saved profile version this label asks for.">
      <select aria-label={`Label profile ${index + 1}`} value={row.profile} onChange={event => change(index, { profile: event.target.value })}>
        <option value="">Select a version</option>{versionOptions(profiles)}</select></SettingField>
    <button className="btn-ghost sm" type="button" onClick={() => setRows(current => current.filter((_, i) => i !== index))}>Remove label {index + 1}</button>
  </div>)}</>;
}

export function LabelsStep({ repositoryId, policy, profiles, open, setOpen, changed, reload }: Props) {
  const [rows, setRows] = useState<MappingRow[]>(() => rowsOf(policy));
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  const mappings = Object.entries(policy.mappings);
  async function submit() {
    let input;
    try { input = policyInput(policy.revision, policy.ceiling ? key(policy.ceiling) : '', rows, profiles); }
    catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); return; }
    if (await save(repositoryId, input, setBusy, setError)) changed('Label mappings saved.');
  }
  const editing = open === 'labels';
  return <FactoryStep number={4} question="What each label asks for" term="ticket labels"
    state={editing || open === 'presets' ? 'editing' : mappings.length ? 'done' : 'missing'}
    actions={!editing && open !== 'presets' && <>
      <button className={mappings.length ? 'btn-ghost sm' : 'btn sm'} type="button" disabled={open !== null} onClick={() => setOpen('presets')}>Use presets</button>
      <button className="btn-ghost sm" type="button" disabled={open !== null || !policy.ceiling}
        onClick={() => { setRows(rowsOf(policy)); setError(''); setOpen('labels'); }}>Edit labels</button></>}>
    {mappings.length === 0
      ? <p className="factory-note">No label starts work yet.{!policy.ceiling && ' Choose the ceiling in step 3 first, or let the presets set it.'}</p>
      : <ul className="factory-list">{mappings.map(([label, profile]) => <li key={label} className="factory-row">
        <span className="chip mono">{label}</span><span className="factory-arrow" aria-hidden="true">→</span>
        <span>{profile.name} v{profile.version}</span><PhaseStrip modes={profile.modes} /></li>)}</ul>}
    {open === 'presets' && <PresetForm repositoryId={repositoryId} policy={policy} profiles={profiles} cancelled={() => setOpen(null)} changed={changed} reload={reload} />}
    {editing && <fieldset className="form-lock factory-form" aria-label="Edit label mappings" disabled={busy}>
      <LabelRows rows={rows} setRows={setRows} profiles={profiles} />
      <div className="prov-actions"><button className="btn-ghost sm" type="button" onClick={() => setRows(current => [...current, { label: '', profile: '' }])}>Add label mapping</button></div>
      {error && <p className="prov-error" role="alert">{error}</p>}
      <div className="prov-actions">
        <button className="btn" type="button" disabled={busy} onClick={() => void submit()}>{busy ? 'Saving…' : 'Save labels'}</button>
        <button className="btn-ghost" type="button" onClick={() => setOpen(null)}>Cancel</button></div>
    </fieldset>}
  </FactoryStep>;
}
