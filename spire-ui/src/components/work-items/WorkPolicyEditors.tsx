import { useState } from 'react';
import * as api from './workPolicyApi';
import SettingField from '../SettingField';
import SidePanel from '../SidePanel';
import { key } from './policyInput';

const bounds = { gateTtlSeconds: 'Approval lifetime (seconds)', maxRunsPerItem: 'Maximum runs per item', maxStepsPerPlan: 'Maximum steps per plan',
  maxWallClockSeconds: 'Maximum wall time (seconds)', maxCostMillicents: 'Maximum cost (millicents)', maxCallsPerItem: 'Maximum calls per item' };
const phaseHelp: Record<api.Phase, string> = {
  INTAKE: 'Accept a ticket into the workflow.', SPEC: 'Accept an existing specification reference.', PLAN: 'Accept an existing plan reference.',
  BUILD: 'Run the prepared plan in a sandbox.', VERIFY: 'Production verification is unavailable until M4.',
  DELIVER: 'Publish a branch as a draft or ready pull request.', REVIEW: 'Observe the existing reviewer at the delivered head.', LAND: 'Production landing is unavailable until M4.',
};
const limitHelp: Record<keyof typeof bounds, string> = {
  gateTtlSeconds: 'How long an unanswered approval remains valid; at least one second.',
  maxRunsPerItem: 'Total run attempts allowed for one item. Zero prevents runs.',
  maxStepsPerPlan: 'Maximum plan steps. Zero prevents execution.',
  maxWallClockSeconds: 'Time budget in seconds. Zero prevents execution.',
  maxCostMillicents: 'Spend budget: 100,000 millicents = $1. Zero prevents execution.',
  maxCallsPerItem: 'Maximum model calls for one item. Zero prevents execution.',
};

export function ProfileEditor({ profiles, initial, saved, cancelled }: { profiles: api.Profile[]; initial: string; saved: (profile: api.Profile) => void; cancelled: () => void }) {
  const base = profiles.find(profile => key(profile) === initial);
  const [selected, setSelected] = useState(initial);
  const [name, setName] = useState(base?.name ?? ''), [precedence, setPrecedence] = useState(base?.precedence ?? 0);
  const emptyModes = Object.fromEntries(api.phases.map(phase => [phase, 'off'])) as Record<api.Phase, string>;
  const emptyLimits: api.Limits = { gateTtlSeconds: 86400, maxRunsPerItem: 0, maxStepsPerPlan: 0, maxWallClockSeconds: 0, maxCostMillicents: 0, maxCallsPerItem: 0, protectedPaths: [] };
  const [modes, setModes] = useState(base?.modes ?? emptyModes);
  const [limits, setLimits] = useState<api.Limits>(base?.limits ?? emptyLimits);
  const [paths, setPaths] = useState(base?.limits.protectedPaths.join('\n') ?? ''), [busy, setBusy] = useState(false), [error, setError] = useState('');
  const existing = profiles.find(profile => key(profile) === selected);
  async function submit() {
    setBusy(true); setError('');
    try {
      const created = await api.saveProfile({ id: existing?.id ?? crypto.randomUUID(), name, precedence,
        version: existing ? Math.max(...profiles.filter(profile => profile.id === existing.id).map(profile => profile.version)) + 1 : 1,
        modes, limits: { ...limits, protectedPaths: paths.split('\n').map(path => path.trim()).filter(Boolean) } }); saved(created);
    } catch (failure) { setError(String(failure)); } finally { setBusy(false); }
  }
  return <SidePanel title={base ? `New version of ${base.name} v${base.version}` : 'Add profile'} busy={busy} onClose={cancelled}
    subtitle={base ? 'A saved version never changes; saving creates the next one' : undefined} actions={<>
    <button className="btn" type="button" disabled={busy || !name.trim()} onClick={() => void submit()}>Save as new version</button>
    <button className="btn-ghost" type="button" onClick={cancelled}>Cancel</button></>}>
    <p className="prov-note">Off disables a phase, approve waits for an answer, and auto permits it within the limits below.</p>
    <SettingField label="Base profile" scope="profile" hint="Optional. Start fresh, or copy an existing version as the starting point for the next one. Saved versions never change.">
      <select aria-label="Base profile" value={selected} onChange={event => {
        const value = profiles.find(profile => key(profile) === event.target.value); setSelected(event.target.value);
        setName(value?.name ?? ''); setPrecedence(value?.precedence ?? 0);
        setModes(value?.modes ?? emptyModes); setLimits(value?.limits ?? emptyLimits); setPaths(value?.limits.protectedPaths.join('\n') ?? '');
      }}><option value="">New profile</option>{profiles.map(profile => <option key={key(profile)} value={key(profile)}>{profile.name} v{profile.version}</option>)}</select></SettingField>
    <SettingField label="Profile name" scope="profile" hint="Required. A recognisable name. An existing profile keeps its name across versions.">
      <input aria-label="Profile name" required value={name} readOnly={!!existing} onChange={event => setName(event.target.value)} /></SettingField>
    <SettingField label="Precedence" scope="profile" hint="Required. A unique nonnegative number; lower values win when several eligible labels apply. New versions keep the precedence of the profile they extend.">
      <input aria-label="Precedence" required type="number" min={0} step={1} readOnly={!!existing} value={precedence} onChange={event => setPrecedence(Number(event.target.value))} /></SettingField>
    <h4 className="field-sep">Phase modes</h4>
    <div className="settings-fields">{api.phases.map(phase => <SettingField key={phase} label={`${phase.toLowerCase()} mode`} scope="phase modes" hint={`Required. ${phaseHelp[phase]}`}>
      <select aria-label={`${phase.toLowerCase()} mode`} required value={modes[phase]} onChange={event => setModes(value => ({ ...value, [phase]: event.target.value }))}>
        {(phase === 'DELIVER' ? ['off', 'draft_pr', 'pr'] : phase === 'LAND' ? ['off', 'approve', 'auto_if_green'] : ['off', 'approve', 'auto']).map(mode => <option key={mode}>{mode}</option>)}
      </select></SettingField>)}</div>
    <h4 className="field-sep">Limits</h4>
    <p className="prov-note">A zero maximum stops execution. The built-in CI protected paths always apply.</p>
    <div className="settings-fields">{Object.entries(bounds).map(([field, label]) => <SettingField key={field} label={label} scope="limits" hint={`Required. ${limitHelp[field as keyof typeof bounds]}`}>
      <input aria-label={label} required type="number" step={1} min={field === 'gateTtlSeconds' ? 1 : 0}
        value={limits[field as keyof typeof bounds]} onChange={event => setLimits(value => ({ ...value, [field]: Number(event.target.value) }))} /></SettingField>)}</div>
    <SettingField label="Additional protected paths, one per line" scope="limits"
      hint="Optional. Repository-relative path patterns to protect in addition to the built-in CI paths. Leave blank for no additions.">
      <textarea aria-label="Additional protected paths, one per line" value={paths} onChange={event => setPaths(event.target.value)} /></SettingField>
    {error && <p className="prov-error" role="alert">{error}</p>}
  </SidePanel>;
}
