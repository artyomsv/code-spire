import { useEffect, useState } from 'react';
import { fetchProviders, type ProviderView } from '../../api';
import { actorLabel, type ActorResult } from '../actorsApi';
import { fetchRepositories, type Repository } from '../repositories/repositoriesApi';
import * as api from './workSourcesApi';
import { ListTodo } from 'lucide-react';
import { accountOptionLabel } from '../accounts';

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
  const [adding, setAdding] = useState(false), [notice, setNotice] = useState('');
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
      <div className="prov-actions"><button className="btn-ghost" type="button" onClick={reload}>Refresh work sources</button>
        <button className="btn" type="button" disabled={!data || adding} onClick={() => { setAdding(true); setNotice(''); }}>Add work source</button></div></div>
    <p className="prov-note">Connect a tracker project to a registered repository. Only confirmed people on this source's allowlist may select a profile.</p>
    {error && <p className="prov-note prov-error" role="alert">{error}</p>}
    {notice && <p className="prov-note" role="status">{notice}</p>}
    {!data ? <p className="prov-note">Loading work sources…</p> : <>
      {data.sources.length === 0 ? <div className="wh-empty">
        <div className="wh-empty-icon"><ListTodo size={22} aria-hidden="true" /></div>
        <div className="wh-empty-title">No work sources registered.</div>
        <p className="wh-empty-text">Choose Add work source to bring tracker tickets into Work items. You will need a registered repository and a compatible account.</p>
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
      {adding && <CreateSource accounts={data.accounts} repositories={data.repositories} cancelled={() => setAdding(false)} saved={created => {
        setData(current => current && ({ ...current, sources: [...current.sources.filter(value => value.id !== created.id), created] }));
        setAdding(false); setSelected(created.id); setNotice(`Work source ${created.name} registered.`);
      }} />}
    </>}
  </div></section>;
}

