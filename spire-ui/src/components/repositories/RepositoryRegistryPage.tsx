import { useEffect, useState } from 'react';
import { fetchProviders, type ProviderView } from '../../api';
import RepositoryForm from './RepositoryForm';
import RepositoryPending from './RepositoryPending';
import { fetchRepositories, fetchRepositoryKinds, type Repository, type RepositoryAccount } from './repositoriesApi';

function accountLabel(account: RepositoryAccount | null): string {
  if (!account) return 'No account selected';
  return `${account.name}${account.handle ? ` (@${account.handle})` : ''} · ${account.state}`;
}

/** Bridge view: explicit bindings are inspectable before pipeline resolution cuts over in slice 2. */
export default function RepositoryRegistryPage() {
  const [repositories, setRepositories] = useState<Repository[]>([]);
  const [providers, setProviders] = useState<ProviderView[]>([]);
  const [editing, setEditing] = useState<Repository | 'new' | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [kinds, setKinds] = useState<string[]>([]);

  useEffect(() => {
    let active = true;
    Promise.all([fetchRepositories(), fetchProviders(), fetchRepositoryKinds()]).then(([repos, accounts, supported]) => {
      if (active) { setRepositories(repos); setProviders(accounts); setKinds(supported); }
    }).catch(failure => { if (active) setError(String(failure)); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, []);

  function saved(repository: Repository) {
    setRepositories(previous => [...previous.filter(row => row.id !== repository.id), repository]);
    setEditing(null);
  }
  return <section className="content"><div className="card" style={{ padding: 18 }}>
    <h2>Registered repositories</h2>
    <p>Review and run routing still uses the legacy account workspace during migration.
      These explicit selections take effect when the repository migration is completed.</p>
    <p><a href="#/settings/repositories">Manage existing webhooks</a></p>
    {loading ? <p>Loading repositories…</p> : error ? <p role="alert">{error}</p> : <>
      <button className="btn" onClick={() => setEditing('new')}>Register repository</button>
      <RepositoryPending repositories={repositories} />
      {repositories.length === 0 ? <p>No registered repositories yet.</p> : <table>
        <thead><tr><th>Repository</th><th>Forge origin</th><th>Workspace</th><th>Reviewer</th><th>Factory</th><th>State</th></tr></thead>
        <tbody>{repositories.map(repository => <tr key={repository.id}>
          <td><button className="btn" onClick={() => setEditing(repository)}>{repository.slug}</button></td>
          <td>{repository.forgeOrigin}</td><td>{repository.workspace}</td>
          <td>{accountLabel(repository.reviewer)}</td><td>{accountLabel(repository.factory)}</td>
          <td>{repository.enabled ? 'Enabled' : 'Disabled'}</td>
        </tr>)}</tbody>
      </table>}
      {editing && <RepositoryForm key={editing === 'new' ? 'new' : editing.id} initial={editing === 'new' ? null : editing}
        providers={providers} kinds={kinds} onSaved={saved} onCancel={() => setEditing(null)} />}
    </>}
  </div></section>;
}
