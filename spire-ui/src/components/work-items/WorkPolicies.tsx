import { useEffect, useState } from 'react';
import { fetchRepositories, type Repository } from '../repositories/repositoriesApi';
import * as api from './workPolicyApi';
import { SlidersHorizontal } from 'lucide-react';

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

export default function WorkPolicies() {
  const [data, setData] = useState<{ profiles: api.Profile[]; repositories: Repository[] } | null>(null);
  const [error, setError] = useState(''), [refresh, setRefresh] = useState(0), [repository, setRepository] = useState('');
  const [editing, setEditing] = useState<string | null>(null), [notice, setNotice] = useState('');
  useEffect(() => {
    let active = true;
    Promise.all([api.profiles(), fetchRepositories()]).then(([profiles, repositories]) => {
      if (active) { setData({ profiles, repositories }); setError(''); }
    }).catch(failure => { if (active) setError(String(failure)); });
    return () => { active = false; };
  }, [refresh]);
  return <section className="content"><div className="card">
    <div className="prov-head"><h2 className="prov-title">Work policy</h2><div className="prov-actions">
      <button className="btn-ghost" type="button" onClick={() => setRefresh(value => value + 1)}>Refresh work policy</button>
      <button className="btn" type="button" disabled={!data || editing !== null} onClick={() => { setEditing(''); setNotice(''); }}>Add profile</button></div></div>
    <p className="prov-note">Profiles set what work may run automatically and what needs approval. Assign a version as a repository ceiling, then map ticket labels to profile versions.</p>
    <p className="prov-note">Lower precedence selects the displayed profile. Every eligible label, the admission version and the repository ceiling restrict the effective policy.</p>
    {error && <p className="prov-note prov-error" role="alert">{error}</p>}
    {notice && <p className="prov-note" role="status">{notice}</p>}
    {!data ? <p className="prov-note">Loading work policy…</p> : <>
      {data.profiles.length === 0 ? <div className="wh-empty"><div className="wh-empty-icon"><SlidersHorizontal size={22} aria-hidden="true" /></div>
        <div className="wh-empty-title">No profiles yet</div><p className="wh-empty-text">Choose Add profile to define phase approvals and limits, then assign a repository ceiling.</p></div>
        : <div className="prov-scroll"><table className="prov-table" aria-label="Profile versions"><thead><tr><th>Profile</th><th>Version</th><th>Precedence</th><th>Phase modes</th><th>Actions</th></tr></thead>
          <tbody>{data.profiles.map(profile => <tr key={key(profile)}>
            <td className="prov-name mono nowrap">{profile.name}</td><td className="mono nowrap">v{profile.version}</td><td>{profile.precedence}</td>
            <td><div className="chips">{api.phases.map(phase => <span className="chip" key={phase}>{phase.toLowerCase()}: {profile.modes[phase]}</span>)}</div></td>
            <td><button className="btn-ghost" type="button" disabled={editing !== null} onClick={() => { setEditing(key(profile)); setNotice(''); }}>New version of {profile.name} v{profile.version}</button></td>
          </tr>)}</tbody></table></div>}
      {editing !== null && <ProfileEditor key={editing} initial={editing} profiles={data.profiles} cancelled={() => setEditing(null)} saved={created => {
        setData(current => current && ({ ...current, profiles: [...current.profiles.filter(value => key(value) !== key(created)), created] }));
        setNotice(`Profile ${created.name} v${created.version} created.`); setEditing(null);
      }} />}
      <div className="modal-body"><h3 className="field-sep">Repository ceiling and labels</h3>
      <label className="field">Repository policy <span className="field-optional">required to configure a repository</span><select aria-label="Repository policy" value={repository} onChange={event => setRepository(event.target.value)}>
        <option value="">Select a repository</option>{data.repositories.map(repo => <option key={repo.id} value={repo.id}>{repo.workspace}/{repo.slug}</option>)}
      </select><small className="field-hint">Choose where these limits and ticket labels will apply.</small></label>
      {data.repositories.length === 0 && <p className="prov-note">No repositories registered. <a href="#/settings/repositories">Register a repository</a> first.</p>}
      {repository && <RepositoryPolicy key={`${repository}:${refresh}`} id={repository} profiles={data.profiles} />}</div>
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
  async function submit(event: React.FormEvent) {
    event.preventDefault(); setBusy(true); setError('');
    try {
      const created = await api.saveProfile({ id: existing?.id ?? crypto.randomUUID(), name, precedence,
        version: existing ? Math.max(...profiles.filter(profile => profile.id === existing.id).map(profile => profile.version)) + 1 : 1,
        modes, limits: { ...limits, protectedPaths: paths.split('\n').map(path => path.trim()).filter(Boolean) } }); saved(created);
    } catch (failure) { setError(String(failure)); } finally { setBusy(false); }
  }
  return <form className="work-policy-form" onSubmit={event => void submit(event)}><fieldset className="modal-body" style={{ border: 0, margin: 0, minWidth: 0 }} disabled={busy}><legend className="field-sep">{base ? 'New profile version' : 'Add profile'}</legend>
    <p className="prov-note">Fields marked required need a value. Off disables a phase, approve waits for an answer, and auto permits it within the limits below.</p>
    <label className="field">Base profile <span className="field-optional">optional</span><select aria-label="Base profile" value={selected} onChange={event => {
      const value = profiles.find(profile => key(profile) === event.target.value); setSelected(event.target.value);
      setName(value?.name ?? ''); setPrecedence(value?.precedence ?? 0);
      setModes(value?.modes ?? emptyModes); setLimits(value?.limits ?? emptyLimits); setPaths(value?.limits.protectedPaths.join('\n') ?? '');
    }}><option value="">New profile</option>{profiles.map(profile => <option key={key(profile)} value={key(profile)}>{profile.name} v{profile.version}</option>)}</select><small className="field-hint">Start fresh or create a new version of an existing profile; saved versions never change.</small></label>
    <label className="field">Profile name <span className="field-optional">required</span><input aria-label="Profile name" required value={name} readOnly={!!existing} onChange={event => setName(event.target.value)} /><small className="field-hint">A recognisable name. Existing profiles keep their name across versions.</small></label>
    <label className="field">Precedence <span className="field-optional">required</span><input aria-label="Precedence" required type="number" min={0} step={1} readOnly={!!existing} value={precedence} onChange={event => setPrecedence(Number(event.target.value))} /><small className="field-hint">A unique nonnegative number; lower values win when several eligible labels apply.</small></label>
    {api.phases.map(phase => <label className="field" key={phase}>{phase.toLowerCase()} mode <span className="field-optional">required</span><select aria-label={`${phase.toLowerCase()} mode`} required value={modes[phase]} onChange={event => setModes(value => ({ ...value, [phase]: event.target.value }))}>
      {(phase === 'DELIVER' ? ['off', 'draft_pr', 'pr'] : phase === 'LAND' ? ['off', 'approve', 'auto_if_green'] : ['off', 'approve', 'auto']).map(mode => <option key={mode}>{mode}</option>)}
    </select><small className="field-hint">{phaseHelp[phase]}</small></label>)}
    <p className="prov-note">New versions keep the same name and precedence. Create a new profile to change either.</p>
    <p className="prov-note">A zero maximum stops execution. The built-in CI protected paths always apply.</p>
    {Object.entries(bounds).map(([field, label]) => <label className="field" key={field}>{label} <span className="field-optional">required</span><input aria-label={label} required type="number" step={1} min={field === 'gateTtlSeconds' ? 1 : 0}
      value={limits[field as keyof typeof bounds]} onChange={event => setLimits(value => ({ ...value, [field]: Number(event.target.value) }))} /><small className="field-hint">{limitHelp[field as keyof typeof bounds]}</small></label>)}
    <label className="field">Additional protected paths, one per line <span className="field-optional">optional</span><textarea aria-label="Additional protected paths, one per line" value={paths} onChange={event => setPaths(event.target.value)} /><small className="field-hint">Repository-relative path patterns to protect in addition to the built-in CI paths. Leave blank for no additions.</small></label>
    {error && <p className="prov-error" role="alert">{error}</p>}<div className="prov-actions"><button className="btn" type="submit">Create profile version</button>
      <button className="btn-ghost" type="button" onClick={cancelled}>Cancel</button></div>
  </fieldset></form>;
}

function RepositoryPolicy({ id, profiles }: { id: string; profiles: api.Profile[] }) {
  const [policy, setPolicy] = useState<api.Policy | null>(null), [ceiling, setCeiling] = useState('');
  const [labels, setLabels] = useState<{ label: string; profile: string }[]>([]), [error, setError] = useState(''), [busy, setBusy] = useState(false);
  function loaded(value: api.Policy) { setPolicy(value); setCeiling(value.ceiling ? key(value.ceiling) : ''); setLabels(Object.entries(value.mappings).map(([label, profile]) => ({ label, profile: key(profile) }))); }
  useEffect(() => { let active = true; api.policy(id).then(value => { if (active) loaded(value); }).catch(failure => { if (active) setError(String(failure)); }); return () => { active = false; }; }, [id]);
  const options = <><option value="">Select a version</option>{profiles.map(profile => <option key={key(profile)} value={key(profile)}>{profile.name} v{profile.version}</option>)}</>;
  async function submit(event: React.FormEvent) {
    event.preventDefault(); if (!policy) return; setBusy(true); setError('');
    try {
      if (new Set(labels.map(value => value.label.trim())).size !== labels.length) throw new Error('Each label needs one mapping.');
      const selected = profiles.find(value => key(value) === ceiling)!;
      loaded(await api.savePolicy(id, { revision: policy.revision, ceiling: pin(selected),
        mappings: Object.fromEntries(labels.map(value => [value.label.trim(), pin(profiles.find(profile => key(profile) === value.profile)!)])) }));
    } catch (failure) { setError(String(failure)); } finally { setBusy(false); }
  }
  return <form className="work-policy-form" onSubmit={event => void submit(event)}><fieldset className="modal-body" style={{ border: 0, margin: 0, minWidth: 0 }} disabled={busy || !policy}><legend className="field-sep">Ceiling and labels</legend>
    <label className="field">Repository ceiling <span className="field-optional">required</span><select aria-label="Repository ceiling" required value={ceiling} onChange={event => setCeiling(event.target.value)}>{options}</select><small className="field-hint">The maximum authority for this repository. Ticket labels can restrict it, never exceed it.</small></label>
    <p className="prov-note">Label mappings are optional. Each added mapping requires a label and a profile version.</p>
    {labels.map((value, index) => <div className="op-form" key={index}>
      <label className="field">Label {index + 1} <span className="field-optional">required</span><input aria-label={`Label ${index + 1}`} required value={value.label} onChange={event => setLabels(rows => rows.map((row, i) => i === index ? { ...row, label: event.target.value } : row))} /><small className="field-hint">The exact ticket label, for example work:assisted.</small></label>
      <label className="field">Label profile {index + 1} <span className="field-optional">required</span><select aria-label={`Label profile ${index + 1}`} required value={value.profile} onChange={event => setLabels(rows => rows.map((row, i) => i === index ? { ...row, profile: event.target.value } : row))}>{options}</select><small className="field-hint">The saved profile version this label requests.</small></label>
      <button className="btn" type="button" onClick={() => setLabels(rows => rows.filter((_, i) => i !== index))}>Remove label {index + 1}</button>
    </div>)}
    <button className="btn" type="button" onClick={() => setLabels(rows => [...rows, { label: '', profile: '' }])}>Add label mapping</button>
    <button className="btn" type="submit">Save repository policy</button>
  </fieldset>{error && <p role="alert">{error}</p>}</form>;
}
