import { useEffect, useState } from 'react';
import { fetchRepositories, type Repository } from '../repositories/repositoriesApi';
import * as api from './workPolicyApi';
import { SlidersHorizontal } from 'lucide-react';
import PhaseStrip, { PhaseLegend } from './PhaseStrip';
import { ProfileEditor, PolicyEditor, key } from './WorkPolicyEditors';

/** What one repository's policy request answered with. Absent means the answer has not arrived. */
interface Assignment { policy?: api.Policy; error?: string }

/** A saved version never changes, so the newest version of a profile is the one new work selects. */
function isCurrent(profile: api.Profile, all: api.Profile[]) {
  return !all.some(other => other.id === profile.id && other.version > profile.version);
}
const coordinates = (repository: Repository) => `${repository.workspace}/${repository.slug}`;

export default function WorkPolicies() {
  const [data, setData] = useState<{ profiles: api.Profile[]; repositories: Repository[] } | null>(null);
  const [policies, setPolicies] = useState<Record<string, Assignment>>({});
  const [error, setError] = useState(''), [refresh, setRefresh] = useState(0);
  const [editing, setEditing] = useState<string | null>(null), [assigning, setAssigning] = useState<string | null>(null);
  const [notice, setNotice] = useState(''), [history, setHistory] = useState(false);

  useEffect(() => {
    let active = true;
    setPolicies({});
    Promise.all([api.profiles(), fetchRepositories()]).then(([profiles, repositories]) => {
      if (!active) return;
      setData({ profiles, repositories }); setError('');
      // Every answer is filed under the repository it was asked about. A slow one therefore cannot
      // land on another row: there is no single "selected repository" for it to overwrite.
      for (const repository of repositories) {
        api.policy(repository.id)
          .then(policy => { if (active) setPolicies(current => ({ ...current, [repository.id]: { policy } })); })
          .catch(failure => { if (active) setPolicies(current => ({ ...current, [repository.id]: { error: String(failure) } })); });
      }
    }).catch(failure => { if (active) setError(String(failure)); });
    return () => { active = false; };
  }, [refresh]);

  const shown = data ? data.profiles.filter(profile => history || isCurrent(profile, data.profiles)) : [];
  const superseded = data ? data.profiles.length - data.profiles.filter(profile => isCurrent(profile, data.profiles)).length : 0;
  const target = data?.repositories.find(repository => repository.id === assigning);
  return <section className="content">
    <div className="card">
      <div className="prov-head"><h2 className="prov-title">Profiles</h2><div className="prov-actions">
        <button className="btn-ghost" type="button" onClick={() => setRefresh(value => value + 1)}>Refresh work policy</button>
        <button className="btn" type="button" disabled={!data || editing !== null} onClick={() => { setEditing(''); setNotice(''); }}>Add profile</button></div></div>
      <p className="prov-note">A profile says which phases run on their own and which wait for you. A saved version never changes:
        a work item keeps the version it was admitted under, so saving changes creates the next version.</p>
      {error && <p className="prov-note prov-error" role="alert">{error}</p>}
      {notice && <p className="prov-note" role="status">{notice}</p>}
      {!data ? <p className="prov-note">Loading work policy…</p>
        : data.profiles.length === 0 ? <div className="wh-empty"><div className="wh-empty-icon"><SlidersHorizontal size={22} aria-hidden="true" /></div>
          <div className="wh-empty-title">No profiles yet</div><p className="wh-empty-text">Choose Add profile to define phase approvals and limits, then assign a repository ceiling.</p></div>
        : <><div className="prov-scroll"><table className="prov-table" aria-label="Profile versions">
          <thead><tr><th>Profile</th><th>Version</th><th>State</th><th>Pipeline</th><th className="cell-r">Precedence</th><th></th></tr></thead>
          <tbody>{shown.map(profile => <tr key={key(profile)}>
            <td className="prov-name mono nowrap">{profile.name}</td>
            <td className="mono nowrap">v{profile.version}</td>
            <td><div className="chips"><span className={`chip ${isCurrent(profile, data.profiles) ? 'on' : ''}`}>
              {isCurrent(profile, data.profiles) ? 'current' : 'superseded'}</span></div></td>
            <td><PhaseStrip modes={profile.modes} /></td>
            <td className="cell-r tnum">{profile.precedence}</td>
            <td className="cell-r"><button className="btn-ghost" type="button" disabled={editing !== null}
              aria-label={`New version of ${profile.name} v${profile.version}`}
              onClick={() => { setEditing(key(profile)); setNotice(''); }}>New version</button></td>
          </tr>)}</tbody></table></div>
          <PhaseLegend />
          {superseded > 0 && <p className="prov-note"><button className="btn-ghost" type="button" onClick={() => setHistory(value => !value)}>
            {history ? 'Hide superseded versions' : `Show ${superseded} superseded version${superseded === 1 ? '' : 's'}`}</button></p>}</>}
    </div>

    {data && <div className="card">
      <div className="prov-head"><h2 className="prov-title">Repository assignments</h2></div>
      <p className="prov-note">The ceiling is the most a repository may ever do. A ticket label can ask for less, never more.</p>
      {data.repositories.length === 0 ? <p className="prov-note">No repositories registered. <a href="#/settings/repositories">Register a repository</a> first.</p>
        : <div className="prov-scroll"><table className="prov-table" aria-label="Repository assignments">
          <thead><tr><th>Repository</th><th>Ceiling</th><th>Ticket labels</th><th></th></tr></thead>
          <tbody>{data.repositories.map(repository => {
            const entry = policies[repository.id] as Assignment | undefined;
            const mappings = entry?.policy ? Object.entries(entry.policy.mappings) : [];
            return <tr key={repository.id}>
              <td className="prov-name mono nowrap">{coordinates(repository)}</td>
              <td className="mono nowrap">{!entry ? 'Loading…' : entry.error ? <span className="prov-error">{entry.error}</span>
                : entry.policy!.ceiling ? `${entry.policy!.ceiling.name} v${entry.policy!.ceiling.version}` : 'No ceiling — nothing runs'}</td>
              <td>{!entry?.policy ? '' : mappings.length === 0 ? <span className="prov-none">none</span>
                : <div className="chips">{mappings.map(([label, profile]) => <span className="chip mono" key={label}>{label} → {profile.name} v{profile.version}</span>)}</div>}</td>
              <td className="cell-r"><button className="btn-ghost" type="button" disabled={!entry?.policy || assigning !== null}
                aria-label={`Edit policy for ${coordinates(repository)}`} onClick={() => setAssigning(repository.id)}>Edit</button></td>
            </tr>;
          })}</tbody></table></div>}
    </div>}

    {data && editing !== null && <ProfileEditor key={editing} initial={editing} profiles={data.profiles} cancelled={() => setEditing(null)} saved={created => {
      setData(current => current && ({ ...current, profiles: [...current.profiles.filter(value => key(value) !== key(created)), created] }));
      setNotice(`Profile ${created.name} v${created.version} created.`); setEditing(null);
    }} />}
    {data && target && policies[target.id]?.policy && <PolicyEditor id={target.id} label={coordinates(target)}
      policy={policies[target.id].policy!} profiles={data.profiles} cancelled={() => setAssigning(null)}
      saved={value => { setPolicies(current => ({ ...current, [target.id]: { policy: value } })); setAssigning(null); }} />}
  </section>;
}
