import { useEffect, useState } from 'react';
import { fetchProviders, type ProviderView } from '../../api';
import { actorLabel, type ActorResult } from '../actorsApi';
import { fetchRepositories, type Repository } from '../repositories/repositoriesApi';
import * as api from './workSourcesApi';
import { ListTodo } from 'lucide-react';

const kinds: Record<api.WorkSourceType, string> = { GITHUB: 'GitHub', GITLAB: 'GitLab', JIRA: 'Jira' };
function origin(base: string) { try { return new URL(base).origin; } catch { return ''; } }
function compatible(type: api.WorkSourceType, account: ProviderView) {
  return account.enabled && account.type === (type === 'JIRA' ? 'atlassian' : type.toLowerCase())
    && (account.authKind === 'bearer' || type === 'JIRA' && account.authKind === 'basic');
}

export default function WorkSources() {
  const [data, setData] = useState<{ sources: api.WorkSource[]; accounts: ProviderView[]; repositories: Repository[] } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [refresh, setRefresh] = useState(0);
  const [selected, setSelected] = useState('');
  useEffect(() => {
    let active = true;
    Promise.all([api.fetchWorkSources(), fetchProviders(), fetchRepositories()]).then(([sources, accounts, repositories]) => {
      if (active) { setData({ sources, accounts, repositories }); setError(null); }
    }).catch(failure => { if (active) setError(String(failure)); });
    return () => { active = false; };
  }, [refresh]);
  const reload = () => setRefresh(value => value + 1);
  const source = data?.sources.find(value => value.id === selected);
  return <section className="content"><div className="card">
    <div className="prov-head"><h2 className="prov-title">Work sources</h2>
      <button className="btn-ghost" type="button" onClick={reload}>Refresh work sources</button></div>
    <p className="prov-note">Connect a tracker project to a registered repository. Only confirmed people on this source's allowlist may select a profile.</p>
    {error && <p className="prov-note prov-error" role="alert">{error}</p>}
    {!data ? <p className="prov-note">Loading work sources…</p> : <>
      {data.sources.length === 0 ? <div className="wh-empty">
        <div className="wh-empty-icon"><ListTodo size={22} aria-hidden="true" /></div>
        <div className="wh-empty-title">No work sources registered.</div>
        <p className="wh-empty-text">Add a tracker project below to bring tickets into Work items. Select a registered repository and a compatible account.</p>
      </div> : <div className="prov-scroll"><table className="prov-table">
        <thead><tr><th>Source</th><th>Tracker</th><th>Project</th><th>Repository</th><th>State</th></tr></thead>
        <tbody>{data.sources.map(item => <tr key={item.id}>
          <td className="nowrap"><a className="prov-name mono nowrap" href="#/settings/work-sources" onClick={event => { event.preventDefault(); setSelected(item.id); }}>{item.name}</a></td>
          <td className="nowrap">{kinds[item.type] ?? 'Unknown tracker'}</td><td className="mono nowrap">{item.scope}</td>
          <td className="mono nowrap">{item.repository.workspace}/{item.repository.slug}</td>
          <td><div className="chips"><span className={`chip ${item.enabled ? 'on' : ''}`}>{item.enabled ? 'Available' : 'Unavailable'}</span></div>
            <div className="prov-sub">{item.health.split('_').join(' ')}</div></td>
        </tr>)}</tbody></table></div>}
      {source && <SourceDetails key={`${source.id}:${source.version.source}`} source={source} accounts={data.accounts} changed={reload} />}
      <CreateSource accounts={data.accounts} repositories={data.repositories} saved={reload} />
    </>}
  </div></section>;
}

