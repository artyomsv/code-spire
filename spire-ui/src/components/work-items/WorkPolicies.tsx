import { useEffect, useState } from 'react';
import { fetchRepositories, type Repository } from '../repositories/repositoriesApi';
import * as api from './workPolicyApi';
import { SlidersHorizontal } from 'lucide-react';
import SettingField from '../SettingField';
import FormDialog from '../FormDialog';

const bounds = { gateTtlSeconds: 'Approval lifetime (seconds)', maxRunsPerItem: 'Maximum runs per item', maxStepsPerPlan: 'Maximum steps per plan',
  maxWallClockSeconds: 'Maximum wall time (seconds)', maxCostMillicents: 'Maximum cost (millicents)', maxCallsPerItem: 'Maximum calls per item' };
const pin = (profile: api.Profile) => ({ id: profile.id, version: profile.version });
const key = (profile: api.Pin) => `${profile.id}:${profile.version}`;
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
/** A saved version never changes, so the newest version of a profile is the one new work selects. */
function isCurrent(profile: api.Profile, all: api.Profile[]) {
  return !all.some(other => other.id === profile.id && other.version > profile.version);
}

export default function WorkPolicies() {
  const [data, setData] = useState<{ profiles: api.Profile[]; repositories: Repository[] } | null>(null);
  const [error, setError] = useState(''), [refresh, setRefresh] = useState(0), [repository, setRepository] = useState('');
  const [editing, setEditing] = useState<string | null>(null), [notice, setNotice] = useState('');
  const [history, setHistory] = useState(false);
  useEffect(() => {
    let active = true;
    Promise.all([api.profiles(), fetchRepositories()]).then(([profiles, repositories]) => {
      if (active) { setData({ profiles, repositories }); setError(''); }
    }).catch(failure => { if (active) setError(String(failure)); });
    return () => { active = false; };
  }, [refresh]);
  const shown = data ? data.profiles.filter(profile => history || isCurrent(profile, data.profiles)) : [];
  const superseded = data ? data.profiles.length - data.profiles.filter(profile => isCurrent(profile, data.profiles)).length : 0;
  return <section className="content"><div className="card">
    <div className="prov-head"><h2 className="prov-title">Work policy</h2><div className="prov-actions">
      <button className="btn-ghost" type="button" onClick={() => setRefresh(value => value + 1)}>Refresh work policy</button>
      <button className="btn" type="button" disabled={!data || editing !== null} onClick={() => { setEditing(''); setNotice(''); }}>Add profile</button></div></div>
    <p className="prov-note">Profiles set what work may run automatically and what needs approval. Assign a version as a repository ceiling, then map ticket labels to profile versions.</p>
    <p className="prov-note">A saved version never changes: a work item keeps the version it was admitted under, so editing one would alter authority already granted. Saving changes creates the next version, and new work selects the current one.</p>
    {error && <p className="prov-note prov-error" role="alert">{error}</p>}
    {notice && <p className="prov-note" role="status">{notice}</p>}
    {!data ? <p className="prov-note">Loading work policy…</p> : <>
      {data.profiles.length === 0 ? <div className="wh-empty"><div className="wh-empty-icon"><SlidersHorizontal size={22} aria-hidden="true" /></div>
        <div className="wh-empty-title">No profiles yet</div><p className="wh-empty-text">Choose Add profile to define phase approvals and limits, then assign a repository ceiling.</p></div>
        : <><div className="prov-scroll"><table className="prov-table" aria-label="Profile versions"><thead><tr><th>Profile</th><th>Version</th><th>State</th><th>Precedence</th><th>Phase modes</th><th>Actions</th></tr></thead>
          <tbody>{shown.map(profile => <tr key={key(profile)}>
            <td className="prov-name mono nowrap">{profile.name}</td>
            <td className="mono nowrap">v{profile.version}</td>
            <td><div className="chips"><span className={`chip ${isCurrent(profile, data.profiles) ? 'on' : ''}`}>
              {isCurrent(profile, data.profiles) ? 'current' : 'superseded'}</span></div></td>
            <td>{profile.precedence}</td>
            <td><div className="chips">{api.phases.map(phase => <span className="chip" key={phase}>{phase.toLowerCase()}: {profile.modes[phase]}</span>)}</div></td>
            <td><button className="btn-ghost" type="button" disabled={editing !== null} onClick={() => { setEditing(key(profile)); setNotice(''); }}>New version of {profile.name} v{profile.version}</button></td>
          </tr>)}</tbody></table></div>
          {superseded > 0 && <button className="btn-ghost" type="button" onClick={() => setHistory(value => !value)}>
            {history ? 'Hide superseded versions' : `Show ${superseded} superseded version${superseded === 1 ? '' : 's'}`}</button>}</>}

      <h3 className="field-sep">Repository ceiling and labels</h3>
      <SettingField label="Repository policy" scope="work policy" hint="Required to configure a repository. Choose where these limits and ticket labels will apply.">
        <select aria-label="Repository policy" value={repository} onChange={event => setRepository(event.target.value)}>
          <option value="">Select a repository</option>{data.repositories.map(repo => <option key={repo.id} value={repo.id}>{repo.workspace}/{repo.slug}</option>)}
        </select></SettingField>
      {data.repositories.length === 0 && <p className="prov-note">No repositories registered. <a href="#/settings/repositories">Register a repository</a> first.</p>}
      {repository && <RepositoryPolicy key={`${repository}:${refresh}`} id={repository} profiles={data.profiles} />}

      {editing !== null && <ProfileEditor key={editing} initial={editing} profiles={data.profiles} cancelled={() => setEditing(null)} saved={created => {
        setData(current => current && ({ ...current, profiles: [...current.profiles.filter(value => key(value) !== key(created)), created] }));
        setNotice(`Profile ${created.name} v${created.version} created.`); setEditing(null);
      }} />}
    </>}
  </div></section>;
}