function CreateSource({ accounts, repositories, saved, cancelled }: { accounts: ProviderView[]; repositories: Repository[]; saved: (source: api.WorkSource) => void; cancelled: () => void }) {
  const [type, setType] = useState<api.WorkSourceType>('GITHUB');
  const [name, setName] = useState(''), [accountId, setAccount] = useState(''), [repositoryId, setRepository] = useState(''), [scope, setScope] = useState('');
  const [busy, setBusy] = useState(false), [error, setError] = useState<string | null>(null);
  const account = accounts.find(value => value.id === accountId && compatible(type, value));
  const choices = repositories.filter(repo => repo.enabled && (type === 'JIRA' || repo.scmType === type.toLowerCase() && repo.forgeOrigin === origin(account?.baseUrl ?? '')));
  const repositoryPrompt = !account ? 'Choose a tracker account first' : choices.length ? 'Select a repository'
    : type === 'JIRA' ? 'No enabled repositories registered' : `No registered repository matches ${origin(account.baseUrl)}`;
  const repository = choices.find(value => value.id === repositoryId);
  async function submit() {
    if (!account || !repository || !name.trim() || !scope.trim()) return;
    setBusy(true); setError(null);
    try { const created = await api.createWorkSource({ name: name.trim(), type, origin: origin(account.baseUrl), scope: scope.trim(), repositoryId, accountId, enabled: true }); saved(created); }
    catch (failure) { setError(String(failure)); } finally { setBusy(false); }
  }
  return <fieldset className="modal-body" style={{ borderWidth: 0, borderStyle: 'none', margin: 0, minWidth: 0 }} disabled={busy}><legend className="field-sep">Add a work source</legend>
    <p className="prov-note">Fields marked required must be filled before registration.</p>
    <label className="field">Source name <span className="field-optional">required</span><input aria-label="Source name" required value={name} onChange={event => setName(event.target.value)} /><small className="field-hint">A name you will recognise in the source list.</small></label>
    <label className="field">Tracker <span className="field-optional">required</span><select aria-label="Tracker" required value={type} onChange={event => { setType(event.target.value as api.WorkSourceType); setAccount(''); setRepository(''); setScope(''); }}>
      {Object.entries(kinds).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
    </select><small className="field-hint">Where the tickets and their labels live.</small></label>
    <label className="field">Tracker account <span className="field-optional">required</span><select aria-label="Tracker account" required value={accountId} onChange={event => { setAccount(event.target.value); setRepository(''); setScope(''); }}>
      <option value="">Select an account</option>{accounts.filter(value => compatible(type, value)).map(value => <option key={value.id} value={value.id}>{accountOptionLabel(value)}</option>)}
    </select><small className="field-hint">An enabled account with supported authentication for this tracker. Manage credentials in <a href="#/settings/accounts">Accounts</a>.</small></label>
    {!accounts.some(value => compatible(type, value)) && <p className="prov-note">No compatible {kinds[type]} accounts. <a href="#/settings/accounts">Add an account</a> first.</p>}
    <label className="field">Target repository <span className="field-optional">required</span><select aria-label="Target repository" aria-describedby="source-repository-help" required disabled={!account || choices.length === 0} value={repositoryId} onChange={event => {
      setRepository(event.target.value); const repo = choices.find(value => value.id === event.target.value);
      if (type !== 'JIRA') setScope(repo ? `${repo.workspace}/${repo.slug}` : '');
    }}><option value="">{repositoryPrompt}</option>{account && choices.map(repo => <option key={repo.id} value={repo.id}>{repo.workspace}/{repo.slug}</option>)}</select>
      <small className="field-hint" id="source-repository-help">{!account ? 'Choose a tracker account first.' : choices.length === 0
        ? <>{repositoryPrompt}. <a href="#/settings/repositories">Register a repository</a>.</>
        : type === 'JIRA' ? 'The code repository for this project; it may be on another forge.' : `The code repository must be on ${origin(account.baseUrl)}.`}</small></label>
    <label className="field">{type === 'JIRA' ? 'Jira project key' : 'Tracker repository'} <span className="field-optional">{type === 'JIRA' ? 'required' : 'automatic'}</span>
      <input aria-label={type === 'JIRA' ? 'Jira project key' : 'Tracker repository'} aria-describedby="source-scope-help" required={type === 'JIRA'} value={scope} readOnly={type !== 'JIRA'} placeholder={type === 'JIRA' ? 'e.g. ENG' : 'Filled from the target repository'} onChange={event => setScope(event.target.value)} />
      <small className="field-hint" id="source-scope-help">{type === 'JIRA' ? 'The Jira project key containing your tickets, for example ENG.' : 'Automatically filled from the target repository; choose a different repository above to change it.'}</small></label>
    {type === 'JIRA' && <p className="prov-note">Jira is polled. Its project may target a repository on another forge; the Jira credential stays on the tracker. Unconfirmed label authors select no profile.</p>}
    {error && <p className="prov-error" role="alert">{error}</p>}
    <div className="prov-actions"><button className="btn" type="button" disabled={busy || !account || !repository || !name.trim() || !scope.trim()} onClick={() => void submit()}>Register work source</button>
      <button className="btn-ghost" type="button" onClick={cancelled}>Cancel</button></div>
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
    <p className="prov-note">{source.origin} · {source.scope}</p>
    <label className="field">Edit source name <span className="field-optional">required</span><input aria-label="Edit source name" required value={name} onChange={event => setName(event.target.value)} /><small className="field-hint">The name shown in the source list.</small></label>
    <label className="field">Source account <span className="field-optional">required</span><select aria-label="Source account" required value={accountId} onChange={event => setAccount(event.target.value)}>
      {accounts.filter(account => compatible(source.type, account) && origin(account.baseUrl) === source.origin || account.id === source.accountId)
        .map(account => <option key={account.id} value={account.id}>{accountOptionLabel(account)}</option>)}
    </select><small className="field-hint">Choose a compatible account on this source's tracker origin. Disabled accounts cannot process tickets.</small></label>
    <label className="field-check"><input type="checkbox" checked={enabled} onChange={event => setEnabled(event.target.checked)} /><span>Source enabled</span></label>
    {!source.enabled && source.configuredEnabled && <p>This source is enabled, but its account or repository is unavailable.</p>}
    <div className="prov-actions" style={{ flexWrap: 'wrap' }}><button className="btn" type="button" disabled={busy || !name.trim()} onClick={() => void action(() => api.editWorkSource(source, { name, accountId, enabled }))}>Save source</button>
    <button className="btn-ghost" type="button" disabled={busy || !source.enabled} onClick={() => void action(() => api.rescanWorkSource(source.id))}>Request rescan</button>
    <button className="btn-ghost" type="button" disabled={busy || !source.enabled} onClick={() => void action(async () => setCapabilities(await api.workCapabilities(source.id)), false)}>Check supported operations</button></div>
    {capabilities && <p>{capabilities.operations.map(value => value.toLowerCase().split('_').join(' ')).join(', ')}. {capabilities.detail}</p>}
    <h4 className="prov-title">Allowed people</h4>
    {source.type === 'JIRA' && <p>Jira Cloud display names are not unique. Resolve and select an account explicitly. Data Center person lookup is currently unavailable.</p>}
    <p className="prov-note">Only these people's attributed labels may choose a profile. An empty list grants no label authority.</p>
    <label className="field">Person <span className="field-optional">optional; required to add a person</span><input aria-label="Person" value={draft} onChange={event => { setDraft(event.target.value); setResolution(null); setSelected(''); }} /><small className="field-hint">Enter a handle, then resolve and confirm the tracker identity before saving.</small></label>
    <div className="prov-actions"><button className="btn-ghost" type="button" disabled={busy || !draft.trim()} onClick={() => {
      setResolution(null); setSelected(''); void action(async () => {
        const result = await api.resolveWorkActor(source.id, draft); setResolution(result);
        setSelected(result.status === 'FOUND' && result.actors.length === 1 ? result.actors[0].providerUserId : '');
      }, false);
    }}>Resolve source person</button></div>
    {resolution && <><p>{resolution.detail}</p><label className="field">Resolved source person <span className="field-optional">required to add a person</span><select aria-label="Resolved source person" value={selected} onChange={event => setSelected(event.target.value)}>
      <option value="">Select a person</option>{resolution.actors.map(actor => <option key={actor.providerUserId} value={actor.providerUserId}>{actorLabel(actor)} · {actor.providerUserId}</option>)}
    </select><small className="field-hint">Confirm the person to grant label authority on this source.</small></label></>}
    <div className="prov-actions"><button className="btn-ghost" type="button" disabled={busy || !selected} onClick={() => void action(() => api.saveWorkActor(source, draft, selected))}>Save source person</button></div>
    <ul>{source.allowedActors.map(id => <li key={id}>{id} <button className="btn-ghost" type="button" onClick={() => void action(() => api.removeWorkActor(source, id))}>Remove {id}</button></li>)}</ul>
    {error && <p className="prov-error" role="alert">{error}</p>}
  </fieldset>;
}
