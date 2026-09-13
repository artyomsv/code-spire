import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { fetchProviders, fetchWebhookRepos, type ProviderView, type WebhookRepoView } from '../../api';
import RepositoryDetail from './RepositoryDetail';
import RepositoryForm from './RepositoryForm';
import RepositoryPending from './RepositoryPending';
import { fetchRepositories, fetchRepositoryKinds, type Repository, type RepositoryAccount } from './repositoriesApi';

function accountLabel(account: RepositoryAccount | null): string {
  if (!account) return 'No account selected';
  return `${account.name}${account.handle ? ` (@${account.handle})` : ''} · ${account.state}`;
}

/** Repository configuration remains usable when the separate webhook service is unavailable. */
export default function RepositoryRegistryPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const [repositories, setRepositories] = useState<Repository[]>([]);
  const [providers, setProviders] = useState<ProviderView[]>([]);
  const [editing, setEditing] = useState<Repository | 'new' | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [kinds, setKinds] = useState<string[]>([]);
  const [webhooks, setWebhooks] = useState<{ rows: WebhookRepoView[]; error: string | null }>({ rows: [], error: null });
  const { rows: hooks, error: hookError } = webhooks;
  const setHooks = (rows: WebhookRepoView[]) => setWebhooks({ rows, error: null });
  const [selected, setSelected] = useState<string | null>(null);

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
    const query = new URLSearchParams(location.search);
    if (query.get('register') === 'true') setEditing('new');
    const legacy = query.get('edit');
    if (legacy) navigate(`/settings/webhooks?edit=${encodeURIComponent(legacy)}`, { replace: true });
  }, [location.search, navigate]);

  function saved(repository: Repository) {
    setRepositories(previous => [...previous.filter(row => row.id !== repository.id), repository]);
    setEditing(null);
    setSelected(repository.id);
  }
  async function retryHooks() {
    try { setHooks(await fetchWebhookRepos()); }
    catch (failure) { setWebhooks(previous => ({ ...previous, error: String(failure) })); }
  }
  return <section className="content"><div className="card" style={{ padding: 18 }}>
    <h2>Registered repositories</h2>
    <p>Repositories own their workspace and select the accounts used for reviews and runs.</p>
    <p><a href="#/settings/webhooks">Manage legacy and organization webhooks</a></p>
    {loading ? <p>Loading repositories…</p> : error ? <p role="alert">{error}</p> : <>
      <button className="btn" onClick={() => setEditing('new')}>Register repository</button>
      {hookError && <p role="alert">Webhooks could not be loaded: {hookError}. Repository settings remain available.
        <button className="btn" onClick={() => void retryHooks()}>Retry loading webhooks</button></p>}
      <RepositoryPending repositories={repositories} onHooksChanged={setHooks} />
      {repositories.length === 0 ? <p>No registered repositories yet.</p> : <table>
        <thead><tr><th>Repository</th><th>Forge origin</th><th>Workspace</th><th>Reviewer</th><th>Factory</th><th>State</th></tr></thead>
        <tbody>{repositories.map(repository => <tr key={repository.id}>
          <td><button className="btn" onClick={() => setSelected(repository.id)}>{repository.slug}</button></td>
          <td>{repository.forgeOrigin}</td><td>{repository.workspace}</td>
          <td>{accountLabel(repository.reviewer)}</td><td>{accountLabel(repository.factory)}</td>
          <td>{repository.enabled ? 'Enabled' : 'Disabled'}</td>
        </tr>)}</tbody>
      </table>}
      {repositories.filter(repository => repository.id === selected).map(repository =>
        <RepositoryDetail key={repository.id} repository={repository} hooks={hooks} onHooksChanged={setHooks}
          onEdit={() => setEditing(repository)} />)}
      {editing && <RepositoryForm key={editing === 'new' ? `new:${location.search}` : editing.id} initial={editing === 'new' ? null : editing}
        prefill={new URLSearchParams(location.search)}
        providers={providers} kinds={kinds} onSaved={saved} onCancel={() => setEditing(null)} />}
    </>}
  </div></section>;
}