function ProfileEditor({ profiles, initial, saved, cancelled }: { profiles: api.Profile[]; initial: string; saved: (profile: api.Profile) => void; cancelled: () => void }) {
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
  return <FormDialog title={base ? `New version of ${base.name} v${base.version}` : 'Add profile'} busy={busy} onClose={cancelled} actions={<>
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
  </FormDialog>;
}

function RepositoryPolicy({ id, profiles }: { id: string; profiles: api.Profile[] }) {
  const [policy, setPolicy] = useState<api.Policy | null>(null), [error, setError] = useState(''), [open, setOpen] = useState(false);
  useEffect(() => { let active = true; api.policy(id).then(value => { if (active) setPolicy(value); }).catch(failure => { if (active) setError(String(failure)); }); return () => { active = false; }; }, [id]);
  if (error) return <p className="prov-note prov-error" role="alert">{error}</p>;
  if (!policy) return <p className="prov-note">Loading repository policy…</p>;
  const mappings = Object.entries(policy.mappings);
  return <>
    <div className="prov-head"><h4 className="prov-title">Ceiling and labels</h4>
      <div className="prov-actions"><button className="btn" type="button" onClick={() => setOpen(true)}>Edit repository policy</button></div></div>
    <p className="prov-note">Ceiling: {policy.ceiling ? <span className="mono">{policy.ceiling.name} v{policy.ceiling.version}</span> : 'none selected'}.
      {' '}The maximum authority for this repository; ticket labels can restrict it, never exceed it.</p>
    {mappings.length === 0 ? <p className="prov-note">No label mappings. A ticket label selects a profile only when it is mapped here.</p>
      : <div className="prov-scroll"><table className="prov-table" aria-label="Label mappings">
        <thead><tr><th>Ticket label</th><th>Profile version</th></tr></thead>
        <tbody>{mappings.map(([label, profile]) => <tr key={label}>
          <td className="mono nowrap">{label}</td><td className="mono nowrap">{profile.name} v{profile.version}</td></tr>)}</tbody></table></div>}
    {open && <PolicyEditor id={id} policy={policy} profiles={profiles} cancelled={() => setOpen(false)}
      saved={value => { setPolicy(value); setOpen(false); }} />}
  </>;
}

function PolicyEditor({ id, policy, profiles, saved, cancelled }:
  { id: string; policy: api.Policy; profiles: api.Profile[]; saved: (policy: api.Policy) => void; cancelled: () => void }) {
  const [ceiling, setCeiling] = useState(policy.ceiling ? key(policy.ceiling) : '');
  const [labels, setLabels] = useState(Object.entries(policy.mappings).map(([label, profile]) => ({ label, profile: key(profile) })));
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  const options = <><option value="">Select a version</option>{profiles.map(profile => <option key={key(profile)} value={key(profile)}>{profile.name} v{profile.version}</option>)}</>;
  async function submit() {
    setBusy(true); setError('');
    try {
      if (new Set(labels.map(value => value.label.trim())).size !== labels.length) throw new Error('Each label needs one mapping.');
      const selected = profiles.find(value => key(value) === ceiling)!;
      saved(await api.savePolicy(id, { revision: policy.revision, ceiling: pin(selected),
        mappings: Object.fromEntries(labels.map(value => [value.label.trim(), pin(profiles.find(profile => key(profile) === value.profile)!)])) }));
    } catch (failure) { setError(String(failure)); } finally { setBusy(false); }
  }
  return <FormDialog title="Edit repository policy" busy={busy} onClose={cancelled} actions={<>
    <button className="btn" type="button" disabled={busy || !ceiling} onClick={() => void submit()}>Save repository policy</button>
    <button className="btn-ghost" type="button" onClick={cancelled}>Cancel</button></>}>
    <SettingField label="Repository ceiling" scope="repository policy"
      hint="Required. The maximum authority for this repository. Ticket labels can restrict it, never exceed it.">
      <select aria-label="Repository ceiling" required value={ceiling} onChange={event => setCeiling(event.target.value)}>{options}</select></SettingField>
    <h4 className="field-sep">Label mappings</h4>
    <p className="prov-note">Optional. Each mapping needs a ticket label and the profile version that label requests.</p>
    {labels.map((value, index) => <div className="op-form" key={index}>
      <SettingField label={`Label ${index + 1}`} scope="label mappings" hint="Required. The exact ticket label, for example work:assisted.">
        <input aria-label={`Label ${index + 1}`} required value={value.label} onChange={event => setLabels(rows => rows.map((row, i) => i === index ? { ...row, label: event.target.value } : row))} /></SettingField>
      <SettingField label={`Label profile ${index + 1}`} scope="label mappings" hint="Required. The saved profile version this label requests.">
        <select aria-label={`Label profile ${index + 1}`} required value={value.profile} onChange={event => setLabels(rows => rows.map((row, i) => i === index ? { ...row, profile: event.target.value } : row))}>{options}</select></SettingField>
      <button className="btn-ghost" type="button" onClick={() => setLabels(rows => rows.filter((_, i) => i !== index))}>Remove label {index + 1}</button>
    </div>)}
    <div className="prov-actions"><button className="btn-ghost" type="button" onClick={() => setLabels(rows => [...rows, { label: '', profile: '' }])}>Add label mapping</button></div>
    {error && <p className="prov-error" role="alert">{error}</p>}
  </FormDialog>;
}
