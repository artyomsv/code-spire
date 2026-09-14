import { useEffect, useState } from 'react';
import { fetchPendingMappings, linkMapping, type PendingMapping, type Repository } from './repositoriesApi';
import { fetchWebhookRepos, updateWebhookRepo, type WebhookRepoView } from '../../api';

export default function RepositoryPending({ repositories, onHooksChanged }: {
  repositories: Repository[]; onHooksChanged: (hooks: WebhookRepoView[]) => void;
}) {
  const [pending, setPending] = useState<PendingMapping[]>([]);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let active = true;
    fetchPendingMappings().then(rows => { if (active) setPending(rows); })
      .catch(failure => { if (active) setError(String(failure)); });
    return () => { active = false; };
  }, []);
  async function link(row: PendingMapping, repository: Repository) {
    try {
      // Repair the owning registration so future verified deliveries carry the selected origin.
      // Re-read on every retry; a lost response must not lead to a second hook or a secret rotation.
      const hooks = await fetchWebhookRepos();
      const hook = hooks.find(candidate => candidate.id === row.registrationId);
      if (hook) {
        if (hook.providerType !== repository.scmType || hook.scope !== 'repo'
            || hook.target !== `${repository.workspace}/${repository.slug}`
            || (hook.forgeOrigin && hook.forgeOrigin !== repository.forgeOrigin)) {
          throw new Error('Registration changed. Reload before linking it to a repository.');
        }
        const changed = await updateWebhookRepo(hook.id, { providerType: hook.providerType, scope: hook.scope,
          target: hook.target, enabled: hook.enabled, eventKind: hook.eventKind,
          repositoryId: repository.id, forgeOrigin: repository.forgeOrigin });
        onHooksChanged(hooks.map(candidate => candidate.id === changed.id ? changed : candidate));
      } else {
        await linkMapping(row, repository.id);
      }
      setPending(previous => previous.filter(p => p.registrationId !== row.registrationId));
    }
    catch (failure) { setError(String(failure)); }
  }
  return <section aria-label="Mappings needing attention">
    {error && <p className="prov-note prov-error" role="alert">{error}</p>}
    {pending.length > 0 && <><div className="prov-head"><h3 className="prov-title">Mappings needing attention</h3></div>
      <p className="prov-note">Register the named repository at its verified forge origin, then link it here.</p>
      <div className="prov-scroll"><table className="prov-table"><thead><tr><th>Repository</th><th>Needs attention</th><th>Link repository</th></tr></thead>
        <tbody>{pending.map(row => <tr key={row.registrationId} id={`registration-${row.registrationId}`}>
          <td className="mono nowrap">{row.target}<div className="prov-sub">{row.scmType}</div></td>
          <td>{row.forgeOrigin ?? 'Forge origin unresolved'}<div className="prov-sub">{row.problem.split('_').join(' ')}</div></td>
          <td><div className="serving-pair">{repositories.filter(repo => repo.scmType === row.scmType && `${repo.workspace}/${repo.slug}` === row.target
            && (!row.forgeOrigin || repo.forgeOrigin === row.forgeOrigin)).map(repo => <div key={repo.id}>
              <button className="btn-ghost" title={`Link ${row.target} at ${repo.forgeOrigin}`} onClick={() => void link(row, repo)}>Link repository</button>
              <div className="prov-sub">{repo.forgeOrigin}</div>
            </div>)}</div></td>
        </tr>)}</tbody>
      </table></div>
    </>}
  </section>;
}
