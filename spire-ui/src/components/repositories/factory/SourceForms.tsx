import { useState } from 'react';
import type { ProviderView } from '../../../api';
import type { Repository } from '../repositoriesApi';
import * as api from '../../work-items/workSourcesApi';
import { accountOptionLabel } from '../../accounts';
import SettingField from '../../SettingField';

export const trackerNames: Record<api.WorkSourceType, string> = { GITHUB: 'GitHub', GITLAB: 'GitLab', JIRA: 'Jira' };
export function origin(base: string) { try { return new URL(base).origin; } catch { return ''; } }

/**
 * The trackers this repository can take tickets from. A forge's own issues only come from that forge;
 * Jira is polled separately and may feed a repository on any forge.
 */
export function trackersFor(repository: Repository): api.WorkSourceType[] {
  const own = repository.scmType === 'github' ? 'GITHUB' : repository.scmType === 'gitlab' ? 'GITLAB' : null;
  return own ? [own, 'JIRA'] : ['JIRA'];
}

/** An account can read this tracker for this repository: enabled, the right kind, supported sign-in and — for a forge — the same origin. */
export function compatibleAccount(type: api.WorkSourceType, account: ProviderView, repository: Repository) {
  if (!account.enabled) return false;
  if (type === 'JIRA') return account.type === 'atlassian' && (account.authKind === 'bearer' || account.authKind === 'basic');
  return account.type === type.toLowerCase() && account.authKind === 'bearer' && origin(account.baseUrl) === repository.forgeOrigin;
}

interface CreateProps { repository: Repository; accounts: ProviderView[]; saved: (source: api.WorkSource) => void; cancelled: () => void }

export function CreateSourceForm({ repository, accounts, saved, cancelled }: CreateProps) {
  const choices = trackersFor(repository);
  const [type, setType] = useState<api.WorkSourceType>(choices[0]);
  const [name, setName] = useState(`${repository.slug} issues`), [accountId, setAccount] = useState(''), [projectKey, setProjectKey] = useState('');
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  const offered = accounts.filter(value => compatibleAccount(type, value, repository));
  const account = offered.find(value => value.id === accountId);
  // A forge tracks issues in the repository itself, so its scope is the repository and is not asked for.
  const scope = type === 'JIRA' ? projectKey.trim() : `${repository.workspace}/${repository.slug}`;
  const ready = !!account && !!name.trim() && !!scope;
  async function submit() {
    if (!account) return;
    setBusy(true); setError('');
    try { saved(await api.createWorkSource({ name: name.trim(), type, origin: origin(account.baseUrl), scope, repositoryId: repository.id, accountId: account.id, enabled: true })); }
    catch (failure) { setError(String(failure)); } finally { setBusy(false); }
  }
  return <fieldset className="form-lock factory-form" aria-label="Add where tickets come from" disabled={busy}>
    <SettingField label="Source name" scope="work source" hint="Required. The name shown for this source. Prefilled from the repository; change it freely.">
      <input aria-label="Source name" required value={name} onChange={event => setName(event.target.value)} /></SettingField>
    <SettingField label="Tracker" scope="work source" hint="Required. Where the tickets and their labels live. A forge offers its own issues; Jira can feed any repository.">
      <select aria-label="Tracker" required value={type} onChange={event => { setType(event.target.value as api.WorkSourceType); setAccount(''); }}>
        {choices.map(value => <option key={value} value={value}>{trackerNames[value]}</option>)}
      </select></SettingField>
    <SettingField label="Tracker account" scope="work source"
      hint={type === 'JIRA' ? 'Required. An enabled Atlassian account. Manage credentials on the Accounts screen.'
        : `Required. An enabled ${trackerNames[type]} account on ${repository.forgeOrigin}, the origin of this repository.`}>
      <select aria-label="Tracker account" required value={accountId} onChange={event => setAccount(event.target.value)}>
        <option value="">{offered.length ? 'Select an account' : 'No compatible account'}</option>
        {offered.map(value => <option key={value.id} value={value.id}>{accountOptionLabel(value)}</option>)}
      </select></SettingField>
    {offered.length === 0 && <p className="prov-note">No enabled {trackerNames[type]} account {type === 'JIRA' ? 'exists' : `on ${repository.forgeOrigin}`}. <a href="#/settings/accounts">Add an account</a> first.</p>}
    {type === 'JIRA'
      ? <SettingField label="Jira project key" scope="work source" hint="Required. The Jira project whose tickets feed this repository, for example ENG.">
        <input aria-label="Jira project key" required value={projectKey} placeholder="e.g. ENG" onChange={event => setProjectKey(event.target.value)} /></SettingField>
      : <SettingField label="Tracker repository" scope="work source" hint="Fixed. A forge tracks issues in the repository itself.">
        <input aria-label="Tracker repository" readOnly value={scope} /></SettingField>}
    {type === 'JIRA' && <p className="prov-note">Jira is polled. The Jira credential stays on the tracker. Unconfirmed label authors select no profile.</p>}
    {error && <p className="prov-error" role="alert">{error}</p>}
    <div className="prov-actions">
      <button className="btn" type="button" disabled={busy || !ready} onClick={() => void submit()}>Register work source</button>
      <button className="btn-ghost" type="button" onClick={cancelled}>Cancel</button></div>
  </fieldset>;
}

