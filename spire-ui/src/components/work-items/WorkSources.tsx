import { useEffect, useState } from 'react';
import { fetchProviders, type ProviderView } from '../../api';
import { actorLabel, type ActorResult } from '../actorsApi';
import { fetchRepositories, type Repository } from '../repositories/repositoriesApi';
import * as api from './workSourcesApi';
import { ListTodo } from 'lucide-react';
import { accountOptionLabel } from '../accounts';
import SettingField from '../SettingField';
import SidePanel from '../SidePanel';

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
        <thead><tr><th>Source</th><th>Tracker</th><th>Project</th><th>Repository</th><th>Allowed people</th><th>State</th></tr></thead>
        <tbody>{data.sources.map(item => <tr key={item.id}>
          <td className="nowrap"><a className="prov-name mono nowrap" href="#/settings/work-sources" onClick={event => { event.preventDefault(); setSelected(item.id); }}>{item.name}</a></td>
          <td className="nowrap">{kinds[item.type] ?? 'Unknown tracker'}</td><td className="mono nowrap">{item.scope}</td>
          <td className="mono nowrap">{item.repository.workspace}/{item.repository.slug}</td>
          {/* An empty allowlist grants no label authority, so the count is the difference between a
              source that works and one that silently selects nothing. It belongs in the list. */}
          <td className="nowrap">{item.allowedActors.length}
            {item.allowedActors.length === 0 && <div className="prov-sub">no ticket starts work</div>}</td>
          <td><div className="chips"><span className={`chip ${item.enabled ? 'on' : ''}`}>{item.enabled ? 'Available' : 'Unavailable'}</span></div>
            <div className="prov-sub">{item.health.split('_').join(' ')}</div></td>
        </tr>)}</tbody></table></div>}
      {source && <SourceDetails key={`${source.id}:${source.version.source}`} source={source} accounts={data.accounts}
        closed={() => setSelected('')} changed={reload} />}
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
  const scopeLabel = type === 'JIRA' ? 'Jira project key' : 'Tracker repository';
  return <SidePanel title="Add a work source" busy={busy} onClose={cancelled} actions={<>
    <button className="btn" type="button" disabled={busy || !account || !repository || !name.trim() || !scope.trim()} onClick={() => void submit()}>Register work source</button>
    <button className="btn-ghost" type="button" onClick={cancelled}>Cancel</button></>}>
    <SettingField label="Source name" scope="work source" hint="Required. A name you will recognise in the source list.">
      <input aria-label="Source name" required value={name} onChange={event => setName(event.target.value)} /></SettingField>
    <SettingField label="Tracker" scope="work source" hint="Required. Where the tickets and their labels live.">
      <select aria-label="Tracker" required value={type} onChange={event => { setType(event.target.value as api.WorkSourceType); setAccount(''); setRepository(''); setScope(''); }}>
        {Object.entries(kinds).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
      </select></SettingField>
    <SettingField label="Tracker account" scope="work source"
      hint="Required. An enabled account with supported authentication for this tracker. Manage credentials on the Accounts screen.">
      <select aria-label="Tracker account" required value={accountId} onChange={event => { setAccount(event.target.value); setRepository(''); setScope(''); }}>
        <option value="">Select an account</option>{accounts.filter(value => compatible(type, value)).map(value => <option key={value.id} value={value.id}>{accountOptionLabel(value)}</option>)}
      </select></SettingField>
    {!accounts.some(value => compatible(type, value)) && <p className="prov-note">No compatible {kinds[type]} accounts. <a href="#/settings/accounts">Add an account</a> first.</p>}
    <SettingField label="Target repository" scope="work source"
      hint={!account ? 'Required. Choose a tracker account first; the list is limited to repositories on that account’s forge.'
        : choices.length === 0 ? `Required. ${repositoryPrompt}. Register one on the Repositories screen.`
        : type === 'JIRA' ? 'Required. The code repository for this project; it may be on another forge.'
        : `Required. The code repository must be on ${origin(account.baseUrl)}.`}>
      <select aria-label="Target repository" required disabled={!account || choices.length === 0} value={repositoryId} onChange={event => {
        setRepository(event.target.value); const repo = choices.find(value => value.id === event.target.value);
        if (type !== 'JIRA') setScope(repo ? `${repo.workspace}/${repo.slug}` : '');
      }}><option value="">{repositoryPrompt}</option>{account && choices.map(repo => <option key={repo.id} value={repo.id}>{repo.workspace}/{repo.slug}</option>)}</select></SettingField>
    {account && choices.length === 0 && <p className="prov-note">{repositoryPrompt}. <a href="#/settings/repositories">Register a repository</a>.</p>}
    <SettingField label={scopeLabel} scope="work source"
      hint={type === 'JIRA' ? 'Required. The Jira project key containing your tickets, for example ENG.'
        : 'Filled automatically from the target repository. Choose a different repository above to change it.'}>
      <input aria-label={scopeLabel} required={type === 'JIRA'} value={scope} readOnly={type !== 'JIRA'}
        placeholder={type === 'JIRA' ? 'e.g. ENG' : 'Filled from the target repository'} onChange={event => setScope(event.target.value)} /></SettingField>
    {type === 'JIRA' && <p className="prov-note">Jira is polled. Its project may target a repository on another forge; the Jira credential stays on the tracker. Unconfirmed label authors select no profile.</p>}
    {error && <p className="prov-error" role="alert">{error}</p>}
  </SidePanel>;
}