function CreateSource({ accounts, repositories, saved }: { accounts: ProviderView[]; repositories: Repository[]; saved: () => void }) {
  const [type, setType] = useState<api.WorkSourceType>('GITHUB');
  const [name, setName] = useState(''), [accountId, setAccount] = useState(''), [repositoryId, setRepository] = useState(''), [scope, setScope] = useState('');
  const [busy, setBusy] = useState(false), [error, setError] = useState<string | null>(null);
  const account = accounts.find(value => value.id === accountId && compatible(type, value));
  const choices = repositories.filter(repo => repo.enabled && (type === 'JIRA' || repo.scmType === type.toLowerCase() && repo.forgeOrigin === origin(account?.baseUrl ?? '')));
  const repository = choices.find(value => value.id === repositoryId);
  async function submit() {
    if (!account || !repository || !name.trim() || !scope.trim()) return;
    setBusy(true); setError(null);
    try { await api.createWorkSource({ name: name.trim(), type, origin: origin(account.baseUrl), scope: scope.trim(), repositoryId, accountId, enabled: true }); setName(''); saved(); }
    catch (failure) { setError(String(failure)); } finally { setBusy(false); }
  }
  return <fieldset className="modal-body" style={{ borderWidth: 0, borderStyle: 'none', margin: 0, minWidth: 0 }} disabled={busy}><legend className="field-sep">Add a work source</legend>
    <label className="field">Source name<input value={name} onChange={event => setName(event.target.value)} /></label>
    <label className="field">Tracker<select value={type} onChange={event => { setType(event.target.value as api.WorkSourceType); setAccount(''); setRepository(''); setScope(''); }}>
      {Object.entries(kinds).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
    </select></label>
    <label className="field">Tracker account<select value={accountId} onChange={event => { setAccount(event.target.value); setRepository(''); setScope(''); }}>
      <option value="">Select an account</option>{accounts.filter(value => compatible(type, value)).map(value => <option key={value.id} value={value.id}>{value.name}</option>)}
    </select></label>
    <label className="field">Target repository<select value={repositoryId} onChange={event => {
      setRepository(event.target.value); const repo = choices.find(value => value.id === event.target.value);
      if (type !== 'JIRA') setScope(repo ? `${repo.workspace}/${repo.slug}` : '');
    }}><option value="">Select a repository</option>{choices.map(repo => <option key={repo.id} value={repo.id}>{repo.workspace}/{repo.slug}</option>)}</select></label>
    <label className="field">{type === 'JIRA' ? 'Jira project key' : 'Tracker repository'}<input value={scope} readOnly={type !== 'JIRA'} onChange={event => setScope(event.target.value)} /></label>
    {type === 'JIRA' && <p>Jira is polled. Its project may target a repository on another forge; the Jira credential stays on the tracker. Unconfirmed label authors select no profile.</p>}
    {error && <p className="prov-error" role="alert">{error}</p>}
    <div className="prov-actions"><button className="btn" type="button" disabled={busy || !account || !repository || !name.trim() || !scope.trim()} onClick={() => void submit()}>Register work source</button></div>
  </fieldset>;
}

function SourceDetails({ source, accounts, changed }: { source: api.WorkSource; accounts: ProviderView[]; changed: () => void }) {
  const [name, setName] = useState(source.name), [accountId, setAccount] = useState(source.accountId);
  const [enabled, setEnabled] = useState(source.configuredEnabled);
  const [draft, setDraft] = useState(''), [selected, setSelected] = useState('');
  const [resolution, setResolution] = useState<ActorResult | null>(null);
  const [capabilities, setCapabilities] = useState<api.WorkCapabilities | null>(null);
  const [busy, setBusy] = useState(false), [error, setError] = useState<string | null>(null);
  async function action(run: () => Promise<unknown>, reload = true) {
    setBusy(true); setError(null);
    try { await run(); if (reload) changed(); } catch (failure) { setError(String(failure)); } finally { setBusy(false); }
  }
  return <fieldset className="modal-body" style={{ borderWidth: 0, borderStyle: 'none', margin: 0, minWidth: 0 }} disabled={busy}><legend className="field-sep">{source.name}</legend>
    <p>{source.origin} · {source.scope}</p>
    <label className="field">Edit source name<input value={name} onChange={event => setName(event.target.value)} /></label>
    <label className="field">Source account<select value={accountId} onChange={event => setAccount(event.target.value)}>
      {accounts.filter(account => compatible(source.type, account) && origin(account.baseUrl) === source.origin || account.id === source.accountId)
        .map(account => <option key={account.id} value={account.id}>{account.name}{!account.enabled ? ' (disabled)' : ''}</option>)}
    </select></label>
    <label className="field-check"><input type="checkbox" checked={enabled} onChange={event => setEnabled(event.target.checked)} /><span>Source enabled</span></label>
    {!source.enabled && source.configuredEnabled && <p>This source is enabled, but its account or repository is unavailable.</p>}
    <div className="prov-actions" style={{ flexWrap: 'wrap' }}><button className="btn" type="button" disabled={busy || !name.trim()} onClick={() => void action(() => api.editWorkSource(source, { name, accountId, enabled }))}>Save source</button>
    <button className="btn-ghost" type="button" disabled={busy || !source.enabled} onClick={() => void action(() => api.rescanWorkSource(source.id))}>Request rescan</button>
    <button className="btn-ghost" type="button" disabled={busy || !source.enabled} onClick={() => void action(async () => setCapabilities(await api.workCapabilities(source.id)), false)}>Check supported operations</button></div>
    {capabilities && <p>{capabilities.operations.map(value => value.toLowerCase().split('_').join(' ')).join(', ')}. {capabilities.detail}</p>}
    <h4 className="prov-title">Allowed people</h4>
    {source.type === 'JIRA' && <p>Jira Cloud display names are not unique. Resolve and select an account explicitly. Data Center person lookup is currently unavailable.</p>}
    <label className="field">Person<input value={draft} onChange={event => { setDraft(event.target.value); setResolution(null); setSelected(''); }} /></label>
    <div className="prov-actions"><button className="btn-ghost" type="button" disabled={busy || !draft.trim()} onClick={() => {
      setResolution(null); setSelected(''); void action(async () => {
        const result = await api.resolveWorkActor(source.id, draft); setResolution(result);
        setSelected(result.status === 'FOUND' && result.actors.length === 1 ? result.actors[0].providerUserId : '');
      }, false);
    }}>Resolve source person</button></div>
    {resolution && <><p>{resolution.detail}</p><label className="field">Resolved source person<select value={selected} onChange={event => setSelected(event.target.value)}>
      <option value="">Select a person</option>{resolution.actors.map(actor => <option key={actor.providerUserId} value={actor.providerUserId}>{actorLabel(actor)} · {actor.providerUserId}</option>)}
    </select></label></>}
    <div className="prov-actions"><button className="btn-ghost" type="button" disabled={busy || !selected} onClick={() => void action(() => api.saveWorkActor(source, draft, selected))}>Save source person</button></div>
    <ul>{source.allowedActors.map(id => <li key={id}>{id} <button className="btn-ghost" type="button" onClick={() => void action(() => api.removeWorkActor(source, id))}>Remove {id}</button></li>)}</ul>
    {error && <p className="prov-error" role="alert">{error}</p>}
  </fieldset>;
}
