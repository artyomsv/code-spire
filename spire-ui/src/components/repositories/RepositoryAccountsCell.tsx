import type { Repository, RepositoryAccount } from './repositoriesApi';

function Account({ role, account }: { role: string; account: RepositoryAccount | null }) {
  const tone = !account || account.state === 'disabled' ? 'cancelled'
    : ['configured', 'ok'].includes(account.state) ? 'completed' : 'refused';
  const label = account ? `${account.name}${account.handle ? ` (@${account.handle})` : ''} · ${account.state}` : 'No account selected';
  return <div className="serving-cell"><span className={`pill ${tone}`} title={label}>
    <span className="glyph" />{role}: {label}
  </span></div>;
}

export default function RepositoryAccountsCell({ repository }: { repository: Repository }) {
  return <div className="serving-pair">
    <Account role="Reviewer" account={repository.reviewer} />
    <Account role="Factory" account={repository.factory} />
  </div>;
}
