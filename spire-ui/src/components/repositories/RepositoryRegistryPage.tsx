import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { fetchProviders, fetchWebhookRepos, type ProviderView, type WebhookRepoView } from '../../api';
import RepositoryDetail from './RepositoryDetail';
import RepositoryForm from './RepositoryForm';
import RepositorySettings, { SETTLED, type SettingsState } from './RepositorySettings';
import RepositoryPending from './RepositoryPending';
import RepositoryAccountsCell from './RepositoryAccountsCell';
import SidePanel from '../SidePanel';
import { CopyableValue } from '../../render';
import { GitBranch } from 'lucide-react';
import { fetchRepositories, fetchRepositoryKinds, type Repository } from './repositoriesApi';
import { fetchWorkSources, type WorkSource } from '../work-items/workSourcesApi';
import { policy as fetchPolicy, type Policy } from '../work-items/workPolicyApi';
import RepositoryFactory from './factory/RepositoryFactory';
import FactoryProgress from './factory/FactoryProgress';
import { readiness } from './factory/factoryModel';

/** Repository configuration remains usable when the separate webhook service is unavailable. */
export default function RepositoryRegistryPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const [repositories, setRepositories] = useState<Repository[]>([]);
  const [providers, setProviders] = useState<ProviderView[]>([]);
  const [registering, setRegistering] = useState(false);
  const [settings, setSettings] = useState<SettingsState>(SETTLED);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [kinds, setKinds] = useState<string[]>([]);
  const [webhooks, setWebhooks] = useState<{ rows: WebhookRepoView[]; error: string | null }>({ rows: [], error: null });
  const { rows: hooks, error: hookError } = webhooks;
  const setHooks = (rows: WebhookRepoView[]) => setWebhooks({ rows, error: null });
  const [selected, setSelected] = useState<string | null>(null);
  const [tab, setTab] = useState('details');
  // The setup column is a summary; when it cannot load, the repositories and their panels still work.
  const [setup, setSetup] = useState<{ sources: WorkSource[]; policies: Record<string, Policy> } | null>(null);
  const [setupRefresh, setSetupRefresh] = useState(0);

  useEffect(() => {
    let active = true;
    fetchWebhookRepos().then(webhooks => { if (active) setHooks(webhooks); })
      .catch(failure => { if (active) setWebhooks(previous => ({ ...previous, error: String(failure) })); });
    Promise.all([fetchRepositories(), fetchProviders(), fetchRepositoryKinds()]).then(([repos, accounts, supported]) => {
      if (active) {
        setRepositories(repos); setProviders(accounts); setKinds(supported);
      }
    }).catch(failure => { if (active) setError(String(failure)); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, []);

  useEffect(() => {
    let active = true;
    Promise.all([fetchWorkSources(), Promise.all(repositories.map(async repo => [repo.id, await fetchPolicy(repo.id)] as const))])
      .then(([sources, policies]) => { if (active) setSetup({ sources, policies: Object.fromEntries(policies) }); })
      .catch(() => { if (active) setSetup(null); });
    return () => { active = false; };
  }, [repositories, setupRefresh]);

  useEffect(() => {
    const query = new URLSearchParams(location.search);
    if (query.get('register') === 'true') setRegistering(true);
    const legacy = query.get('edit');
    if (legacy) navigate(`/settings/webhooks?edit=${encodeURIComponent(legacy)}`, { replace: true });
  }, [location.search, navigate]);

  function replace(repository: Repository) {
    setRepositories(previous => [...previous.filter(row => row.id !== repository.id), repository]);
  }
  function registered(repository: Repository) {
    replace(repository);
    setRegistering(false);
    setTab('details');
    setSelected(repository.id);
  }
  async function retryHooks() {
    try { setHooks(await fetchWebhookRepos()); }
    catch (failure) { setWebhooks(previous => ({ ...previous, error: String(failure) })); }
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
        <button className="btn-ghost" onClick={() => void retryHooks()}>Retry loading webhooks</button></div>}
      {/* What is waiting for the operator goes above the list. Below it, it was not found. */}
      <RepositoryPending repositories={repositories} onHooksChanged={setHooks} />
      {repositories.length === 0 ? <div className="wh-empty">
        <div className="wh-empty-icon"><GitBranch size={22} aria-hidden="true" /></div>
        <div className="wh-empty-title">No registered repositories yet.</div>
        <p className="wh-empty-text">Register a repository, then select its review and factory accounts and webhook kinds.</p>
        <button className="btn" onClick={() => setRegistering(true)}>Register repository</button>
      </div> : <div className="prov-scroll"><table className="prov-table">
        <thead><tr><th>Repository</th><th>Forge origin</th><th>Workspace</th><th>Accounts</th><th>Factory setup</th><th>State</th></tr></thead>
        <tbody>{repositories.map(repository => <tr key={repository.id}>
          <td className="nowrap"><a className="prov-name mono nowrap" href="#/settings/repositories" onClick={event => { event.preventDefault(); setTab('details'); setSelected(repository.id); }}>{repository.slug}</a>
            <div className="prov-sub">{repository.scmType}</div></td>
          <td className="mono nowrap"><div className="wh-url"><CopyableValue text={repository.forgeOrigin} mono /></div></td><td className="mono nowrap">{repository.workspace}</td>
          <td><RepositoryAccountsCell repository={repository} /></td>
          <td><FactoryProgress ready={setup ? readiness(setup.sources.filter(source => source.repositoryId === repository.id), setup.policies[repository.id] ?? null) : null} /></td>
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
          webhooks={{ hooks, unavailable: !!hookError, changed: setHooks }} onChanged={() => setSetupRefresh(value => value + 1)} />}
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
