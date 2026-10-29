import { useEffect, useState } from 'react';
import { useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { useLocation, useNavigate } from 'react-router';
import { fetchProviders, fetchWebhookRepos, providersKey, webhookReposKey, type WebhookRepoView } from '../../api';
import RepositoryDetail from './RepositoryDetail';
import RepositoryForm from './RepositoryForm';
import RepositorySettings, { SETTLED, type SettingsState } from './RepositorySettings';
import RepositoryPending from './RepositoryPending';
import RepositoryAccountsCell from './RepositoryAccountsCell';
import SidePanel from '../SidePanel';
import { CopyableValue } from '../../render';
import { GitBranch } from 'lucide-react';
import { fetchRepositories, fetchRepositoryKinds, repositoriesKey, repositoryKindsKey, type Repository } from './repositoriesApi';
import { fetchWorkSources, workSourcesKey } from '../work-items/workSourcesApi';
import { policy as fetchPolicy } from '../work-items/workPolicyApi';
import { policyKeys } from '../work-items/workQueries';
import RepositoryFactory from './factory/RepositoryFactory';
import FactoryProgress from './factory/FactoryProgress';
import { readiness } from './factory/factoryModel';
import { updateLive, writeLive } from '../../liveCache';

/** Repository configuration remains usable when the separate webhook service is unavailable. */
export default function RepositoryRegistryPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const client = useQueryClient();
  const repositoriesRead = useQuery({ queryKey: repositoriesKey, queryFn: () => fetchRepositories() });
  const providersRead = useQuery({ queryKey: providersKey, queryFn: () => fetchProviders() });
  const kindsRead = useQuery({ queryKey: repositoryKindsKey, queryFn: () => fetchRepositoryKinds() });
  // The page needs all three; a re-read that fails after they answered keeps what is on screen.
  const loaded = repositoriesRead.data !== undefined && providersRead.data !== undefined && kindsRead.data !== undefined;
  const loadFailure = repositoriesRead.error ?? providersRead.error ?? kindsRead.error;
  const loading = !loaded && !loadFailure;
  const error = !loaded && loadFailure ? String(loadFailure) : null;
  const repositories = repositoriesRead.data ?? [], providers = providersRead.data ?? [], kinds = kindsRead.data ?? [];
  // Webhooks come from a separate service, so they fail on their own and the rest of the page stays usable.
  const hooksRead = useQuery({ queryKey: webhookReposKey, queryFn: () => fetchWebhookRepos() });
  const hooks = hooksRead.data ?? [], hookError = hooksRead.error ? String(hooksRead.error) : null;
  const [registering, setRegistering] = useState(false);
  const [settings, setSettings] = useState<SettingsState>(SETTLED);
  const [selected, setSelected] = useState<string | null>(null);
  const [tab, setTab] = useState('details');
  // The setup column is a summary; a row whose sources or policy cannot load says nothing, and the panels still work.
  // It reads the keys the Factory tab reads and saves, so a save there reaches this column without a callback.
  const sourcesRead = useQuery({ queryKey: workSourcesKey, queryFn: () => fetchWorkSources() });
  const policyReads = useQueries({ queries: repositories.map(repository =>
    ({ queryKey: policyKeys.policy(repository.id), queryFn: () => fetchPolicy(repository.id) })) });
  function readinessOf(repository: Repository, index: number) {
    const policy = policyReads[index]?.data;
    if (!sourcesRead.data || !policy) return null;
    return readiness(sourcesRead.data.filter(source => source.repositoryId === repository.id), policy);
  }

  useEffect(() => {
    const query = new URLSearchParams(location.search);
    if (query.get('register') === 'true') setRegistering(true);
    const legacy = query.get('edit');
    if (legacy) navigate(`/settings/webhooks?edit=${encodeURIComponent(legacy)}`, { replace: true });
  }, [location.search, navigate]);

  // A write cancels a read of the same key first (liveCache): a read started before it would land after it and undo it.
  function setHooks(rows: WebhookRepoView[]) { writeLive(client, webhookReposKey, rows); }
  function replace(repository: Repository) {
    updateLive<Repository[]>(client, repositoriesKey, current => [...(current ?? []).filter(row => row.id !== repository.id), repository]);
    // The server reports a source enabled only while its repository is, so the setup column reads them again
    // (review of PR 358, M-1).
    void client.invalidateQueries({ queryKey: workSourcesKey, exact: true });
  }
  function registered(repository: Repository) {
    replace(repository);
    setRegistering(false);
    setTab('details');
    setSelected(repository.id);
  }
  const chosen = repositories.find(repository => repository.id === selected);
  return <section className="content"><div className="card">
    <div className="prov-head"><h2 className="prov-title">Registered repositories</h2>
      {repositories.length > 0 && <button className="btn" onClick={() => setRegistering(true)}>Register repository</button>}
    </div>
    <p className="prov-note">Repositories own their workspace and select the accounts used for reviews and runs.</p>
    <p className="prov-note"><a className="btn-ghost" href="#/settings/webhooks">Manage legacy and organization webhooks</a></p>
    {loading ? <p className="prov-note">Loading repositories…</p> : error ? <p className="prov-note prov-error" role="alert">{error}</p> : <>
      {hookError && <div className="prov-note" role="alert"><p className="prov-error">Webhooks could not be loaded: {hookError}. Repository settings remain available.</p>
        <button className="btn-ghost" onClick={() => void hooksRead.refetch()}>Retry loading webhooks</button></div>}
      {/* What is waiting for the operator goes above the list. Below it, it was not found. */}
      <RepositoryPending repositories={repositories} onHooksChanged={setHooks} />
      {repositories.length === 0 ? <div className="wh-empty">
        <div className="wh-empty-icon"><GitBranch size={22} aria-hidden="true" /></div>
        <div className="wh-empty-title">No registered repositories yet.</div>
        <p className="wh-empty-text">Register a repository, then select its review and factory accounts and webhook kinds.</p>
        <button className="btn" onClick={() => setRegistering(true)}>Register repository</button>
      </div> : <div className="prov-scroll"><table className="prov-table">
        <thead><tr><th>Repository</th><th>Forge origin</th><th>Workspace</th><th>Accounts</th><th>Factory setup</th><th>State</th></tr></thead>
        <tbody>{repositories.map((repository, index) => <tr key={repository.id}>
          <td className="nowrap"><a className="prov-name mono nowrap" href="#/settings/repositories" onClick={event => { event.preventDefault(); setTab('details'); setSelected(repository.id); }}>{repository.slug}</a>
            <div className="prov-sub">{repository.scmType}</div></td>
          <td className="mono nowrap"><div className="wh-url"><CopyableValue text={repository.forgeOrigin} mono /></div></td><td className="mono nowrap">{repository.workspace}</td>
          <td><RepositoryAccountsCell repository={repository} /></td>
          <td><FactoryProgress ready={readinessOf(repository, index)} /></td>
          <td><div className="chips"><span className={`chip ${repository.enabled ? 'on' : ''}`}>{repository.enabled ? 'Enabled' : 'Disabled'}</span></div></td>
        </tr>)}</tbody>
      </table></div>}
      {chosen && <SidePanel wide title={chosen.slug} subtitle={`${chosen.workspace} · ${chosen.forgeOrigin}`}
        busy={settings.busy} onClose={() => setSelected(null)}
        tabs={[{ id: 'details', label: 'Details' }, { id: 'settings', label: 'Settings', marked: settings.dirty }, { id: 'factory', label: 'Factory' }]}
        tab={tab} onTab={setTab}
        actions={<button className="btn-ghost" type="button" disabled={settings.busy} onClick={() => setSelected(null)}>Close</button>}>
        {/* Siblings, so each key names its tab too: one shared key made React leave old tabs behind (2026-10-20). */}
        {tab === 'details' && <RepositoryDetail key={`details:${chosen.id}`} repository={chosen} hooks={hooks} onHooksChanged={setHooks} />}
        {tab === 'factory' && <RepositoryFactory key={`factory:${chosen.id}`} repository={chosen} accounts={providers}
          webhooks={{ hooks, unavailable: !!hookError, changed: setHooks }} />}
        {/* Edit lives in this panel, never in a second one over it (operator, 2026-10-16). */}
        <RepositorySettings key={`settings:${chosen.id}`} repository={chosen} providers={providers} hidden={tab !== 'settings'}
          onSaved={replace} onState={setSettings} />
      </SidePanel>}
      {registering && <RepositoryForm key={`new:${location.search}`}
        prefill={new URLSearchParams(location.search)}
        providers={providers} kinds={kinds} onSaved={registered} onCancel={() => setRegistering(false)} />}
    </>}
  </div></section>;
}
