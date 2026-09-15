import { useState } from 'react';
import type { ProviderView } from '../../api';
import { saveRepository, type Repository, type RepositoryInput } from './repositoriesApi';
import { accountOptionLabel } from '../accounts';
import SettingField from '../SettingField';
import SidePanel from '../SidePanel';

interface Props {
  initial: Repository | null;
  providers: ProviderView[];
  kinds: string[];
  onSaved: (repository: Repository) => void;
  onCancel: () => void;
  prefill?: URLSearchParams;
}
function origin(value: string): string {
  try { return new URL(value).origin; } catch { return ''; }
}

/** Coordinates identify the repository; account choices never silently follow a workspace match. */
export default function RepositoryForm({ initial, providers, kinds, onSaved, onCancel, prefill = new URLSearchParams() }: Props) {
  const [fields, setFields] = useState<RepositoryInput>({
    scmType: initial?.scmType ?? prefill.get('scmType') ?? '', forgeOrigin: initial?.forgeOrigin ?? prefill.get('forgeOrigin') ?? '',
    workspace: initial?.workspace ?? prefill.get('workspace') ?? '', slug: initial?.slug ?? prefill.get('slug') ?? '', enabled: initial?.enabled ?? true,
    reviewerAccountId: initial?.reviewer?.id ?? null, factoryAccountId: initial?.factory?.id ?? null,
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const patch = (next: Partial<RepositoryInput>) => setFields(previous => ({ ...previous, ...next }));
  const compatible = providers.filter(p => p.type === fields.scmType && origin(p.baseUrl) === origin(fields.forgeOrigin));

  async function submit() {
    setBusy(true); setError(null);
    try { onSaved(await saveRepository(fields, initial)); }
    catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { setBusy(false); }
  }
  const title = initial ? 'Repository details' : 'Register repository';
  // Coordinates are the repository's identity, so an existing one shows them and does not offer
  // to change them — a different origin or slug is a different repository.
  const settled = !!initial;
  return (
    <SidePanel title={title} busy={busy} onClose={onCancel} actions={<>
      <button className="btn" disabled={busy} type="button" onClick={() => void submit()}>{busy ? 'Saving…' : 'Save repository'}</button>
      <button className="btn-ghost" type="button" onClick={onCancel}>Cancel</button></>}>
      {!initial && prefill.get('registration') && <p className="prov-note">Incoming registration: <span className="mono">{prefill.get('registration')}</span></p>}
      <SettingField label="Forge kind" scope="repository"
        hint={settled ? 'Fixed. The forge is part of this repository’s identity and cannot change.' : 'Required. Which forge hosts this repository.'}>
        <select aria-label="Forge kind" required disabled={settled} value={fields.scmType}
          onChange={e => patch({ scmType: e.target.value, reviewerAccountId: null, factoryAccountId: null })}>
          <option value="">Select forge</option>{kinds.map(kind => <option key={kind}>{kind}</option>)}
        </select></SettingField>
      <SettingField label="Forge origin" scope="repository"
        hint={settled ? 'Fixed. Two forges can host the same workspace name, so the origin identifies which one.'
          : 'Required. The forge API origin, for example https://api.github.com. It is what tells two hosts with the same workspace apart.'}>
        <input aria-label="Forge origin" type="url" required readOnly={settled} value={fields.forgeOrigin}
          onChange={e => patch({ forgeOrigin: e.target.value, reviewerAccountId: null, factoryAccountId: null })} /></SettingField>
      <SettingField label="Workspace" scope="repository"
        hint={settled ? 'Fixed. Part of this repository’s identity.' : 'Required. The owner, organisation or group that contains the repository.'}>
        <input aria-label="Workspace" required readOnly={settled} value={fields.workspace}
          onChange={e => patch({ workspace: e.target.value })} /></SettingField>
      <SettingField label="Repository slug" scope="repository"
        hint={settled ? 'Fixed. Part of this repository’s identity.' : 'Required. The repository name within the workspace.'}>
        <input aria-label="Repository slug" required readOnly={settled} value={fields.slug}
          onChange={e => patch({ slug: e.target.value })} /></SettingField>
      {(['REVIEWER', 'FACTORY'] as const).map(role => {
        const key = role === 'REVIEWER' ? 'reviewerAccountId' : 'factoryAccountId';
        const label = role === 'REVIEWER' ? 'Reviewer account' : 'Factory account';
        return <SettingField key={role} label={label} scope="repository"
          hint={role === 'REVIEWER'
            ? 'Optional. The account that reads pull requests and posts reviews here. Only accounts on this forge and origin are offered.'
            : 'Optional. The account that pushes branches and opens pull requests here. Only accounts on this forge and origin are offered.'}>
          <select aria-label={`${role} account`} value={fields[key] ?? ''} onChange={e => patch({ [key]: e.target.value || null })}>
            <option value="">No account selected</option>
            {compatible.filter(p => p.role === role).map(p => <option key={p.id} value={p.id}>{accountOptionLabel(p)}</option>)}
          </select></SettingField>;
      })}
      {fields.scmType && fields.forgeOrigin && compatible.length === 0
        && <p className="prov-note">No accounts on {origin(fields.forgeOrigin) || 'this origin'}. <a href="#/settings/accounts">Add an account</a> to select one.</p>}
      <label className="field-check"><input type="checkbox" checked={fields.enabled} onChange={e => patch({ enabled: e.target.checked })} /><span>Enabled</span></label>
      {error && <p className="prov-error" role="alert">{error}</p>}
    </SidePanel>
  );
}
