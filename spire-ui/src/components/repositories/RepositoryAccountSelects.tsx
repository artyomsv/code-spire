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

function host(value: string): string {
  try { return new URL(value).host; } catch { return ''; }
}

/**
 * The origin of this forge's accounts on the same host under another scheme, when the typed origin matches
 * none. `http://api.github.com` against accounts on `https://api.github.com` once left both selects empty
 * with no reason given (first production install, 2026-11-24). Plain http stays allowed: a self-hosted
 * forge may serve it.
 */
function otherSchemeOrigin(fields: RepositoryInput, providers: ProviderView[]): string | null {
  const typed = host(fields.forgeOrigin);
  if (!typed) return null;
  const match = providers.find(p => p.type === fields.scmType && host(p.baseUrl) === typed
    && origin(p.baseUrl) !== origin(fields.forgeOrigin));
  return match ? origin(match.baseUrl) : null;
}

/** The reviewer and factory account choices. Only accounts on the repository's forge and origin are offered. */
export default function RepositoryAccountSelects({ fields, providers, onChange }: Props) {
  const compatible = providers.filter(p => p.type === fields.scmType && origin(p.baseUrl) === origin(fields.forgeOrigin));
  const suggestion = compatible.length === 0 ? otherSchemeOrigin(fields, providers) : null;
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
    {fields.scmType && fields.forgeOrigin && compatible.length === 0 && suggestion
      && <p className="prov-note">No accounts on {origin(fields.forgeOrigin)}, but this forge's accounts are on {suggestion}.{' '}
        <button type="button" className="btn-ghost sm"
          onClick={() => onChange({ forgeOrigin: suggestion, reviewerAccountId: null, factoryAccountId: null })}>
          Use {suggestion}</button></p>}
    {fields.scmType && fields.forgeOrigin && compatible.length === 0 && !suggestion
      && <p className="prov-note">No accounts on {origin(fields.forgeOrigin) || 'this origin'}. <a href="#/settings/accounts">Add an account</a> to select one.</p>}
  </>;
}