interface EditProps { source: api.WorkSource; repository: Repository; accounts: ProviderView[]; saved: () => void; cancelled: () => void }

export function EditSourceForm({ source, repository, accounts, saved, cancelled }: EditProps) {
  const [name, setName] = useState(source.name), [accountId, setAccount] = useState(source.accountId), [enabled, setEnabled] = useState(source.configuredEnabled);
  const [capabilities, setCapabilities] = useState<api.WorkCapabilities | null>(null);
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  async function run(action: () => Promise<unknown>) {
    setBusy(true); setError('');
    try { await action(); } catch (failure) { setError(String(failure)); } finally { setBusy(false); }
  }
  // The current account stays listed even when it no longer qualifies, so the form shows what is saved.
  const offered = accounts.filter(account => compatibleAccount(source.type, account, repository) && origin(account.baseUrl) === source.origin || account.id === source.accountId);
  return <fieldset className="form-lock factory-form" aria-label={`Edit ${source.name}`} disabled={busy}>
    <SettingField label="Edit source name" scope="work source" hint="Required. The name shown for this source.">
      <input aria-label="Edit source name" required value={name} onChange={event => setName(event.target.value)} /></SettingField>
    <SettingField label="Source account" scope="work source" hint="Required. A compatible account on this source's tracker origin. Disabled accounts cannot read tickets.">
      <select aria-label="Source account" required value={accountId} onChange={event => setAccount(event.target.value)}>
        {offered.map(account => <option key={account.id} value={account.id}>{accountOptionLabel(account)}</option>)}
      </select></SettingField>
    <label className="field-check"><input type="checkbox" checked={enabled} onChange={event => setEnabled(event.target.checked)} /><span>Source enabled</span></label>
    {!source.enabled && source.configuredEnabled && <p className="prov-note">This source is enabled, but its account or repository is unavailable.</p>}
    {capabilities && <p className="prov-note">{capabilities.operations.map(value => value.toLowerCase().split('_').join(' ')).join(', ')}. {capabilities.detail}</p>}
    {error && <p className="prov-error" role="alert">{error}</p>}
    <div className="prov-actions">
      <button className="btn-ghost" type="button" disabled={busy || !source.enabled}
        onClick={() => void run(async () => setCapabilities(await api.workCapabilities(source.id)))}>Check supported operations</button>
      <span className="grow" />
      <button className="btn" type="button" disabled={busy || !name.trim()}
        onClick={() => void run(async () => { await api.editWorkSource(source, { name, accountId, enabled }); saved(); })}>Save source</button>
      <button className="btn-ghost" type="button" onClick={cancelled}>Cancel</button></div>
  </fieldset>;
}
