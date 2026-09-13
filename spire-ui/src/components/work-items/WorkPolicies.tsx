import { useEffect, useState } from 'react';
import { fetchRepositories, type Repository } from '../repositories/repositoriesApi';
import * as api from './workPolicyApi';

const bounds = { gateTtlSeconds: 'Approval lifetime (seconds)', maxRunsPerItem: 'Maximum runs per item', maxStepsPerPlan: 'Maximum steps per plan',
  maxWallClockSeconds: 'Maximum wall time (seconds)', maxCostMillicents: 'Maximum cost (millicents)', maxCallsPerItem: 'Maximum calls per item' };
const pin = (profile: api.Profile) => ({ id: profile.id, version: profile.version });
const key = (profile: api.Pin) => `${profile.id}:${profile.version}`;

export default function WorkPolicies() {
  const [data, setData] = useState<{ profiles: api.Profile[]; repositories: Repository[] } | null>(null);
  const [error, setError] = useState(''), [refresh, setRefresh] = useState(0), [repository, setRepository] = useState('');
  useEffect(() => {
    let active = true;
    Promise.all([api.profiles(), fetchRepositories()]).then(([profiles, repositories]) => {
      if (active) { setData({ profiles, repositories }); setError(''); }
    }).catch(failure => { if (active) setError(String(failure)); });
    return () => { active = false; };
  }, [refresh]);
  return <section className="content"><div className="card" style={{ padding: 18 }}>
    <h2>Work policy</h2>
    <p>Lower precedence selects the displayed profile. Every eligible label, the admission version and the repository ceiling restrict the effective policy.</p>
    {error && <p role="alert">{error}</p>}
    {!data ? <p>Loading work policy…</p> : <>
      <ProfileEditor key={refresh} profiles={data.profiles} saved={() => setRefresh(value => value + 1)} />
      <label className="field">Repository policy<select value={repository} onChange={event => setRepository(event.target.value)}>
        <option value="">Select a repository</option>{data.repositories.map(repo => <option key={repo.id} value={repo.id}>{repo.workspace}/{repo.slug}</option>)}
      </select></label>
      {repository && <RepositoryPolicy key={`${repository}:${refresh}`} id={repository} profiles={data.profiles} />}
    </>}
  </div></section>;
}

function ProfileEditor({ profiles, saved }: { profiles: api.Profile[]; saved: () => void }) {
  const [selected, setSelected] = useState('');
  const [name, setName] = useState(''), [precedence, setPrecedence] = useState(0);
  const [modes, setModes] = useState(Object.fromEntries(api.phases.map(phase => [phase, 'off'])) as Record<api.Phase, string>);
  const [limits, setLimits] = useState<api.Limits>({ gateTtlSeconds: 86400, maxRunsPerItem: 0, maxStepsPerPlan: 0, maxWallClockSeconds: 0, maxCostMillicents: 0, maxCallsPerItem: 0, protectedPaths: [] });
  const [paths, setPaths] = useState(''), [busy, setBusy] = useState(false), [error, setError] = useState('');
  const existing = profiles.find(profile => key(profile) === selected);
  async function submit(event: React.FormEvent) {
    event.preventDefault(); setBusy(true); setError('');
    try {
      await api.saveProfile({ id: existing?.id ?? crypto.randomUUID(), name, precedence,
        version: existing ? Math.max(...profiles.filter(profile => profile.id === existing.id).map(profile => profile.version)) + 1 : 1,
        modes, limits: { ...limits, protectedPaths: paths.split('\n').map(path => path.trim()).filter(Boolean) } }); saved();
    } catch (failure) { setError(String(failure)); } finally { setBusy(false); }
  }
  return <form className="work-policy-form" onSubmit={event => void submit(event)}><fieldset disabled={busy}><legend>Immutable profile versions</legend>
    <label className="field">Base profile<select value={selected} onChange={event => {
      const value = profiles.find(profile => key(profile) === event.target.value); setSelected(event.target.value);
      setName(value?.name ?? ''); setPrecedence(value?.precedence ?? 0);
      if (value) { setModes(value.modes); setLimits(value.limits); setPaths(value.limits.protectedPaths.join('\n')); }
    }}><option value="">New profile</option>{profiles.map(profile => <option key={key(profile)} value={key(profile)}>{profile.name} v{profile.version}</option>)}</select></label>
    <label className="field">Profile name<input required value={name} readOnly={!!existing} onChange={event => setName(event.target.value)} /></label>
    <label className="field">Precedence<input required type="number" min={0} step={1} readOnly={!!existing} value={precedence} onChange={event => setPrecedence(Number(event.target.value))} /></label>
    {api.phases.map(phase => <label className="field" key={phase}>{phase.toLowerCase()} mode<select value={modes[phase]} onChange={event => setModes(value => ({ ...value, [phase]: event.target.value }))}>
      {(phase === 'DELIVER' ? ['off', 'draft_pr', 'pr'] : phase === 'LAND' ? ['off', 'approve', 'auto_if_green'] : ['off', 'approve', 'auto']).map(mode => <option key={mode}>{mode}</option>)}
    </select></label>)}
    <p>New versions keep the same name and precedence. Create a new profile to change either.</p>
    <p>A zero maximum stops execution. The built-in CI protected paths always apply.</p>
    {Object.entries(bounds).map(([field, label]) => <label className="field" key={field}>{label}<input required type="number" step={1} min={field === 'gateTtlSeconds' ? 1 : 0}
      value={limits[field as keyof typeof bounds]} onChange={event => setLimits(value => ({ ...value, [field]: Number(event.target.value) }))} /></label>)}
    <label className="field">Additional protected paths, one per line<textarea value={paths} onChange={event => setPaths(event.target.value)} /></label>
    {error && <p role="alert">{error}</p>}<button className="btn" type="submit">Create profile version</button>
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
  return <form className="work-policy-form" onSubmit={event => void submit(event)}><fieldset disabled={busy || !policy}><legend>Ceiling and labels</legend>
    <label className="field">Repository ceiling<select required value={ceiling} onChange={event => setCeiling(event.target.value)}>{options}</select></label>
    {labels.map((value, index) => <div className="op-form" key={index}>
      <label className="field">Label {index + 1}<input required value={value.label} onChange={event => setLabels(rows => rows.map((row, i) => i === index ? { ...row, label: event.target.value } : row))} /></label>
      <label className="field">Label profile {index + 1}<select required value={value.profile} onChange={event => setLabels(rows => rows.map((row, i) => i === index ? { ...row, profile: event.target.value } : row))}>{options}</select></label>
      <button className="btn" type="button" onClick={() => setLabels(rows => rows.filter((_, i) => i !== index))}>Remove label {index + 1}</button>
    </div>)}
    <button className="btn" type="button" onClick={() => setLabels(rows => [...rows, { label: '', profile: '' }])}>Add label mapping</button>
    <button className="btn" type="submit">Save repository policy</button>
  </fieldset>{error && <p role="alert">{error}</p>}</form>;
}
