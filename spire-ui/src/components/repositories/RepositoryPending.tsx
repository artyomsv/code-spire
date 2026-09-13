import { useEffect, useState } from 'react';
import { fetchPendingMappings, linkMapping, type PendingMapping, type Repository } from './repositoriesApi';

export default function RepositoryPending({ repositories }: { repositories: Repository[] }) {
  const [pending, setPending] = useState<PendingMapping[]>([]);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let active = true;
    fetchPendingMappings().then(rows => { if (active) setPending(rows); })
      .catch(failure => { if (active) setError(String(failure)); });
    return () => { active = false; };
  }, []);
  async function link(row: PendingMapping, id: string) {
    try { await linkMapping(row, id); setPending(previous => previous.filter(p => p.registrationId !== row.registrationId)); }
    catch (failure) { setError(String(failure)); }
  }
  return <div>
    {error && <p role="alert">{error}</p>}
    {pending.length > 0 && <><h3>Mappings needing attention</h3><p>Register the named repository at its verified forge origin, then link it here.</p></>}
    {pending.map(row => <div key={row.registrationId} id={`registration-${row.registrationId}`}>
      <p>{row.target} · {row.forgeOrigin ?? 'Forge origin unresolved'} · {row.problem}<br />Registration: {row.registrationId}</p>
      {repositories.filter(repo => repo.scmType === row.scmType && `${repo.workspace}/${repo.slug}` === row.target
        && (!row.forgeOrigin || repo.forgeOrigin === row.forgeOrigin)).map(repo => <button className="btn" key={repo.id}
          onClick={() => void link(row, repo.id)}>Link to {repo.forgeOrigin}/{repo.workspace}/{repo.slug}</button>)}
    </div>)}
  </div>;
}
