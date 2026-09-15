import { useEffect, useState } from 'react';
import { fetchRepositories, type Repository } from '../repositories/repositoriesApi';
import * as api from './workPolicyApi';
import { SlidersHorizontal } from 'lucide-react';
import PhaseStrip, { PhaseLegend } from './PhaseStrip';
import { ProfileEditor } from './WorkPolicyEditors';
import { key } from './policyInput';

/** A saved version never changes, so the newest version of a profile is the one new work selects. */
function isCurrent(profile: api.Profile, all: api.Profile[]) {
  return !all.some(other => other.id === profile.id && other.version > profile.version);
}

/** One repository's use of one profile version: as its ceiling, and under which labels. */
interface Use { repository: string; ceiling: boolean; labels: string[] }

/**
 * Which repositories a profile version is pinned in. Answers filed per repository, so a slow one can
 * never land on another; a repository whose policy did not load is simply not counted.
 */
function usesOf(profile: api.Profile, repositories: Repository[], policies: Record<string, api.Policy>): Use[] {
  return repositories.flatMap(repository => {
    const policy = policies[repository.id];
    if (!policy) return [];
    const ceiling = !!policy.ceiling && key(policy.ceiling) === key(profile);
    const labels = Object.entries(policy.mappings).filter(([, mapped]) => key(mapped) === key(profile)).map(([label]) => label);
    return ceiling || labels.length ? [{ repository: `${repository.workspace}/${repository.slug}`, ceiling, labels }] : [];
  });
}

export default function WorkPolicies() {
  const [data, setData] = useState<{ profiles: api.Profile[]; repositories: Repository[] } | null>(null);
  const [policies, setPolicies] = useState<Record<string, api.Policy>>({});
  const [error, setError] = useState(''), [refresh, setRefresh] = useState(0);
  const [editing, setEditing] = useState<string | null>(null), [notice, setNotice] = useState('');
  const [history, setHistory] = useState(false);

  useEffect(() => {
    let active = true;
    setPolicies({});
    Promise.all([api.profiles(), fetchRepositories()]).then(([profiles, repositories]) => {
      if (!active) return;
      setData({ profiles, repositories }); setError('');
      for (const repository of repositories) {
        api.policy(repository.id).then(policy => { if (active) setPolicies(current => ({ ...current, [repository.id]: policy })); }).catch(() => undefined);
      }
    }).catch(failure => { if (active) setError(String(failure)); });
    return () => { active = false; };
  }, [refresh]);

  const shown = data ? data.profiles.filter(profile => history || isCurrent(profile, data.profiles)) : [];
  const superseded = data ? data.profiles.length - data.profiles.filter(profile => isCurrent(profile, data.profiles)).length : 0;
  return <section className="content">
    <div className="card">
      <div className="prov-head"><h2 className="prov-title">Profiles</h2><div className="prov-actions">
        <button className="btn-ghost" type="button" onClick={() => setRefresh(value => value + 1)}>Refresh profiles</button>
        <button className="btn" type="button" disabled={!data || editing !== null} onClick={() => { setEditing(''); setNotice(''); }}>Add profile</button></div></div>
      <p className="prov-note">A profile says which phases run on their own and which wait for you. Repositories pick profiles as their ceiling
        and for their labels in the Factory tab on the Repositories screen. A saved version never changes: saving changes creates the next version.</p>
      {error && <p className="prov-note prov-error" role="alert">{error}</p>}
      {notice && <p className="prov-note" role="status">{notice}</p>}
      {!data ? <p className="prov-note">Loading profiles…</p>
        : data.profiles.length === 0 ? <div className="wh-empty"><div className="wh-empty-icon"><SlidersHorizontal size={22} aria-hidden="true" /></div>
          <div className="wh-empty-title">No profiles yet</div><p className="wh-empty-text">Choose Add profile, or open a repository's Factory tab and use the presets.</p></div>
        : <><div className="prov-scroll"><table className="prov-table" aria-label="Profile versions">
          <thead><tr><th>Profile</th><th>Version</th><th>State</th><th>Pipeline</th><th className="cell-r">Precedence</th><th>Used by</th><th></th></tr></thead>
          <tbody>{shown.map(profile => {
            const uses = usesOf(profile, data.repositories, policies);
            return <tr key={key(profile)}>
              <td className="prov-name mono nowrap">{profile.name}</td>
              <td className="mono nowrap">v{profile.version}</td>
              <td><div className="chips"><span className={`chip ${isCurrent(profile, data.profiles) ? 'on' : ''}`}>
                {isCurrent(profile, data.profiles) ? 'current' : 'superseded'}</span></div></td>
              <td><PhaseStrip modes={profile.modes} /></td>
              <td className="cell-r tnum">{profile.precedence}</td>
              <td>{uses.length === 0 ? <span className="prov-none">—</span> : uses.map(use => <div key={use.repository}>
                <span className="mono nowrap">{use.repository}</span>
                <div className="prov-sub">{[use.ceiling && 'ceiling', ...use.labels.map(label => `label ${label}`)].filter(Boolean).join(' · ')}</div></div>)}</td>
              <td className="cell-r"><button className="btn-ghost" type="button" disabled={editing !== null}
                aria-label={`New version of ${profile.name} v${profile.version}`}
                onClick={() => { setEditing(key(profile)); setNotice(''); }}>New version</button></td>
            </tr>;
          })}</tbody></table></div>
          <PhaseLegend />
          {superseded > 0 && <p className="prov-note"><button className="btn-ghost" type="button" onClick={() => setHistory(value => !value)}>
            {history ? 'Hide superseded versions' : `Show ${superseded} superseded version${superseded === 1 ? '' : 's'}`}</button></p>}</>}
    </div>

    {data && editing !== null && <ProfileEditor key={editing} initial={editing} profiles={data.profiles} cancelled={() => setEditing(null)} saved={created => {
      setData(current => current && ({ ...current, profiles: [...current.profiles.filter(value => key(value) !== key(created)), created] }));
      setNotice(`Profile ${created.name} v${created.version} created.`); setEditing(null);
    }} />}
  </section>;
}