function SourceDetails({ source, accounts, changed, closed }: { source: api.WorkSource; accounts: ProviderView[]; changed: () => void; closed: () => void }) {
  const [name, setName] = useState(source.name), [accountId, setAccount] = useState(source.accountId);
  const [enabled, setEnabled] = useState(source.configuredEnabled);
  const [draft, setDraft] = useState(''), [selected, setSelected] = useState('');
  const [resolution, setResolution] = useState<ActorResult | null>(null);
  const [capabilities, setCapabilities] = useState<api.WorkCapabilities | null>(null);
  const [busy, setBusy] = useState(false), [error, setError] = useState<string | null>(null);
  // Connection and people are separate jobs on the same source. Both stay mounted so a half-typed
  // handle survives a tab switch; `hidden` keeps the inactive one out of the accessibility tree.
  const [tab, setTab] = useState('connection');
  async function action(run: () => Promise<unknown>, reload = true) {
    setBusy(true); setError(null);
    try { await run(); if (reload) changed(); } catch (failure) { setError(String(failure)); } finally { setBusy(false); }
  }
  return <SidePanel title={source.name} subtitle={`${source.origin} · ${source.scope}`} busy={busy} onClose={closed}
    tabs={[{ id: 'connection', label: 'Connection' }, { id: 'people', label: 'Allowed people', count: source.allowedActors.length }]}
    tab={tab} onTab={setTab}
    actions={<>
      <button className="btn" type="button" disabled={busy || !name.trim()} onClick={() => void action(() => api.editWorkSource(source, { name, accountId, enabled }))}>Save source</button>
      <button className="btn-ghost" type="button" onClick={closed}>Close</button></>}>
    <div role="tabpanel" aria-label="Connection" hidden={tab !== 'connection'} className="panel-section">
      <SettingField label="Edit source name" scope="work source" hint="Required. The name shown in the source list.">
        <input aria-label="Edit source name" required value={name} onChange={event => setName(event.target.value)} /></SettingField>
      <SettingField label="Source account" scope="work source"
        hint="Required. A compatible account on this source's tracker origin. Disabled accounts cannot process tickets.">
        <select aria-label="Source account" required value={accountId} onChange={event => setAccount(event.target.value)}>
          {accounts.filter(account => compatible(source.type, account) && origin(account.baseUrl) === source.origin || account.id === source.accountId)
            .map(account => <option key={account.id} value={account.id}>{accountOptionLabel(account)}</option>)}
        </select></SettingField>
      <label className="field-check"><input type="checkbox" checked={enabled} onChange={event => setEnabled(event.target.checked)} /><span>Source enabled</span></label>
      {!source.enabled && source.configuredEnabled && <p className="prov-note">This source is enabled, but its account or repository is unavailable.</p>}
      <div className="prov-actions">
        <button className="btn-ghost" type="button" disabled={busy || !source.enabled} onClick={() => void action(() => api.rescanWorkSource(source.id))}>Request rescan</button>
        <button className="btn-ghost" type="button" disabled={busy || !source.enabled} onClick={() => void action(async () => setCapabilities(await api.workCapabilities(source.id)), false)}>Check supported operations</button></div>
      {capabilities && <p className="prov-note">{capabilities.operations.map(value => value.toLowerCase().split('_').join(' ')).join(', ')}. {capabilities.detail}</p>}
    </div>
    <div role="tabpanel" aria-label="Allowed people" hidden={tab !== 'people'} className="panel-section">
      {source.type === 'JIRA' && <p className="prov-note">Jira Cloud display names are not unique. Resolve and select an account explicitly. Data Center person lookup is currently unavailable.</p>}
      <p className="prov-note">Only these people's attributed labels may choose a profile. An empty list grants no label authority.</p>
      <SettingField label="Person" scope="allowed people" hint="Optional, and required to add a person. Enter a handle, then resolve and confirm the tracker identity before saving.">
        <input aria-label="Person" value={draft} onChange={event => { setDraft(event.target.value); setResolution(null); setSelected(''); }} /></SettingField>
      <div className="prov-actions"><button className="btn-ghost" type="button" disabled={busy || !draft.trim()} onClick={() => {
        setResolution(null); setSelected(''); void action(async () => {
          const result = await api.resolveWorkActor(source.id, draft); setResolution(result);
          setSelected(result.status === 'FOUND' && result.actors.length === 1 ? result.actors[0].providerUserId : '');
        }, false);
      }}>Resolve source person</button>
        <button className="btn-ghost" type="button" disabled={busy || !selected} onClick={() => void action(() => api.saveWorkActor(source, draft, selected))}>Save source person</button></div>
      {resolution && <><p className="prov-note">{resolution.detail}</p>
        <SettingField label="Resolved source person" scope="allowed people" hint="Required to add a person. Confirm the identity to grant label authority on this source.">
          <select aria-label="Resolved source person" value={selected} onChange={event => setSelected(event.target.value)}>
            <option value="">Select a person</option>{resolution.actors.map(actor => <option key={actor.providerUserId} value={actor.providerUserId}>{actorLabel(actor)} · {actor.providerUserId}</option>)}
          </select></SettingField></>}
      <ul className="prov-list">{source.allowedActors.map(id => <li key={id}><span className="mono nowrap">{id}</span>
        <button className="btn-ghost" type="button" onClick={() => void action(() => api.removeWorkActor(source, id))}>Remove {id}</button></li>)}</ul>
    </div>
    {error && <p className="prov-error" role="alert">{error}</p>}
  </SidePanel>;
}
