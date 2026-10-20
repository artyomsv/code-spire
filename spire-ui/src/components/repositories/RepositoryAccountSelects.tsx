import type { ProviderView } from '../../api';
import type { RepositoryInput } from './repositoriesApi';
import { accountOptionLabel } from '../accounts';
import SettingField from '../SettingField';

interface Props {
  fields: RepositoryInput;
  providers: ProviderView[];
  onChange: (next: Partial<RepositoryInput>) => void;
}

export function origin(value: string): string {
  try { return new URL(value).origin; } catch { return ''; }
}

/** The reviewer and factory account choices. Only accounts on the repository's forge and origin are offered. */
export default function RepositoryAccountSelects({ fields, providers, onChange }: Props) {
  const compatible = providers.filter(p => p.type === fields.scmType && origin(p.baseUrl) === origin(fields.forgeOrigin));
  return <>
    {(['REVIEWER', 'FACTORY'] as const).map(role => {
      const key = role === 'REVIEWER' ? 'reviewerAccountId' : 'factoryAccountId';
      const label = role === 'REVIEWER' ? 'Reviewer account' : 'Factory account';
      return <SettingField key={role} label={label} scope="repository"
        hint={role === 'REVIEWER'
          ? 'Optional. The account that reads pull requests and posts reviews here. Only accounts on this forge and origin are offered.'
          : 'Optional. The account that pushes branches and opens pull requests here. Only accounts on this forge and origin are offered.'}>
        <select aria-label={`${role} account`} value={fields[key] ?? ''} onChange={e => onChange({ [key]: e.target.value || null })}>
          <option value="">No account selected</option>
          {compatible.filter(p => p.role === role).map(p => <option key={p.id} value={p.id}>{accountOptionLabel(p)}</option>)}
        </select></SettingField>;
    })}
    {fields.scmType && fields.forgeOrigin && compatible.length === 0
      && <p className="prov-note">No accounts on {origin(fields.forgeOrigin) || 'this origin'}. <a href="#/settings/accounts">Add an account</a> to select one.</p>}
  </>;
}
